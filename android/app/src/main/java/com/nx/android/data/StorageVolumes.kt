package com.nx.android.data

import android.content.Context
import android.os.Environment
import com.nx.android.R
import java.io.File

/**
 * 可浏览的存储卷：内部存储在前，SD 卡卷按 `/storage` 下的卷 ID 在后。
 *
 * 枚举 SD 卡卷的通用手法：`getExternalFilesDirs(null)` 的第一个元素是内部卷
 * （`/storage/emulated/0/Android/data/<pkg>/files`），其余元素裁掉 `/Android/data/<pkg>/files`
 * 前缀就是各卷的根。全文件访问在手，java.io 直读畅通。
 */
object StorageVolumes {

    /**
     * 可移动卷（SD 卡）上 App 自己的端口目录：卷名 → 那个目录
     * （`<卡>/Android/data/<包名>/files/ports`，和私有存储里的那个同构：端口目录下面才是数据包）。
     *
     * 枚举 SD 卡卷的通用手法：`getExternalFilesDirs(null)` 的第一个元素是内部卷
     * （`/storage/emulated/0/Android/data/<pkg>/files`），其余元素裁掉 `/Android/data/<pkg>/files`
     * 前缀就是各卷的根。全文件访问在手，java.io 直读畅通。
     */
    fun removablePortsDirs(ctx: Context): List<Pair<String, File>> {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val out = LinkedHashMap<String, File>()
        for (dir in ctx.getExternalFilesDirs(null)) {
            val path = dir?.path ?: continue
            if (!path.startsWith("/storage/")) continue
            val root = path.substringBefore("/Android/data")
            if (root == primary) continue          // 内置存储：包放在那儿是 FUSE，比私有目录慢，不作为落点
            if (!File(root).isDirectory) continue  // 卡不在了
            out[root.substringAfterLast('/')] = File(dir, "ports")
        }
        return out.toList()
    }

    /** 卷名 → 卷根目录（内部存储在前） */
    fun list(ctx: Context, locale: AppLocale): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val internal = Environment.getExternalStorageDirectory()
        if (internal.isDirectory) out += locale.str(R.string.storage_internal) to internal
        for (dir in ctx.getExternalFilesDirs(null)) {
            val path = dir?.path ?: continue
            if (!path.startsWith("/storage/")) continue
            val root = path.substringBefore("/Android/data")
            if (root == internal.path || out.any { it.second.path == root }) continue
            if (File(root).isDirectory) out += locale.str(R.string.storage_sd_card, root.substringAfterLast('/')) to File(root)
        }
        return out
    }

    /**
     * The filesystem a path is on ("vfat" for a FAT32 card, "exfat" for an exFAT one).
     *
     * The card's own mount is under /mnt (the volume's ID is in its path), while /storage holds the
     * framework's own view of it — so the volume's ID is what tells the two apart.
     */
    fun fsTypeOf(path: File): String? {
        val mounts = runCatching { File("/proc/mounts").readLines() }.getOrNull() ?: return null
        val volume = volumeIdOf(path)
        var best: String? = null
        var bestLen = -1
        for (line in mounts) {
            val parts = line.split(' ')
            if (parts.size < 3) continue
            val point = parts[1]
            val type = parts[2]
            val matches = if (volume != null) point.contains(volume)
            else path.absolutePath == point || path.absolutePath.startsWith("$point/")
            if (matches && point.length > bestLen) { bestLen = point.length; best = type }
        }
        return best
    }

    /** The volume's ID ("1234-5678" for /storage/1234-5678/...), null for the internal storage. */
    fun volumeIdOf(path: File): String? {
        val absolute = path.absolutePath
        if (!absolute.startsWith("/storage/")) return null
        val rest = absolute.removePrefix("/storage/")
        val id = rest.substringBefore('/')
        return id.takeIf { it.isNotEmpty() && it.contains('-') }
    }
}
