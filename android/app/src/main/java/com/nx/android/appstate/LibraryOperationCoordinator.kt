package com.nx.android.appstate

import com.nx.android.data.library.GameCleanupFiles
import com.nx.android.data.library.LibraryFileScanner
import com.nx.android.data.library.PackMoverFiles
import com.nx.android.data.library.PortablePack
import com.nx.android.data.library.PortablePackSources
import com.nx.android.domain.GameCleanup
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameConfig
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.PackMover
import com.nx.android.domain.library.GameLibraryRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes destructive library actions and rechecks task/runtime guards at the IO boundary. */
@Singleton
class LibraryOperationCoordinator @Inject constructor(
    private val port: PortState,
    private val player: PlayerState,
    private val runtime: RuntimeUpdateState,
    private val repository: GameLibraryRepository,
    private val paths: com.nx.android.core.storage.AppPaths,
) {
    private val moving = HashSet<String>()

    fun beginBuild(game: String, config: GameConfig): Boolean = synchronized(moving) {
        if (moving.isNotEmpty() || port.running || player.running || runtime.running) return@synchronized false
        port.begin(game, config)
        true
    }

    fun beginRuntimeUpdate(pack: String, name: String): Boolean = synchronized(moving) {
        if (moving.isNotEmpty() || port.running || player.running || runtime.running) return@synchronized false
        runtime.begin(pack, name)
    }

    fun beginPlay(titleId: String, name: String): Boolean = synchronized(moving) {
        if (moving.isNotEmpty() || port.running || player.running || runtime.running) return@synchronized false
        player.start(titleId, name)
        true
    }

    fun remove(entry: GameEntry): GameCleanup.Result = synchronized(moving) {
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val plan = planRemoval(entry)
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val unreachable = repository.unreachablePacks(entry)
        val deleted = GameCleanupFiles.delete(plan)
        val clean = deleted.failed.isEmpty() && unreachable.isEmpty()
        if (clean) repository.forget(entry.titleId)
        GameCleanup.Result(
            outcome = if (clean) GameCleanup.Outcome.DONE else GameCleanup.Outcome.FAILED,
            workspaceKnown = plan.workspaceKnown,
            failed = deleted.failed + unreachable,
        )
    }

    fun clearCache(entry: GameEntry): GameCleanup.Result = synchronized(moving) {
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val plan = planCache(entry)
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        // 数据包读的是工作区里的 RomFS：单独删工作区，游戏就跑不起来了（要删就整个删）
        if (plan.workspaceInUse) return@synchronized GameCleanup.Result(GameCleanup.Outcome.WORKSPACE_IN_USE)
        val deleted = GameCleanupFiles.delete(plan)
        GameCleanup.Result(
            outcome = if (deleted.failed.isEmpty()) GameCleanup.Outcome.DONE else GameCleanup.Outcome.FAILED,
            workspaceKnown = plan.workspaceKnown,
            failed = deleted.failed,
        )
    }

    fun movePack(entry: GameEntry, targetDir: File, onProgress: (PackMover.Progress) -> Unit): PackMover.Result {
        moveBlocked(entry)?.let { return PackMover.Result.fail(it.name.lowercase()) }
        val pack = entry.pack?.pack ?: return PackMover.Result.fail("the game has no pack")
        if (!synchronized(moving) { moving.add(entry.titleId) }) return PackMover.Result.fail("the pack is being moved already")
        try {
            moveBlocked(entry, includeMoving = false)?.let { return PackMover.Result.fail(it.name.lowercase()) }
            val result = PackMoverFiles.move(pack, targetDir, onProgress)
            if (result.ok) repository.rememberPackPath(entry.titleId, File(targetDir, pack.name).absolutePath)
            return result
        } finally {
            synchronized(moving) { moving.remove(entry.titleId) }
        }
    }

    fun removalBlocked(entry: GameEntry): GameCleanup.Outcome? = synchronized(moving) { removalBlockedLocked(entry) }

    fun moveBlocked(entry: GameEntry): GameCleanup.Outcome? = moveBlocked(entry, includeMoving = true)

    /**
     * 导出一个可携带包：把游戏做成一个能带走的文件夹（包的文件 + 游戏的文件装在一个容器里 + 配方）。
     *
     * 源游戏一个字节都不动；落点先写临时名，写完才改名。
     */
    fun exportPack(entry: GameEntry, parent: File, onProgress: (Long, Long) -> Unit): PortablePack.Export {
        moveBlocked(entry)?.let { return PortablePack.Export(false, reason = it.name.lowercase()) }
        val ported = entry.pack ?: return PortablePack.Export(false, reason = "the game has no pack")
        if (!synchronized(moving) { moving.add(entry.key) }) return PortablePack.Export(false, reason = "the pack is being moved already")
        try {
            moveBlocked(entry, includeMoving = false)?.let { return PortablePack.Export(false, reason = it.name.lowercase()) }
            val files = PortablePackSources.gameFiles(ported.pack, ported.romfsAt)
                ?: return PortablePack.Export(false, reason = "the game's files are not there")
            val profile = PortablePackSources.profile(repository.workRoot(), File(paths.tool, "nxnative/dm/games"), entry.titleId)
            return PortablePack.export(ported.pack, files, profile, entry.titleId, entry.label, parent, onProgress)
        } finally {
            synchronized(moving) { moving.remove(entry.key) }
        }
    }

    /** 添加一个别人导出的包：只记它的路径，玩家的文件一个都不动 */
    fun addPack(folder: File): Boolean = synchronized(moving) {
        if (!PortablePack.isPackFolder(folder)) return@synchronized false
        repository.addPackPath(folder)
    }

    /** 移除一个导入的包：只忘掉路径，那个文件夹和里面的东西都留着 */
    fun forgetPack(folder: File) = synchronized(moving) { repository.forgetPackPath(folder) }

    fun planRemoval(entry: GameEntry): GameCleanup.Plan {
        val packs = repository.packsOf(entry)
        val workspace = entry.work ?: repository.existingWorkspace(entry.titleId)
        val partials = if (port.running) emptyList() else repository.partialPacks()
        return GameCleanup.plan(entry.titleId, packs, workspace, partials)
    }

    fun planCache(entry: GameEntry): GameCleanup.Plan {
        val workspace = entry.work ?: repository.existingWorkspace(entry.titleId)
        val partials = if (port.running) emptyList() else repository.partialPacks()
        return GameCleanup.plan(entry.titleId, emptyList(), workspace, partials, workspaceInUse = workspaceInUse(entry, workspace))
    }

    /**
     * 数据包读的是工作区里的 RomFS（移植时没有复制一份进包，省一份游戏的体积）：
     * 那个文件夹是游戏的一部分，不能单独删。
     */
    private fun workspaceInUse(entry: GameEntry, workspace: File?): Boolean {
        if (workspace == null) return false
        return repository.packsOf(entry).any { pack ->
            val at = LibraryFileScanner.portFromPack(pack)?.romfsAt.orEmpty()
            GameCleanup.packReadsWorkspace(at, workspace)
        }
    }

    private fun removalBlockedLocked(entry: GameEntry): GameCleanup.Outcome? {
        if (runtime.running) return GameCleanup.Outcome.BUSY_PORTING
        if (entry.titleId in moving) return GameCleanup.Outcome.BUSY_MOVING
        return LibraryLogic.removalBlocked(
            portRunning = port.running, portGameFile = port.gameFile, gamePath = entry.sourcePath,
            playerRunning = player.running, playerTitleId = player.titleId, titleId = entry.titleId, packPath = entry.pack?.pack?.absolutePath,
        )
    }

    private fun moveBlocked(entry: GameEntry, includeMoving: Boolean): GameCleanup.Outcome? = synchronized(moving) {
        if (runtime.running) return@synchronized GameCleanup.Outcome.BUSY_PORTING
        LibraryLogic.moveBlocked(
            portRunning = port.running, portGameFile = port.gameFile, gamePath = entry.sourcePath,
            playerRunning = player.running, playerTitleId = player.titleId, titleId = entry.titleId, packPath = entry.pack?.pack?.absolutePath,
            moving = includeMoving && entry.titleId in moving,
        )
    }
}
