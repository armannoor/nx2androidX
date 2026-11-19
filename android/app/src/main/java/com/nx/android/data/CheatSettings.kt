package com.nx.android.data

/**
 * 金手指的开关（纯逻辑，JVM 可测）：总开关与启动时给宿主的那一行。
 *
 * 每条金手指自己的开关不由 App 记：宿主把它记在下载来的文件旁边（cheats/<build ID>.enabled，那边才知道这个
 * build 的编号与条目名），App 只管“玩家要不要用金手指”这一件事——关着的时候宿主不加载也不执行任何金手指。
 */
object CheatKnobs {

    /**
     * 启动时给宿主的那一行：金手指的目录（App 自己的 cheats/，下载来的文件在那里），总开关开着才有这一行。
     * 目录为空（还没下载过）时没有这一行，宿主什么也不加载。
     */
    fun envLine(master: Boolean, cheatsDir: String?): Array<String> =
        if (master && !cheatsDir.isNullOrEmpty()) arrayOf("DM_CHEATS=$cheatsDir") else emptyArray()
}

/**
 * 一个游戏的金手指开关：prefs 壳（按 Title ID 的键），读写总开关。
 */
class CheatSettings(private val prefs: PrefsStore, private val titleId: String) {

    private val base = "cheats.${titleId.lowercase()}"

    /** 总开关：默认关（玩家自己打开才生效） */
    fun master(): Boolean = prefs.bool("$base.on")

    fun setMaster(on: Boolean) {
        prefs.put("$base.on", on)
    }

    fun envLine(cheatsDir: String?): Array<String> = CheatKnobs.envLine(master(), cheatsDir)
}
