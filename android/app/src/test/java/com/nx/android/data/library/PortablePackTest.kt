package com.nx.android.data.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The portable pack: a finished port as one folder that another phone can run.
 */
class PortablePackTest {

    private val titleId = "0100B8A026F14000"

    private fun fakePack(root: File): File {
        val pack = File(root, "pack").apply { mkdirs() }
        File(pack, "nx2a-data.json").writeText(
            "{\"title_id\":\"$titleId\",\"name\":\"FINAL FANTASY\",\"host\":\".runtime-abc/libdmplay.so\",\"profile_h\":\"abc\"," +
                "\"romfs_at\":\"${root.absolutePath}/work/0100B8A026F14000_build/extract/v65536/RomFS\"}"
        )
        File(pack, "env.txt").writeText(
            "# written by the porter\nDM_ROMFS=${root.absolutePath}/work/0100B8A026F14000_build/extract/v65536/RomFS\n" +
                "DM_AOC_DIR=${pack.absolutePath}/aoc\nDM_LANG=zh-Hans\n"
        )
        File(pack, ".runtime-abc").mkdirs()
        File(pack, ".runtime-abc/libdmplay.so").writeBytes(byteArrayOf(1, 2, 3))
        File(pack, "aoc").mkdirs()
        File(pack, "aoc/2").writeText("dlc")
        File(pack, "mods").mkdirs()
        File(pack, "mods/m.so").writeBytes(byteArrayOf(4, 5))
        File(pack, "log.txt").writeText("the host's log")
        File(pack, "vk_pipelines.bin").writeBytes(byteArrayOf(6))
        return pack
    }

    @Test
    fun `the export holds the pack's files, the game's files in one container, the profile and no host`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = fakePack(root)
            val gameFiles = File(root, "extract/RomFS").apply {
                mkdirs()
                File(this, "x.bin").writeBytes(byteArrayOf(9))
            }
            val profile = File(root, "profile/$titleId").apply {
                mkdirs()
                File(this, "game.tsv").writeText("game")
                File(this, "android.tsv").writeText("android")
            }
            val target = File(root, "out").apply { mkdirs() }

            val result = PortablePack.export(pack, gameFiles, profile, titleId, "FINAL FANTASY", target)
            assertTrue(result.reason, result.ok)
            val folder = result.folder!!
            assertEquals("FINAL FANTASY", folder.name)

            // the host is not carried over: the receiving phone builds its own
            assertFalse(File(folder, ".runtime-abc").exists())
            // the device's own leftovers are not carried over either
            assertFalse(File(folder, "log.txt").exists())
            assertFalse(File(folder, "vk_pipelines.bin").exists())
            // the pack's own files are
            assertTrue(File(folder, "mods/m.so").isFile)
            assertTrue(File(folder, "aoc/2").isFile)

            // the manifest has no host and keeps the rest
            val manifest = JSONObject(File(folder, "nx2a-data.json").readText())
            assertFalse(manifest.has("host"))
            // and no pointer to the game's files: they are in the pack's own image, and that pointer
            // names a folder on the phone that exported it — it means nothing on the receiving one
            assertFalse(manifest.has("romfs_at"))
            assertEquals("FINAL FANTASY", manifest.getString("name"))
            assertEquals("abc", manifest.getString("profile_h"))

            // the env has no row for the game's files and the pack's own rows point at the new place
            val env = File(folder, "env.txt").readText()
            assertFalse(env.contains("DM_ROMFS="))
            assertTrue(env.contains("DM_AOC_DIR=${folder.absolutePath}/aoc"))
            assertTrue(env.contains("DM_LANG=zh-Hans"))

            // the game's files are in one RomFS image
            assertTrue(File(folder, "romfs.bin").isFile)
            // the manifest says how big it is and what it hashes to, so the receiving phone can check it
            val image = File(folder, "romfs.bin")
            assertEquals(image.length(), manifest.getLong("romfs_size"))
            assertEquals(64, manifest.getString("romfs_sha256").length)
            assertNull(PortablePack.checkImage(folder))
            // the profile is in it
            assertTrue(File(folder, "profile/$titleId/game.tsv").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an export that fails leaves nothing behind`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = fakePack(root)
            val target = File(root, "out").apply { mkdirs() }
            // the game's files are not there: the export cannot work
            val result = PortablePack.export(pack, File(root, "nope"), null, titleId, "FINAL FANTASY", target)
            assertFalse(result.ok)
            assertEquals(0, target.listFiles()?.size ?: 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a pack that already carries the game's files cannot be exported`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = fakePack(root)
            assertEquals(PortablePack.Exportable.ALREADY_PORTABLE, PortablePack.exportable(pack, ""))
            assertEquals(PortablePack.Exportable.YES, PortablePack.exportable(pack, "/somewhere/RomFS"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a compressed game cannot be exported`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = fakePack(root)
            File(pack, "env.txt").writeText("DM_ROMFS_ZSTD=1\nDM_ROMFS=/somewhere/RomFS\n")
            assertEquals(PortablePack.Exportable.COMPRESSED, PortablePack.exportable(pack, "/somewhere/RomFS"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the added paths survive being remembered`() {
        val paths = listOf("/storage/AAAA-1111/game one", "/storage/BBBB-2222/game two")
        assertEquals(paths, PortablePack.AddedPaths.decode(PortablePack.AddedPaths.encode(paths)))
        assertEquals(emptyList<String>(), PortablePack.AddedPaths.decode("not json"))
    }

    @Test
    fun `the folder name holds what a file name can hold`() {
        assertEquals("FINAL FANTASY", PortablePack.folderName("FINAL FANTASY", titleId))
        assertEquals("a_b_c", PortablePack.folderName("a/b:c", titleId))
        assertEquals(titleId, PortablePack.folderName("  ", titleId))
    }

    @Test
    fun `paths that differ only in their case are caught`() {
        assertEquals(listOf("Mods/Main.so", "mods/main.so"),
            PortablePack.caseCollisions(listOf("Mods/Main.so", "mods/main.so")))
        assertEquals(listOf("a/b.bin", "A/B.bin"),
            PortablePack.caseCollisions(listOf("a/b.bin", "A/B.bin")))
        assertEquals(emptyList<String>(), PortablePack.caseCollisions(listOf("a/b.bin", "a/c.bin")))
    }

    @Test
    fun `a folder with a manifest in it is a pack`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val dir = File(root, "game").apply { mkdirs() }
            assertFalse(PortablePack.isPackFolder(dir))
            File(dir, "nx2a-data.json").writeText("{}")
            assertTrue(PortablePack.isPackFolder(dir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a card that cannot hold the image refuses the export and says why`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = fakePack(root)
            val gameFiles = File(root, "extract/RomFS").apply {
                mkdirs()
                File(this, "x.bin").writeBytes(ByteArray(1024))
            }
            val target = File(root, "out").apply { mkdirs() }

            // a FAT32 card stops at 4 GB: an image bigger than that does not fit
            assertEquals(0xFFFFFFFFL, PortablePack.singleFileLimitFor("vfat"))
            // the other kinds we meet hold more
            assertNull(PortablePack.singleFileLimitFor("exfat"))
            assertNull(PortablePack.singleFileLimitFor("fuse"))
            assertNull(PortablePack.singleFileLimitFor(null))

            val result = PortablePack.export(pack, gameFiles, null, titleId, "FINAL FANTASY", target,
                singleFileLimit = 512L)
            assertFalse(result.ok)
            assertTrue(result.reason, result.reason.contains("FAT32"))
            assertEquals(0, target.listFiles()?.size ?: 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the export carries no host at all`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = fakePack(root)
            // a pack the app made can still have the host at the old place (before the runtime update moved
            // it into a .runtime- folder): a host that came with the pack was built for the exporting
            // phone's toolchain, so it must not travel
            File(pack, "host").mkdirs()
            File(pack, "host/libdmplay.so").writeBytes(byteArrayOf(7, 7, 7))
            val gameFiles = File(root, "extract/RomFS").apply {
                mkdirs()
                File(this, "x.bin").writeBytes(byteArrayOf(9))
            }
            val target = File(root, "out").apply { mkdirs() }

            val result = PortablePack.export(pack, gameFiles, null, titleId, "FINAL FANTASY", target)
            assertTrue(result.reason, result.ok)
            val folder = result.folder!!
            // neither the host the manifest names nor the one at the old place is carried over
            assertFalse(File(folder, ".runtime-abc/libdmplay.so").exists())
            assertFalse(File(folder, "host").exists())
            // and the manifest names no host, so the receiving phone builds its own
            assertFalse(JSONObject(File(folder, "nx2a-data.json").readText()).has("host"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an imported pack does not run a host that came with it`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = File(root, "pack").apply { mkdirs() }
            File(pack, "nx2a-data.json").writeText("{\"title_id\":\"$titleId\",\"name\":\"FINAL FANTASY\"}")
            // a host at the old place, from the phone that exported the pack
            File(pack, "host").mkdirs()
            File(pack, "host/libdmplay.so").writeBytes(byteArrayOf(7))
            // the game's files in one image: this is a portable pack
            File(pack, "romfs.bin").writeBytes(byteArrayOf(1, 2, 3))

            // the pack carries the image: it is a portable pack, so the host at the old place (which came
            // with it, built for the other phone) is not used
            val portable = LibraryFileScanner.portFromPack(pack)
            assertNotNull(portable)
            assertFalse(portable!!.hasHost)
            assertFalse(portable.runnable)

            // the manifest names one (the receiving phone built it): that one is used
            File(pack, "nx2a-data.json").writeText(
                "{\"title_id\":\"$titleId\",\"name\":\"FINAL FANTASY\",\"host\":\".runtime-abc/libdmplay.so\"}"
            )
            File(pack, ".runtime-abc").mkdirs()
            File(pack, ".runtime-abc/libdmplay.so").writeBytes(byteArrayOf(8))
            val built = LibraryFileScanner.portFromPack(pack)
            assertNotNull(built)
            assertTrue(built!!.hasHost)
            assertEquals("libdmplay.so", built.host.name)

            // a pack the app made (no image): the host at the old place is the app's own and is used
            val own = File(root, "own").apply { mkdirs() }
            File(own, "nx2a-data.json").writeText("{\"title_id\":\"$titleId\",\"name\":\"FINAL FANTASY\"}")
            File(own, "host").mkdirs()
            File(own, "host/libdmplay.so").writeBytes(byteArrayOf(7))
            val ownPorted = LibraryFileScanner.portFromPack(own)
            assertNotNull(ownPorted)
            assertTrue(ownPorted!!.hasHost)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an imported card is its own card`() {
        val own = com.nx.android.domain.GameEntry("0100B8A026F14000", null, null, null)
        val imported = com.nx.android.domain.GameEntry("0100B8A026F14000", null, null, null,
            importedPath = "/storage/ABCD-1234/game")
        assertEquals("0100B8A026F14000", own.key)
        assertFalse(own.imported)
        assertEquals("/storage/ABCD-1234/game", imported.key)
        assertTrue(imported.imported)
        // the two cards live side by side: the same title ID, two identities
        assertEquals("game", imported.label)
    }

    @Test
    fun `a pack that carries its own image does not need the pointer to the game's files`() {
        val root = Files.createTempDirectory("portable").toFile()
        try {
            val pack = File(root, "pack").apply { mkdirs() }
            val image = File(pack, "romfs.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val host = File(pack, ".runtime-abc/libdmplay.so").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(4))
            }
            // the pointer names a folder on the phone that exported the pack: it is not there here
            val stale = "/data/user/0/com.nx.android/files/work/0100B8A026F14000_build/extract/v65536/RomFS"
            val ported = com.nx.android.domain.Ported(
                pack = pack, host = host, icon = null, titleId = titleId,
                playerApiRevision = com.nx.android.domain.PlayerApiRevision.CURRENT,
                romfsAt = stale, romfsContainer = image,
            )
            // the image is there: the game can run, the stale pointer is of no account
            assertFalse(ported.romfsMissing)
            assertTrue(ported.runnable)

            // the image gone: the game cannot run
            val withoutImage = ported.copy(romfsContainer = File(pack, "nope.bin"))
            assertTrue(withoutImage.romfsMissing)
            assertFalse(withoutImage.runnable)

            // an own pack (no image): the pointer is the one that counts
            val own = ported.copy(romfsContainer = null)
            assertTrue(own.romfsMissing)
            val ownOk = own.copy(romfsAt = root.absolutePath)
            assertFalse(ownOk.romfsMissing)
        } finally {
            root.deleteRecursively()
        }
    }
}
