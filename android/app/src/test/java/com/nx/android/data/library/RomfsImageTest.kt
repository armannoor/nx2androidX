package com.nx.android.data.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The RomFS image: the game's files in one file, in the format LibHac and nstool read.
 *
 * The tests read the image the way the host does (a strict reader) and with a second reader written here,
 * so a mistake in one of them shows up.
 */
class RomfsImageTest {

    private fun tree(): File {
        val tree = Files.createTempDirectory("romfs-tree").toFile()
        File(tree, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(tree, "z.bin").writeBytes(byteArrayOf(9))
        File(tree, "sub").mkdirs()
        File(tree, "sub/b.txt").writeText("hello")
        File(tree, "sub/deep").mkdirs()
        File(tree, "sub/deep/c.dat").writeBytes(ByteArray(1000) { it.toByte() })
        return tree
    }

    @Test
    fun `a tree becomes one image the reader can read`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            val written = RomfsImage.write(tree, out)
            if (written is RomfsImage.Written.Failed) println("DEBUG write failed: " + written.reason)
            assertTrue(written is RomfsImage.Written.Ok)

            val reader = RomfsImage.open(out)
            assertNotNull(reader)
            reader!!.use {
                val paths = it.entries().map { e -> e.path }.sorted()
                assertEquals(listOf("", "a.bin", "sub", "sub/b.txt", "sub/deep", "sub/deep/c.dat", "z.bin"), paths)
                assertArrayEquals(byteArrayOf(1, 2, 3), it.bytes("a.bin"))
                assertEquals("hello", String(it.bytes("sub/b.txt")!!))
                assertEquals(1000, it.bytes("sub/deep/c.dat")!!.size)
                assertNull(it.find("sub/nope"))
                assertTrue(it.find("sub")!!.isDir)
                assertTrue(!it.find("a.bin")!!.isDir)
            }
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `the header and the tables are where the format says`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            assertTrue(RomfsImage.write(tree, out) is RomfsImage.Written.Ok)
            val b = out.readBytes()
            fun u32(off: Int) = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
            assertEquals(0x28, u32(0))                     // the header is 0x28 bytes
            val dirTable = u32(0x0c); val dirTableSize = u32(0x10)
            val fileTable = u32(0x1c); val fileTableSize = u32(0x20)
            val dataOff = u32(0x24)
            assertEquals(0x28 + bucketCount(3) * 4, dirTable)
            assertTrue(dirTable + dirTableSize <= fileTable)
            assertTrue(fileTable + fileTableSize <= dataOff)
            assertEquals(0, dataOff % 16)                  // the data starts on a 16-byte boundary
            assertEquals(b.size.toLong(), (dataOff + dataSize(out)).toLong())        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `every entry is in its hash bucket`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            assertTrue(RomfsImage.write(tree, out) is RomfsImage.Written.Ok)
            val b = out.readBytes()
            fun u32(off: Int) = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
            // the file table: every file must be found in the bucket its hash names
            val fileTable = u32(0x1c)
            val buckets = u32(0x14); val bucketCount = u32(0x18) / 4
            val dirTable = u32(0x0c)
            var off = fileTable
            var checked = 0
            while (off < fileTable + u32(0x20)) {
                val parent = u32(off)
                val nameLen = u32(off + 0x1c)
                val name = String(b, off + 0x20, nameLen, Charsets.UTF_8)
                val hash = pathHash(parent, name.toByteArray(Charsets.UTF_8))
                val bucket = (hash.toLong() and 0xffffffffL) % bucketCount
                // walk the bucket: the entry has to be in it
                var entry = u32(buckets + bucket.toInt() * 4)
                var found = false
                while (entry != -1) {
                    val eNameLen = u32(fileTable + entry + 0x1c)
                    val eName = String(b, fileTable + entry + 0x20, eNameLen, Charsets.UTF_8)
                    if (eName == name && u32(fileTable + entry) == parent) { found = true; break }
                    entry = u32(fileTable + entry + 0x18)
                }
                assertTrue("$name is not in its hash bucket", found)
                checked++
                off += 0x20 + ((nameLen + 3) and 3.inv())
            }
            assertEquals(4, checked)                       // a.bin, z.bin, sub/b.txt and sub/deep/c.dat
            assertTrue(dirTable > 0)
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `a second reader agrees with the first`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            assertTrue(RomfsImage.write(tree, out) is RomfsImage.Written.Ok)
            val mine = RomfsImage.open(out)!!.use { r ->
                r.entries().map { e -> Triple(e.path, e.isDir, e.size) }.sortedBy { it.first }
            }
            val theirs = SecondReader(out).entries().sortedBy { it.path }
            assertEquals(theirs.size, mine.size)
            for (i in mine.indices) {
                assertEquals(theirs[i].path, mine[i].first)
                assertEquals(theirs[i].isDir, mine[i].second)
                assertEquals(theirs[i].size, mine[i].third)
            }
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `every file comes back byte for byte`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            assertTrue(RomfsImage.write(tree, out) is RomfsImage.Written.Ok)
            var checked = 0
            val stack = ArrayDeque<File>()
            stack.addLast(tree)
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                for (child in dir.listFiles() ?: emptyArray()) {
                    if (child.isDirectory) { stack.addLast(child); continue }
                    val path = child.relativeTo(tree).path
                    val bytes = RomfsImage.open(out)!!.use { it.bytes(path) }
                    assertNotNull("the image has no $path", bytes)
                    assertArrayEquals(child.readBytes(), bytes)
                    checked++
                }
            }
            assertEquals(4, checked)
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `every directory points at its own children`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            assertTrue(RomfsImage.write(tree, out) is RomfsImage.Written.Ok)
            val b = out.readBytes()
            fun u32(off: Int) = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
            val dirTable = u32(0x0c); val dirTableSize = u32(0x10)
            val fileTable = u32(0x1c); val fileTableSize = u32(0x20)
            // the directories: their first child must be a directory whose parent is this one
            val dirParents = HashMap<Int, Int>()
            val dirOffsets = ArrayList<Int>()
            var off = dirTable
            while (off < dirTable + dirTableSize) {
                val nameLen = u32(off + 0x14)
                dirParents[off - dirTable] = u32(off)
                dirOffsets += off - dirTable
                off += 0x18 + ((nameLen + 3) and 3.inv())
            }
            val fileParents = HashMap<Int, Int>()
            off = fileTable
            while (off < fileTable + fileTableSize) {
                val nameLen = u32(off + 0x1c)
                fileParents[off - fileTable] = u32(off)
                off += 0x20 + ((nameLen + 3) and 3.inv())
            }
            off = dirTable
            while (off < dirTable + dirTableSize) {
                val nameLen = u32(off + 0x14)
                val here = off - dirTable
                val childDir = u32(off + 8)
                val childFile = u32(off + 0x0c)
                if (childDir != -1) {
                    assertEquals("the first directory child of $here", here, dirParents[childDir])
                }
                if (childFile != -1) {
                    assertEquals("the first file child of $here", here, fileParents[childFile])
                }
                off += 0x18 + ((nameLen + 3) and 3.inv())
            }
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `an empty tree is refused`() {
        val tree = Files.createTempDirectory("romfs-tree").toFile()
        try {
            val out = File(tree, "romfs.bin")
            val written = RomfsImage.write(tree, out)
            assertTrue(written is RomfsImage.Written.Failed)
            assertTrue(!out.exists())
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test
    fun `a directory is refused`() {
        val out = File(Files.createTempDirectory("romfs-tree").toFile(), "romfs.bin")
        try {
            val written = RomfsImage.write(out.parentFile, out)
            assertTrue(written is RomfsImage.Written.Failed)
        } finally {
            out.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `the writer says the size and the digest of what it wrote`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            val written = RomfsImage.write(tree, out)
            if (written is RomfsImage.Written.Failed) println("DEBUG write failed: " + written.reason)
            assertTrue(written is RomfsImage.Written.Ok)
            val ok = written as RomfsImage.Written.Ok
            assertEquals(out.length(), ok.size)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            out.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, ok.sha256)
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `a broken image is refused`() {
        val tree = tree()
        try {
            val out = File(tree.parentFile, "romfs.bin")
            assertTrue(RomfsImage.write(tree, out) is RomfsImage.Written.Ok)
            assertNull(RomfsImage.verify(out))
            // a header that is not a RomFS header: the image is not one
            val broken = File(tree.parentFile, "broken.bin")
            broken.writeBytes(ByteArray(64))
            assertNotNull(RomfsImage.verify(broken))
            assertNull(RomfsImage.open(broken))
        } finally {
            tree.parentFile.deleteRecursively()
        }
    }

    // the pieces the tests above need

    private fun bucketCount(count: Int): Int {
        if (count < 3) return 3
        if (count < 19) return count or 1
        var n = count or 1
        while (n % 2 == 0 || n % 3 == 0 || n % 5 == 0 || n % 7 == 0 || n % 11 == 0 || n % 13 == 0 || n % 17 == 0) n++
        return n
    }

    private fun pathHash(parentOffset: Int, name: ByteArray): Int {
        var h = 0x075BCD15 xor parentOffset
        for (b in name) h = (b.toInt() xor ((h ushr 5) or (h shl 27))) and 0xffffffff.toInt()
        return h
    }

    /** How much data the image holds: the tables' sizes and the data. */
    private fun dataSize(image: File): Int {
        val b = image.readBytes()
        fun u32(off: Int) = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
        return b.size - u32(0x24)
    }
}

/**
 * A second reader of the image, written here and not sharing a line with the app's: it parses the raw
 * bytes on its own, so the two can be compared.
 */
private class SecondReader(file: File) {
    private val bytes = file.readBytes()

    data class Entry(val path: String, val isDir: Boolean, val size: Long)

    private fun u32(off: Int): Int = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8) or
        ((bytes[off + 2].toInt() and 0xff) shl 16) or ((bytes[off + 3].toInt() and 0xff) shl 24)

    private fun u64(off: Int): Long = (u32(off).toLong() and 0xffffffffL) or ((u32(off + 4).toLong() and 0xffffffffL) shl 32)

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        val dirTable = u32(0x0c); val dirTableSize = u32(0x10)
        val paths = HashMap<Int, String>()
        var off = dirTable
        while (off < dirTable + dirTableSize) {
            val nameLen = u32(off + 0x14)
            val name = String(bytes, off + 0x18, nameLen, Charsets.UTF_8)
            val parent = if (off == dirTable) "" else paths.getValue(u32(off))
            val path = if (parent.isEmpty()) name else "$parent/$name"
            paths[off - dirTable] = path
            out += Entry(path, true, 0)
            off += 0x18 + ((nameLen + 3) and 3.inv())
        }
        val fileTable = u32(0x1c); val fileTableSize = u32(0x20)
        off = fileTable
        while (off < fileTable + fileTableSize) {
            val nameLen = u32(off + 0x1c)
            val name = String(bytes, off + 0x20, nameLen, Charsets.UTF_8)
            val parent = paths.getValue(u32(off))
            val path = if (parent.isEmpty()) name else "$parent/$name"
            out += Entry(path, false, u64(off + 0x10))
            off += 0x20 + ((nameLen + 3) and 3.inv())
        }
        return out
    }
}
