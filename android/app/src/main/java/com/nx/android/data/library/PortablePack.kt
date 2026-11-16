package com.nx.android.data.library

import com.nx.android.data.StorageVolumes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A portable pack: a game's finished port as one folder that can be copied to another phone and run there.
 *
 * The folder holds the pack's own files, the game's files in one RomFS image (romfs.bin), the port's profile
 * (the recipe the host is built from) and the manifest — without the host binary: the phone that receives it
 * builds its own host from the profile, so the glue is always that phone's own.
 *
 * Pure file operations, free of Android, so the tests run on the JVM.
 */
object PortablePack {

    const val MANIFEST = "nx2a-data.json"
    const val ENV = "env.txt"
    const val PROFILE_DIR = "profile"

    /** The folder the host lived in before the runtime update moved it into a .runtime- folder. */
    const val HOST_DIR = "host"

    /** The pack's files that are the device's, not the game's: they are not carried over. */
    private val NOT_CARRIED = setOf("log.txt", "log.prev.txt", "vk_pipeline_cache.bin", "vk_pipelines.bin")

    /** A folder with a pack manifest in it is a pack. */
    fun isPackFolder(dir: File): Boolean = File(dir, MANIFEST).isFile

    /** The folder name a portable pack gets: the game's name, cleaned of what a file name cannot hold. */
    fun folderName(name: String, titleId: String): String {
        val cleaned = name.trim().map { if (it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim()
        return cleaned.ifEmpty { titleId }.take(120)
    }

    /**
     * The env rows for a portable pack: the row that pointed at the game's files is gone (the container is
     * found by its own name), and the rows that name files inside the pack point at the pack's new place.
     */
    fun envFor(pack: File, newPack: File): String {
        val old = pack.absolutePath.trimEnd('/')
        val now = newPack.absolutePath.trimEnd('/')
        val rows = ArrayList<String>()
        for (line in envLines(pack)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.substringBefore('=')
            if (key == "DM_ROMFS") continue
            val value = trimmed.substringAfter('=', "")
            rows += if (value.startsWith(old)) "$key=${now}${value.removePrefix(old)}" else trimmed
        }
        return rows.joinToString("\n") + "\n"
    }

    /**
     * The manifest of a portable pack: the host is not in it (the receiving phone builds its own), and neither
     * is the pointer to the game's files (they are in the pack's own image — the pointer names a folder on the
     * phone that exported it, which means nothing on the phone that receives it).
     */
    fun manifestFor(pack: File, romfsSize: Long = 0, romfsSha256: String = ""): String {
        val json = JSONObject(File(pack, MANIFEST).readText(Charsets.UTF_8))
        json.remove("host")
        json.remove("romfs_at")
        // the game's files are in the image: its size and its digest say what the receiving phone should find
        if (romfsSize > 0) json.put("romfs_size", romfsSize)
        if (romfsSha256.isNotEmpty()) json.put("romfs_sha256", romfsSha256)
        return json.toString(2) + "\n"
    }

    /** The env.txt rows (the porter's comments included). */
    fun envLines(pack: File): List<String> = try {
        File(pack, ENV).readText(Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    /** The pack's files that go into a portable pack: everything but the host and the device's own leftovers. */
    fun carriedFiles(pack: File): List<File> {
        val host = hostOf(pack)
        val out = ArrayList<File>()
        val stack = java.util.ArrayDeque<File>()
        stack.addLast(pack)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles() ?: emptyArray()) {
                val name = child.name
                if (child.isDirectory) {
                    if (name.startsWith(".runtime-")) continue      // the runtime update's staging folders
                    // The host's own folder (the old place, before the runtime update moved the host into
                    // a .runtime- folder): a host that came with the pack was built for the exporting
                    // phone's toolchain, and the receiving phone builds its own.
                    if (dir == pack && name == HOST_DIR) continue
                    stack.addLast(child)
                } else {
                    if (name in NOT_CARRIED) continue
                    if (host != null && child.absolutePath == host.absolutePath) continue
                    out += child
                }
            }
        }
        return out
    }

    /** The host binary the manifest names (the portable pack leaves it behind). */
    fun hostOf(pack: File): File? = try {
        val json = JSONObject(File(pack, MANIFEST).readText(Charsets.UTF_8))
        if (!json.has("host")) null else File(pack, json.getString("host")).takeIf { it.isFile }
    } catch (e: Exception) {
        null
    }

    /** The profile the pack was built with (the folder the host is built from), if the app can find one. */
    fun profileIn(pack: File): File? {
        val dir = File(pack, PROFILE_DIR)
        if (!dir.isDirectory) return null
        val child = dir.listFiles()?.firstOrNull { it.isDirectory } ?: return null
        return child.takeIf { File(it, "game.tsv").isFile && File(it, "android.tsv").isFile }
    }

    /** The game's files in one RomFS image, when the pack has them. */
    fun imageOf(pack: File): File? = File(pack, RomfsImage.FILE_NAME).takeIf { it.isFile }

    /** The digest the manifest records for the image, "" when it records none. */
    fun imageSha256(pack: File): String = try {
        JSONObject(File(pack, MANIFEST).readText(Charsets.UTF_8)).optString("romfs_sha256")
    } catch (e: Exception) {
        ""
    }

    /**
     * Check the image's digest against the manifest.
     *
     * Reading ten gigabytes to hash it takes a while, so this is done once, before the first runtime build
     * on the receiving phone — not on every scan of the library.
     *
     * @return null when the image is the one the pack was made with, the reason when it is not
     */
    fun checkImage(pack: File): String? {
        val expected = imageSha256(pack)
        if (expected.isEmpty()) return null          // an old pack records no digest: nothing to check
        val image = imageOf(pack) ?: return "the game's files are not in the pack"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        image.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(expected, ignoreCase = true)) return "the game's files in this pack are not the ones it was made with"
        return null
    }

    /** The paths the player added as portable packs, as they are remembered. */
    object AddedPaths {
        fun encode(paths: List<String>): String {
            val array = JSONArray()
            for (p in paths) array.put(p)
            return array.toString()
        }

        fun decode(text: String): List<String> = try {
            val array = JSONArray(text)
            val out = ArrayList<String>()
            for (i in 0 until array.length()) {
                val p = array.optString(i)
                if (p.isNotEmpty()) out += p
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** What an export ended up as. */
    data class Export(
        val ok: Boolean,
        val folder: File? = null,
        val files: Int = 0,
        val bytes: Long = 0L,
        val reason: String = "",
    )

    /**
     * Write a portable pack into [parent]: a folder named after the game, holding the pack's files, the game's
     * files in one RomFS image, the profile and the manifest.
     *
     * The folder is written under a temporary name and renamed into place at the end, so a folder that is
     * there is always a finished one. Nothing in the source pack is touched.
     *
     * @param gameFiles the game's files: the build workspace's extract, or the pack's own RomFS folder
     * @param profile the profile folder to carry (the title ID's folder), null when there is none
     */
    fun export(
        pack: File,
        gameFiles: File,
        profile: File?,
        titleId: String,
        name: String,
        parent: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        singleFileLimit: Long? = singleFileLimit(parent),
    ): Export {
        if (!pack.isDirectory) return Export(false, reason = "the pack is not there")
        if (!gameFiles.isDirectory) return Export(false, reason = "the game's files are not there")
        if (!parent.isDirectory && !parent.mkdirs()) return Export(false, reason = "the folder cannot be created")
        val folder = File(parent, folderName(name, titleId))
        if (folder.exists()) return Export(false, reason = "a folder with that name is already there")
        // 预检先做，做不过就不建临时目录：玩家选的目录里一个东西也不留
        val carried = carriedFiles(pack)
        // 两个路径只差大小写：落到不区分大小写的卡上会合成一个（游戏会坏）——先说清楚，不拷
        val collisions = caseCollisions(carried.map { it.relativeTo(pack).path })
        if (collisions.isNotEmpty()) return Export(false, reason = "two files differ only in their case: ${collisions.joinToString(", ")}")
        // FAT32 的卡放不下超过 4 GB 的单个文件：游戏资源装在一个文件里，那一个文件就超了
        val gameBytes = measure(gameFiles)
        val limit = singleFileLimit
        if (limit != null && gameBytes > limit) {
            return Export(false, reason = "this card cannot hold a file of $gameBytes bytes (a FAT32 card stops at 4 GB): use an exFAT card")
        }
        val tmp = File(parent, folder.name + ".partial")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return Export(false, reason = "the folder cannot be created")
        try {
            val total = carried.sumOf { it.length() } + gameBytes + (profile?.let { measure(it) } ?: 0L)
            var written = 0L
            var files = 0
            for (file in carried) {
                val out = File(tmp, file.relativeTo(pack).path)
                out.parentFile?.mkdirs()
                file.copyTo(out, overwrite = true)
                written += out.length()
                files++
                onProgress(written, total)
            }
            if (profile != null && profile.isDirectory) {
                copyTree(profile, File(tmp, "$PROFILE_DIR/$titleId"))
                written += measure(profile)
                onProgress(written, total)
            }
            val image = File(tmp, RomfsImage.FILE_NAME)
            val written0 = RomfsImage.write(gameFiles, image) { done, all ->
                onProgress(written + done, total)
            }
            if (written0 is RomfsImage.Written.Failed) {
                tmp.deleteRecursively()
                return Export(false, reason = written0.reason)
            }
            val imageOk = written0 as RomfsImage.Written.Ok
            written += imageOk.size
            files++
            // the image is read back and checked before the pack is put in place: a broken one never lands
            RomfsImage.verify(image)?.let {
                tmp.deleteRecursively()
                return Export(false, reason = it)
            }
            File(tmp, MANIFEST).writeText(manifestFor(pack, imageOk.size, imageOk.sha256), Charsets.UTF_8)
            File(tmp, ENV).writeText(envFor(pack, folder), Charsets.UTF_8)
            onProgress(written, total)
            if (folder.exists()) folder.deleteRecursively()
            if (!tmp.renameTo(folder)) {
                tmp.deleteRecursively()
                return Export(false, reason = "the folder cannot be put in place")
            }
            return Export(true, folder = folder, files = files, bytes = written)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return Export(false, reason = e.message ?: e.javaClass.simpleName)
        }
    }

    /** A tree's size (a stat walk). */
    fun measure(dir: File): Long {
        var bytes = 0L
        val stack = java.util.ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (child in d.listFiles() ?: emptyArray()) {
                if (child.isDirectory) stack.addLast(child) else bytes += child.length()
            }
        }
        return bytes
    }

    /** Copy a tree into a folder. */
    fun copyTree(src: File, dst: File) {
        dst.mkdirs()
        for (child in src.listFiles() ?: emptyArray()) {
            val out = File(dst, child.name)
            if (child.isDirectory) copyTree(child, out) else child.copyTo(out, overwrite = true)
        }
    }

    /** 这个包能不能导出成可携带包（不能时说清楚为什么）。 */
    fun exportable(pack: File, romfsAt: String): Exportable = when {
        // 包的 env.txt 里有这一行：游戏的文件是只给本 App 读的压缩格式
        envLines(pack).any { it.startsWith("DM_ROMFS_ZSTD=") } -> Exportable.COMPRESSED
        // 包里已经带着游戏的文件：它本身就是可携带的，拷走就行
        romfsAt.isEmpty() -> Exportable.ALREADY_PORTABLE
        else -> Exportable.YES
    }

    /** 一个包能不能导出成可携带包。 */
    enum class Exportable { YES, ALREADY_PORTABLE, COMPRESSED }

    /** The relative paths that collide on a volume that does not tell big letters from small ones. */
    fun caseCollisions(paths: List<String>): List<String> {
        val seen = HashMap<String, String>()
        val out = ArrayList<String>()
        for (p in paths) {
            val key = p.lowercase()
            val other = seen[key]
            if (other != null) {
                if (!out.contains(other)) out += other
                if (!out.contains(p)) out += p
            } else seen[key] = p
        }
        return out
    }

    /** The biggest file the volume can hold (a FAT32 card stops at 4 GB), null when it is not known. */
    fun singleFileLimit(path: File): Long? = singleFileLimitFor(StorageVolumes.fsTypeOf(path))

    /**
     * The biggest file a volume of that kind can hold: a FAT32 card stops at 4 GB (0xffffffff bytes),
     * every other kind we meet (exFAT, ext4, f2fs) holds more.
     *
     * The rule is separate from the volume lookup so it can be checked on its own.
     */
    fun singleFileLimitFor(fsType: String?): Long? = if (fsType == "vfat") 0xFFFFFFFFL else null

    /** What an imported pack is for the library: its folder is its identity. */
    fun identity(path: File): String = path.absolutePath
}

/**
 * Where a game's files and its profile are, for the export: the game's files are in the build workspace's
 * extract (or in the pack itself, in the old format), and its profile is in the workspace's copy first (the
 * one the build used) and in the app's own tree after that.
 */
object PortablePackSources {

    /** The game's files: the build workspace's extract, or the pack's own RomFS folder. */
    fun gameFiles(pack: File, romfsAt: String): File? {
        if (romfsAt.isNotEmpty()) {
            val f = File(romfsAt)
            if (f.isDirectory) return f
        }
        return File(pack, "RomFS").takeIf { it.isDirectory }
    }

    /** The profile the host is built from (the title ID's folder), null when there is none to be found. */
    fun profile(workRoot: File, gamesDir: File, titleId: String): File? {
        val upper = titleId.uppercase()
        val build = File(workRoot, "${upper}_build")
        for (name in listOf("profile-mem", "profile-derived", "profile")) {
            for (id in listOf(titleId, upper)) {
                val dir = File(build, "$name/$id")
                if (File(dir, "game.tsv").isFile && File(dir, "android.tsv").isFile) return dir
            }
        }
        val shipped = profileFile(gamesDir, titleId)?.parentFile ?: return null
        return shipped.takeIf { File(it, "game.tsv").isFile && File(it, "android.tsv").isFile }
    }
}

/** The card an imported pack is: its folder, its name and where it came from. */
data class PortablePackCard(val folder: File, val titleId: String, val name: String)
