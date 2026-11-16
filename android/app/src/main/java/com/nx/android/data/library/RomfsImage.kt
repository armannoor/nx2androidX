package com.nx.android.data.library

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * A portable pack's game files: the switchbrew RomFS image (the format LibHac, nstool and hactool read).
 *
 * The game's files are in one file, so a pack can be copied as one file and the volume does not have to
 * hold thousands of small ones. The host reads the same format (hle_fs_container.c).
 *
 * Layout (little endian):
 * - header (0x28 bytes): headerSize, dirHashOff/Size, dirTableOff/Size, fileHashOff/Size, fileTableOff/Size,
 *   dataOff
 * - directory table: entries of 0x18 bytes (parent, sibling, childDir, childFile, nextHash, nameLen) and the
 *   name after them, padded to a multiple of 4
 * - file table: entries of 0x20 bytes (parent, sibling, dataOff, dataSize, nextHash, nameLen) and the name
 *   after them, padded to a multiple of 4
 * - hash tables: one u32 a bucket, the offset of the first entry of that bucket (0xffffffff when it is empty)
 *
 * The data follows the tables; every file starts on a 16-byte boundary.
 *
 * Pure file operations, free of Android, so the tests run on the JVM.
 */
object RomfsImage {

    /** The image's name inside a portable pack: the host looks for it beside the pack's other files. */
    const val FILE_NAME = "romfs.bin"

    private const val HEADER = 0x28
    private const val DIR_ENTRY = 0x18
    private const val FILE_ENTRY = 0x20
    private const val DATA_ALIGN = 16
    private const val NONE = -1          // the format's 0xffffffff (an entry that is not there)
    private const val HEADER_ALT = 0x50      // the same header with 64-bit fields (what LibHac's builder writes)

    /** The host reads the image as little endian (hle_fs_container.c), so the numbers go out that way. */
    private fun DataOutputStream.writeLeInt(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun DataOutputStream.writeLeLong(value: Long) {
        writeLeInt((value and 0xffffffffL).toInt())
        writeLeInt((value shr 32).toInt())
    }

    /**
     * The path hash of the RomFS format (the one LibHac computes): the parent's offset, then every byte of
     * the name, rotating the hash right by five each time.
     */
    private fun pathHash(parentOffset: Int, name: ByteArray): Int {
        var h = 0x075BCD15 xor parentOffset
        for (b in name) {
            h = (b.toInt() xor ((h ushr 5) or (h shl 27))) and 0xffffffff.toInt()
        }
        return h
    }

    /** The number of hash buckets a table of [count] entries has: the RomFS builder's rule. */
    private fun bucketCount(count: Int): Int {
        if (count < 3) return 3
        if (count < 19) return count or 1
        var n = count or 1
        while (n % 2 == 0 || n % 3 == 0 || n % 5 == 0 || n % 7 == 0 || n % 11 == 0 || n % 13 == 0 || n % 17 == 0) n++
        return n
    }

    private fun align4(n: Int): Int = (n + 3) and 3.inv()

    /** What the writer ended up with. */
    sealed interface Written {
        /** The image is there: its size and its digest. */
        data class Ok(val size: Long, val sha256: String) : Written

        /** It is not: the reason says why. */
        data class Failed(val reason: String) : Written
    }

    // ---------- the tree ----------

    /** One directory of the tree. */
    private class Dir(
        val name: ByteArray,
        val path: String,
        val parent: Int,          // the index in the directory table
        var offset: Int = 0,      // where its entry sits in the directory table
        var sibling: Int = NONE,
        var hashNext: Int = NONE,
    )

    /** One file of the tree. */
    private class FileEntry(
        val name: ByteArray,
        val path: String,
        val parent: Int,          // the index of its directory
        val size: Long,
        var offset: Int = 0,      // where its entry sits in the file table
        var dataOffset: Long = 0, // where its bytes sit in the data area
        var sibling: Int = NONE,
        var hashNext: Int = NONE,
    )

    /** The tree as the image wants it: the directories in breadth-first order, the files by directory. */
    private class Tree(
        val dirs: List<Dir>,
        val files: List<FileEntry>,
        val bytes: Long,
    )

    /** Walk the tree at [source]: the directories first (breadth-first), then the files by directory. */
    private fun collect(source: File): Tree? {
        val dirs = ArrayList<Dir>()
        val files = ArrayList<FileEntry>()
        var bytes = 0L

        dirs += Dir(ByteArray(0), "", 0)

        // the directories, breadth-first: a parent comes before its children
        var at = 0
        while (at < dirs.size) {
            val dir = dirs[at]
            val physical = if (dir.path.isEmpty()) source else File(source, dir.path)
            val children = physical.listFiles() ?: return null
            for (child in children.filter { it.isDirectory }.sortedBy { it.name }) {
                val path = if (dir.path.isEmpty()) child.name else "${dir.path}/${child.name}"
                dirs += Dir(child.name.toByteArray(Charsets.UTF_8), path, at)
            }
            for (child in children.filter { !it.isDirectory }.sortedBy { it.name }) {
                val path = if (dir.path.isEmpty()) child.name else "${dir.path}/${child.name}"
                files += FileEntry(child.name.toByteArray(Charsets.UTF_8), path, at, child.length())
                bytes += child.length()
            }
            at++
        }
        return Tree(dirs, files, bytes)
    }

    // ---------- writing ----------

    /**
     * Write the tree at [source] into [target] as a RomFS image.
     *
     * The files are copied one after another and each one's length is checked against the tree that was
     * measured first, so a file that changed or a copy that broke is caught here and not on the device.
     * The image's digest is computed while it is written, so the receiving phone can check it later.
     *
     * @param onProgress how much of the data has been written (bytes)
     * @return what was written, or why it did not work
     */
    fun write(source: File, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Written {
        if (!source.isDirectory) return Written.Failed("the game files are not there")
        val tree = collect(source) ?: return Written.Failed("the game files could not be read")
        if (tree.files.isEmpty()) return Written.Failed("the game files are empty")

        // the entries' places in their tables
        var dirOffset = 0
        for (d in tree.dirs) { d.offset = dirOffset; dirOffset += DIR_ENTRY + align4(d.name.size) }
        var fileOffset = 0
        for (f in tree.files) { f.offset = fileOffset; fileOffset += FILE_ENTRY + align4(f.name.size) }

        // the tables' places in the image
        val dirHashOff = HEADER
        val dirHashSize = bucketCount(tree.dirs.size) * 4
        val dirTableOff = dirHashOff + dirHashSize
        val dirTableSize = dirOffset
        val fileHashOff = dirTableOff + dirTableSize
        val fileHashSize = bucketCount(tree.files.size) * 4
        val fileTableOff = fileHashOff + fileHashSize
        val fileTableSize = fileOffset
        val dataOff = (fileTableOff + fileTableSize + (DATA_ALIGN - 1)) and (DATA_ALIGN - 1).inv()

        // the data's places: the files in the table's order, every one of them 16-byte aligned
        var dataSize = 0L
        for (f in tree.files) {
            f.dataOffset = dataSize
            dataSize += f.size
            dataSize += ((DATA_ALIGN - (dataSize % DATA_ALIGN)) % DATA_ALIGN)
        }

        // the children: every directory's files and its directories, each in name order
        val dirChildren = HashMap<Int, MutableList<Dir>>()
        for (i in tree.dirs.indices) if (i > 0) dirChildren.getOrPut(tree.dirs[i].parent) { ArrayList() } += tree.dirs[i]
        for ((parent, kids) in dirChildren) {
            for (i in kids.indices) kids[i].sibling = if (i + 1 < kids.size) kids[i + 1].offset else NONE
        }
        val fileChildren = HashMap<Int, MutableList<FileEntry>>()
        for (f in tree.files) fileChildren.getOrPut(f.parent) { ArrayList() } += f
        for ((parent, kids) in fileChildren) {
            for (i in kids.indices) kids[i].sibling = if (i + 1 < kids.size) kids[i + 1].offset else NONE
        }

        // the hash tables: a new entry goes to the head of its bucket
        val dirBuckets = IntArray(bucketCount(tree.dirs.size)) { NONE }
        for (d in tree.dirs) {
            val bucket = (pathHash(tree.dirs[d.parent].offset, d.name).toLong() and 0xffffffffL) % dirBuckets.size
            d.hashNext = dirBuckets[bucket.toInt()]
            dirBuckets[bucket.toInt()] = d.offset
        }
        val fileBuckets = IntArray(bucketCount(tree.files.size)) { NONE }
        for (f in tree.files) {
            val bucket = (pathHash(tree.dirs[f.parent].offset, f.name).toLong() and 0xffffffffL) % fileBuckets.size
            f.hashNext = fileBuckets[bucket.toInt()]
            fileBuckets[bucket.toInt()] = f.offset
        }

        try {
            target.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            val out = DataOutputStream(
                BufferedOutputStream(DigestOutputStream(FileOutputStream(target), digest), 1 shl 16))
            out.writeLeInt(HEADER)
            out.writeLeInt(dirHashOff); out.writeLeInt(dirHashSize)
            out.writeLeInt(dirTableOff); out.writeLeInt(dirTableSize)
            out.writeLeInt(fileHashOff); out.writeLeInt(fileHashSize)
            out.writeLeInt(fileTableOff); out.writeLeInt(fileTableSize)
            out.writeLeInt(dataOff)
            for (b in dirBuckets) out.writeLeInt(b)

            // the directory table: every directory's own children, its sibling, and its hash chain
            tree.dirs.forEachIndexed { index, d ->
                out.writeLeInt(tree.dirs[d.parent].offset)
                out.writeLeInt(d.sibling)
                out.writeLeInt(dirChildren[index]?.firstOrNull()?.offset ?: NONE)
                out.writeLeInt(fileChildren[index]?.firstOrNull()?.offset ?: NONE)
                out.writeLeInt(d.hashNext)
                out.writeLeInt(d.name.size)
                out.write(d.name)
                repeat(align4(d.name.size) - d.name.size) { out.write(0) }
            }

            for (b in fileBuckets) out.writeLeInt(b)

            // the file table
            for (f in tree.files) {
                out.writeLeInt(tree.dirs[f.parent].offset)
                out.writeLeInt(f.sibling)
                out.writeLeLong(f.dataOffset)
                out.writeLeLong(f.size)
                out.writeLeInt(f.hashNext)
                out.writeLeInt(f.name.size)
                out.write(f.name)
                repeat(align4(f.name.size) - f.name.size) { out.write(0) }
            }

            // the gap between the file table and the data: the data starts on a 16-byte boundary
            repeat(dataOff - (fileTableOff + fileTableSize)) { out.write(0) }

            // the data
            var written = 0L          // the bytes in the data area, the padding included
            var copiedTotal = 0L      // the files' bytes
            for (f in tree.files) {
                val copied = copy(File(source, f.path), out, f.size)
                if (copied != f.size) { out.close(); target.delete(); return Written.Failed("a game file could not be copied") }
                copiedTotal += copied
                written += copied
                val pad = ((DATA_ALIGN - (written % DATA_ALIGN)) % DATA_ALIGN).toInt()
                repeat(pad) { out.write(0) }
                written += pad
                onProgress(copiedTotal, tree.bytes)
            }
            out.flush()
            out.close()
            if (target.length() != dataOff + dataSize) { target.delete(); return Written.Failed("the image does not have the size it should") }
            return Written.Ok(target.length(), digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: IOException) {
            target.delete()
            return Written.Failed(e.message ?: "the image could not be written")
        }
    }

    /** Copy one file into the image, checking its length. */
    private fun copy(file: File, out: DataOutputStream, expected: Long): Long {
        var copied = 0L
        BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                copied += n
            }
        }
        return if (copied == expected) copied else -1
    }

    // ---------- reading (the self-check and the import side) ----------

    /** A reader of the image, strict: a broken image is refused, never half read. */
    class Reader internal constructor(private val file: RandomAccessFile) : Closeable {

        /** One entry of the image. */
        class Entry(
            val path: String,
            val isDir: Boolean,
            val size: Long,
            val dataOffset: Long,
        )

        private val entries: List<Entry>
        private val byPath: Map<String, Entry>

        init {
            val head = ByteArray(HEADER_ALT)
            file.seek(0)
            val size = file.length()
            val first = minOf(HEADER_ALT.toLong(), size).toInt()
            file.readFully(head, 0, first)
            fun u32(off: Int) = (head[off].toInt() and 0xff) or ((head[off + 1].toInt() and 0xff) shl 8) or
                ((head[off + 2].toInt() and 0xff) shl 16) or ((head[off + 3].toInt() and 0xff) shl 24)
            fun u64(off: Int) = (u32(off).toLong() and 0xffffffffL) or ((u32(off + 4).toLong() and 0xffffffffL) shl 32)
            val headerSize = u32(0)
            val dirTableOff: Int; val dirTableSize: Int; val fileTableOff: Int; val fileTableSize: Int
            val dataOffset: Long
            if (headerSize == HEADER) {
                dirTableOff = u32(0x0c); dirTableSize = u32(0x10)
                fileTableOff = u32(0x1c); fileTableSize = u32(0x20)
                dataOffset = u32(0x24).toLong() and 0xffffffffL
            } else if (headerSize == HEADER_ALT) {
                dirTableOff = u64(0x18).toInt(); dirTableSize = u64(0x20).toInt()
                fileTableOff = u64(0x38).toInt(); fileTableSize = u64(0x40).toInt()
                dataOffset = u64(0x48)
            } else throw IOException("the image's header is not a RomFS header")
            if (dirTableOff < 0 || fileTableOff < 0 || dataOffset < 0 ||
                dirTableOff.toLong() + dirTableSize > size || fileTableOff.toLong() + fileTableSize > size ||
                dataOffset > size) throw IOException("the image's tables are outside the file")

            val dirPaths = HashMap<Int, String>()
            val dirs = readTable(dirTableOff, dirTableSize, DIR_ENTRY, dirPaths, dirPaths)
            val files = readTable(fileTableOff, fileTableSize, FILE_ENTRY, HashMap(), dirPaths)
            // the children chains: a directory's first child must be its own, and so must its siblings
            val dirByOffset = HashMap<Int, Raw>()
            val fileByOffset = HashMap<Int, Raw>()
            var off = 0
            for (d in dirs) { dirByOffset[off] = d; off += DIR_ENTRY + align4(d.nameLength) }
            off = 0
            for (f in files) { fileByOffset[off] = f; off += FILE_ENTRY + align4(f.nameLength) }
            for ((offset, d) in dirByOffset) {
                if (d.childDir != -1) {
                    val child = dirByOffset[d.childDir] ?: throw IOException("the image's directory chain is broken")
                    if (child.parent != offset) throw IOException("the image's directory chain is broken")
                }
                if (d.childFile != -1) {
                    val child = fileByOffset[d.childFile] ?: throw IOException("the image's file chain is broken")
                    if (child.parent != offset) throw IOException("the image's file chain is broken")
                }
            }
            // and the sibling chains: every sibling shares the parent
            for ((offset, d) in dirByOffset) {
                var s = d.sibling
                while (s != -1) {
                    val sib = dirByOffset[s] ?: throw IOException("the image's sibling chain is broken")
                    if (sib.parent != d.parent) throw IOException("the image's sibling chain is broken")
                    s = sib.sibling
                }
            }
            for ((offset, f) in fileByOffset) {
                var s = f.sibling
                while (s != -1) {
                    val sib = fileByOffset[s] ?: throw IOException("the image's sibling chain is broken")
                    if (sib.parent != f.parent) throw IOException("the image's sibling chain is broken")
                    s = sib.sibling
                }
            }
            val all = ArrayList<Entry>()
            for (d in dirs) all += Entry(d.path, true, 0, 0)
            for (f in files) {
                if (f.dataOffset < 0 || f.size < 0 || dataOffset + f.dataOffset + f.size > size) {
                    throw IOException("the image has a file outside the file")
                }
                all += Entry(f.path, false, f.size, dataOffset + f.dataOffset)
            }
            entries = all
            byPath = all.associateBy { it.path }
            if (byPath.size != all.size) throw IOException("the image has the same path twice")
        }

        private class Raw(
            val path: String,
            val size: Long,
            val dataOffset: Long,
            val parent: Int,
            val sibling: Int,
            val childDir: Int,
            val childFile: Int,
            val nameLength: Int,
        )

        /** Read one table and build its entries' paths. */
        private fun readTable(tableOff: Int, tableSize: Int, entrySize: Int,
                              own: MutableMap<Int, String>, parents: Map<Int, String>): List<Raw> {
            val bytes = ByteArray(tableSize)
            file.seek(tableOff.toLong())
            file.readFully(bytes)
            fun u32(off: Int) = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8) or
                ((bytes[off + 2].toInt() and 0xff) shl 16) or ((bytes[off + 3].toInt() and 0xff) shl 24)
            fun u64(off: Int) = (u32(off).toLong() and 0xffffffffL) or ((u32(off + 4).toLong() and 0xffffffffL) shl 32)
            val out = ArrayList<Raw>()
            var off = 0
            while (off + entrySize <= tableSize) {
                val parent = u32(off)
                val nameLen = u32(off + entrySize - 4)
                if (nameLen < 0 || off + entrySize + align4(nameLen) > tableSize) {
                    throw IOException("the image's table is broken")
                }
                val name = String(bytes, off + entrySize, nameLen, Charsets.UTF_8)
                val parentPath = if (off == 0) "" else parents[parent] ?: throw IOException("the image's table is broken")
                val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                own[off] = path
                out += Raw(path, if (entrySize == FILE_ENTRY) u64(off + 16) else 0,
                    if (entrySize == FILE_ENTRY) u64(off + 8) else 0,
                    parent, u32(off + 4),
                    if (entrySize == DIR_ENTRY) u32(off + 8) else -1,
                    if (entrySize == DIR_ENTRY) u32(off + 0x0c) else -1,
                    nameLen)
                off += entrySize + align4(nameLen)
            }
            if (off != tableSize) throw IOException("the image's table does not end on an entry")
            return out
        }

        /** The entries the image holds. */
        fun entries(): List<Entry> = entries

        /** The entry a path names ("a/b/c"), null when there is none. */
        fun find(path: String): Entry? = byPath[path.trimStart('/')]

        /** A file's bytes. */
        fun bytes(path: String): ByteArray? {
            val e = find(path) ?: return null
            if (e.isDir) return null
            val out = ByteArray(e.size.toInt())
            file.seek(e.dataOffset)
            file.readFully(out)
            return out
        }

        override fun close() = file.close()
    }

    /** Open the image at [path], or null when it is not one. */
    fun open(path: File): Reader? = try {
        Reader(RandomAccessFile(path, "r"))
    } catch (e: IOException) {
        null
    }

    /**
     * Check the image at [path]: the header, the tables and every file's place in the data area.
     *
     * This is what the export runs before the pack is renamed into place, so a broken image never lands.
     */
    fun verify(path: File): String? {
        val reader = open(path) ?: return "the image could not be read"
        return reader.use {
            try {
                val files = it.entries().filter { e -> !e.isDir }
                if (files.isEmpty()) return "the image holds no files"
                null
            } catch (e: IOException) {
                e.message ?: "the image is broken"
            }
        }
    }
}
