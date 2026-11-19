package com.nx.android.game

import org.json.JSONArray

/**
 * 一条金手指（宿主那边的一段程序，改写游戏内存里的一个数值）。
 *
 * 名字来自社区那份 Atmosphère 格式的文件；why 是它不能跑的原因（要改游戏的代码之类），空着就是能跑。
 */
data class Cheat(val name: String, val on: Boolean, val why: String) {
    /** 它不能跑（这一版只写数据，不改游戏的代码） */
    val broken: Boolean get() = why.isNotEmpty()
}

/** 宿主给的金手指列表（JSON 数组）：每条 {name, on, why} */
fun parseCheats(json: String): List<Cheat> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Cheat(o.optString("name"), o.optBoolean("on"), o.optString("why"))
        }
    }.getOrDefault(emptyList())
}

/** 从社区库下载到哪一步了 */
enum class CheatDownload {
    NONE,        // 还没试过
    DOWNLOADING, // 正在下
    DONE,        // 下好了
    NO_BUILD,    // 社区库里没有这个 build 的文件
    NO_USABLE,   // 库里有这个 build 的文件，但里面能用的没有（都是分辨率类的）
    FAILED,      // 下载失败（网络）
}

/** 金手指页的状态：App 那边这个游戏有什么 */
data class CheatStatus(
    /** 文件已经在手机上了（下载来的或导入的） */
    val downloaded: Boolean = false,
    /** 下载到哪一步了 */
    val download: CheatDownload = CheatDownload.NONE,
    /** 出处：社区库自己写的 */
    val credit: String = "",
    /** 能不能下载：得知道这个游戏的 title ID 与它这个 build 的编号 */
    val canDownload: Boolean = false,
)
