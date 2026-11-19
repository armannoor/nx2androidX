package com.nx.android.data

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 金手指文件：两条路都验。
 *
 * 下载：从社区库（switch-cheats-db）按 build 编号挑出来，拼成 Atmosphère 格式的文本；第二个库（MyNXCheats）
 * 的文件树与路径；分辨率类的过滤；下载的几种结局。
 * 导入：玩家自己挑的 zip（社区的几种布局）解到 App 的目录里。
 */
class CheatRepoTest {

    /** The servers the tests started (they are stopped when the test is over) */
    private val servers = ArrayList<HttpServer>()

    /** How many folders the test made (their names must not collide) */
    private var counter = 0

    @After
    fun stopServers() {
        for (s in servers) s.stop(0)
        servers.clear()
    }

    /** The library's file of one game, in the shape it has: the builds it knows, each with its cheats, and the credit */
    private val library = """
        {
          "5A797A3A5DA6C4F3": {
            "[Old Build]": "[Old Build]\n04000000 003BE784 94378D5F\n\n"
          },
          "DF3766A2BB651A3E": {
            "[Inf HP]": "[Inf HP]\n04000000 011A1D00 52800108\n04000000 011A1D04 3909DC08\n\n",
            "[Human 99]": "[Human 99]\n04000000 004C095C 52800C69\n\n",
            "[Aura]": "[Aura]\n04000000 0024F7CC 1E2E1008\n\n"
          },
          "attribution": {
            "DARK SOULS REMASTERED.txt": "By masagrator\nhttps://gbatemp.net/threads/dark-souls-60-fps-mod.557760/\n"
          }
        }
    """.trimIndent()

    @Test
    fun `the build's cheats become one Atmosphere file`() {
        assertEquals(
            "[Inf HP]\n04000000 011A1D00 52800108\n04000000 011A1D04 3909DC08\n\n" +
                "[Human 99]\n04000000 004C095C 52800C69\n\n" +
                "[Aura]\n04000000 0024F7CC 1E2E1008\n\n",
            CheatRepo.entriesOf(library, "DF3766A2BB651A3E")!!.joinToString("") { it.text },
        )
    }

    @Test
    fun `the cheats keep the order the library lists them in`() {
        val text = CheatRepo.entriesOf(library, "DF3766A2BB651A3E")!!.joinToString("") { it.text }
        assertTrue("Inf HP comes first", text.indexOf("[Inf HP]") < text.indexOf("[Human 99]"))
        assertTrue("Aura comes last", text.indexOf("[Human 99]") < text.indexOf("[Aura]"))
    }

    @Test
    fun `the build is found in either case`() {
        assertEquals(
            CheatRepo.entriesOf(library, "DF3766A2BB651A3E")!!.size,
            CheatRepo.entriesOf(library, "df3766a2bb651a3e")!!.size,
        )
    }

    @Test
    fun `a build the library does not have gives nothing`() {
        assertNull(CheatRepo.entriesOf(library, "040AD650120B583A"))
    }

    @Test
    fun `the credit is not a build`() {
        assertNull(CheatRepo.entriesOf(library, "attribution"))
        assertNull(CheatRepo.entriesOf(library, "ATTRIBUTION"))
    }

    @Test
    fun `the credit is the first line of the library's note`() {
        assertEquals("By masagrator", CheatRepo.credit(library))
    }

    @Test
    fun `a file without a credit gives none`() {
        val noCredit = """{"DF3766A2BB651A3E": {"[Inf HP]": "[Inf HP]\n04000000 011A1D00 52800108\n\n"}}"""
        assertEquals("", CheatRepo.credit(noCredit))
    }

    @Test
    fun `the escapes of the library's text are read`() {
        val text = """{"DF3766A2BB651A3E": {"[A]": "[A]\n04000000 004C095C \"quoted\" \\ back \u00e9\n\n"}}"""
        assertEquals(
            "[A]\n04000000 004C095C \"quoted\" \\ back \u00e9\n\n",
            CheatRepo.entriesOf(text, "DF3766A2BB651A3E")!!.joinToString("") { it.text },
        )
    }

    @Test
    fun `a broken file gives nothing`() {
        assertNull(CheatRepo.entriesOf("not json at all", "DF3766A2BB651A3E"))
        assertNull(CheatRepo.entriesOf("""{"DF3766A2BB651A3E": }""", "DF3766A2BB651A3E"))
    }

    @Test
    fun `the toggles of the cheats are cleared when the game starts again`() {
        val dir = folder()
        val toggles = File(dir, "DF3766A2BB651A3E.enabled")
        toggles.writeText("Inf HP\n")
        assertTrue(CheatRepo.clearToggles(dir, "df3766a2bb651a3e"))
        assertFalse(toggles.exists())
        // 没有开关文件时没什么可清的
        assertFalse(CheatRepo.clearToggles(dir, "df3766a2bb651a3e"))
    }

    @Test
    fun `the build id comes from the pack's rewriter table`() {
        val pack = folder()
        val mods = File(pack, "mods")
        mods.mkdirs()
        File(mods, "main.fixups.tsv").writeText(
            "# BUILD_ID\tdf3766a2bb651a3e822828abf10b6f7b\n" +
                "# PATCH_OFF\t-0x2000000\n" +
                "0x277d838\t280080d2\t080080d2\tdisplay settings\n"
        )
        assertEquals("DF3766A2BB651A3E", CheatRepo.buildIdOf(pack))
    }

    @Test
    fun `a pack without that table gives no build id`() {
        assertNull(CheatRepo.buildIdOf(folder()))
    }

    @Test
    fun `the library's file of one game is fetched by its title id`() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/HamletDuFromage/switch-cheats-db@master/cheats/01004AB00A260000.json",
            CheatRepo.url("01004ab00a260000"),
        )
    }

    @Test
    fun `the file is asked of every edge of the CDN`() {
        val urls = CheatRepo.urls("01004ab00a260000")
        assertEquals(3, urls.size)
        assertEquals(
            "https://fastly.jsdelivr.net/gh/HamletDuFromage/switch-cheats-db@master/cheats/01004AB00A260000.json",
            urls[1],
        )
        for (u in urls) assertTrue(u.startsWith("https://"))
    }

    // ---- the sources, the filter and the download ----

    @Test
    fun `the resolution patches are skipped`() {
        assertTrue(CheatRepo.isResolution("[Downscale Handheld 504p / Docked 756p]"))
        assertTrue(CheatRepo.isResolution("[Remain Handheld 720p / Docked 1080p]"))
        assertTrue(CheatRepo.isResolution("[Downscale]"))
        assertTrue(CheatRepo.isResolution("[Resolution]"))
        assertFalse(CheatRepo.isResolution("[Inf HP]"))
        assertFalse(CheatRepo.isResolution("[60 FPS]"))
        assertFalse(CheatRepo.isResolution("[Max/Inf Money]"))
    }

    @Test
    fun `an Atmosphere file is read into its entries`() {
        val text = """
            Pokemon Legends Z-A v393216 TID=0100F43008C44000 BID=B1F12FD919EAE86A
            [Item/Misc]
            [1. Disable Autosave]
            04000000 002D4B40 00000001

            [2. Max Money]
            04000000 00321A00 F4FFFFFF
        """.trimIndent()
        val entries = CheatRepo.fileEntries(text)
        assertEquals(3, entries.size)
        assertEquals("Item/Misc", entries[0].name)
        assertEquals("1. Disable Autosave", entries[1].name)
        assertTrue("the code stays with its entry", entries[1].text.contains("002D4B40"))
        // 小节标题（没有代码）也在里面，但宿主的解析器把它当标签，不列给玩家
        assertTrue(entries[0].text.startsWith("[Item/Misc]"))
    }

    @Test
    fun `the first source with a usable file wins`() {
        val first = source("first", "Inf HP" to "04000000 011A1D00 52800108\n")
        val second = source("second", "Aura" to "04000000 0024F7CC 1E2E1008\n")
        val file = CheatRepo.fetchFile(listOf(first, second), "01004AB00A260000", "DF3766A2BB651A3E")
        assertEquals("first", file.source)
        assertTrue(file.text.contains("011A1D00"))
    }

    @Test
    fun `a source without the build gives way to the next one`() {
        val empty = object : CheatRepo.Source {
            override val label = "empty"
            override fun fileOf(titleId: String, build: String): CheatRepo.RawFile? = null
        }
        val second = source("second", "Aura" to "04000000 0024F7CC 1E2E1008\n")
        val file = CheatRepo.fetchFile(listOf(empty, second), "01004AB00A260000", "DF3766A2BB651A3E")
        assertEquals("second", file.source)
    }

    @Test
    fun `a source with only resolution patches gives way to the next one`() {
        val gfx = source("gfx", "Downscale Handheld 504p / Docked 756p" to "04000000 002D4B40 00000001\n")
        val second = source("second", "Aura" to "04000000 0024F7CC 1E2E1008\n")
        val file = CheatRepo.fetchFile(listOf(gfx, second), "01004AB00A260000", "DF3766A2BB651A3E")
        assertEquals("second", file.source)
        assertFalse("the resolution patch is not in the file", file.text.contains("002D4B40"))
    }

    @Test
    fun `a source with nothing usable says so`() {
        val gfx = source("gfx", "Downscale Handheld 504p / Docked 756p" to "04000000 002D4B40 00000001\n")
        val failure = runCatching { CheatRepo.fetchFile(listOf(gfx), "01004AB00A260000", "DF3766A2BB651A3E") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is CheatRepo.NothingUsable)
    }

    @Test
    fun `no source with the build says so`() {
        val empty = object : CheatRepo.Source {
            override val label = "empty"
            override fun fileOf(titleId: String, build: String): CheatRepo.RawFile? = null
        }
        val failure = runCatching { CheatRepo.fetchFile(listOf(empty), "01004AB00A260000", "DF3766A2BB651A3E") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is CheatRepo.NoSuchBuild)
    }

    @Test
    fun `a network that failed is a failure, not an empty library`() {
        val broken = object : CheatRepo.Source {
            override val label = "broken"
            override fun fileOf(titleId: String, build: String): CheatRepo.RawFile? =
                throw java.io.IOException("the network is down")
        }
        val failure = runCatching { CheatRepo.fetchFile(listOf(broken), "01004AB00A260000", "DF3766A2BB651A3E") }
        assertTrue(failure.isFailure)
        assertTrue("it is a network failure, not a missing file",
            failure.exceptionOrNull() is java.io.IOException)
        assertFalse(failure.exceptionOrNull() is CheatRepo.NoSuchBuild)
    }

    @Test
    fun `the second source's file is found in its tree`() {
        val tree = listOf(
            "/Animal Crossing New Horizons/01006F8002326000/cheats/0400F824B4DA556E.txt",
            "/Pokemon Legends Z-A/0100F43008C44000/cheats/179C3843B984F878.txt",
            "/Pokemon Legends Z-A/0100F43008C44000/cheats/B1F12FD919EAE86A.txt",
            "/Pokemon Legends Z-A/0100F43008C44000/cheats/notes.txt",
        )
        assertEquals(
            "/Pokemon Legends Z-A/0100F43008C44000/cheats/B1F12FD919EAE86A.txt",
            CheatRepo.mynxFilePath(tree, "0100F43008C44000", "B1F12FD919EAE86A"),
        )
        assertEquals(
            "/Pokemon Legends Z-A/0100F43008C44000/cheats/B1F12FD919EAE86A.txt",
            CheatRepo.mynxFilePath(tree, "0100f43008c44000", "b1f12fd919eae86a"),
        )
        assertNull(CheatRepo.mynxFilePath(tree, "0100F43008C44000", "8A1C86C437394B69"))
        assertNull(CheatRepo.mynxFilePath(tree, "01001F5010DFA000", "AEE8F150DDA1B5A8"))
    }

    @Test
    fun `the path of the second source is asked of the CDN with its spaces written out`() {
        assertEquals(
            "/Pokemon%20Legends%20Z-A/0100F43008C44000/cheats/B1F12FD919EAE86A.txt",
            CheatRepo.encodePath("/Pokemon Legends Z-A/0100F43008C44000/cheats/B1F12FD919EAE86A.txt"),
        )
    }

    @Test
    fun `the download writes the file of the build and the credit beside it`() {
        val dir = folder()
        val result = CheatRepo.downloadFrom(listOf(source("test", "Inf HP" to "04000000 011A1D00 52800108\n")),
            "01004AB00A260000", "DF3766A2BB651A3E", dir)
        assertTrue(result.isSuccess)
        val file = CheatRepo.file(dir, "DF3766A2BB651A3E")
        assertTrue(file.isFile)
        assertEquals("[Inf HP]\n04000000 011A1D00 52800108\n", file.readText())
        assertEquals("test", CheatRepo.creditFile(dir, "DF3766A2BB651A3E").readText())
    }

    @Test
    fun `a build no source has fails as such`() {
        val empty = object : CheatRepo.Source {
            override val label = "empty"
            override fun fileOf(titleId: String, build: String): CheatRepo.RawFile? = null
        }
        val result = CheatRepo.downloadFrom(listOf(empty), "01004AB00A260000", "DF3766A2BB651A3E", folder())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CheatRepo.NoSuchBuild)
    }

    // ---- the file layout: the game is a folder, the build is the file's name ----

    @Test
    fun `the game is the folder of 16 digits in the path`() {
        assertEquals("01004AB00A260000", CheatRepo.titleIdOf("titles/01004AB00A260000/cheats/DF3766A2BB651A3E.txt"))
        assertEquals("01004AB00A260000", CheatRepo.titleIdOf("atmosphere/titles/01004AB00A260000/cheats/DF3766A2BB651A3E.txt"))
        assertEquals("01004AB00A260000", CheatRepo.titleIdOf("cheats/01004AB00A260000/DF3766A2BB651A3E.txt"))
        assertEquals("0100F43008C44000", CheatRepo.titleIdOf("Pokemon Legends Z-A/0100F43008C44000/cheats/B1F12FD919EAE86A.txt"))
    }

    @Test
    fun `a path without a game folder is not a cheat file`() {
        assertNull(CheatRepo.titleIdOf("cheats/DF3766A2BB651A3E.txt"))
        assertNull(CheatRepo.titleIdOf("readme.txt"))
        assertNull(CheatRepo.titleIdOf("titles/Dark Souls/cheats/DF3766A2BB651A3E.txt"))
    }

    // ---- the import ----

    @Test
    fun `the import writes the file of the build where the host looks for it`() {
        val files = folder()
        val zip = zip(
            "titles/01004AB00A260000/cheats/DF3766A2BB651A3E.txt" to "[Inf HP]\n04000000 011A1D00 52800108\n\n",
        )
        val result = CheatRepo.importZip(zip, files)
        assertEquals(1, result.files)
        assertEquals(1, result.gameCount)
        assertEquals(mapOf("01004AB00A260000" to 1), result.games)
        val out = CheatRepo.file(CheatRepo.dir(files, "01004AB00A260000"), "DF3766A2BB651A3E")
        assertTrue(out.isFile)
        assertEquals("[Inf HP]\n04000000 011A1D00 52800108\n\n", out.readText())
    }

    @Test
    fun `the import takes the layouts the community uses`() {
        val files = folder()
        val zip = zip(
            "titles/01004AB00A260000/cheats/DF3766A2BB651A3E.txt" to "[A]\n04000000 011A1D00 52800108\n",
            "atmosphere/titles/01001F5010DFA000/cheats/AEE8F150DDA1B5A8.txt" to "[B]\n04000000 011A1D00 52800108\n",
            "cheats/0100F43008C44000/B1F12FD919EAE86A.txt" to "[C]\n04000000 011A1D00 52800108\n",
        )
        val result = CheatRepo.importZip(zip, files)
        assertEquals(3, result.files)
        assertEquals(3, result.gameCount)
        assertTrue(CheatRepo.file(CheatRepo.dir(files, "01004AB00A260000"), "DF3766A2BB651A3E").isFile)
        assertTrue(CheatRepo.file(CheatRepo.dir(files, "01001F5010DFA000"), "AEE8F150DDA1B5A8").isFile)
        assertTrue(CheatRepo.file(CheatRepo.dir(files, "0100F43008C44000"), "B1F12FD919EAE86A").isFile)
    }

    @Test
    fun `the resolution patches are not imported`() {
        val files = folder()
        val zip = zip(
            "cheats/01001F5010DFA000/AEE8F150DDA1B5A8.txt" to
                "[60 FPS]\n04000000 0397E8D8 00FE502B\n\n[Downscale Handheld 504p / Docked 756p]\n580F0000 042ECFC8\n",
        )
        val result = CheatRepo.importZip(zip, files)
        assertEquals(1, result.files)
        val out = CheatRepo.file(CheatRepo.dir(files, "01001F5010DFA000"), "AEE8F150DDA1B5A8")
        assertTrue(out.readText().contains("60 FPS"))
        assertFalse(out.readText().contains("504p"))
    }

    @Test
    fun `a file with only resolution patches is not imported at all`() {
        val files = folder()
        val zip = zip(
            "cheats/01001F5010DFA000/AEE8F150DDA1B5A8.txt" to "[Downscale Handheld 504p / Docked 756p]\n580F0000 042ECFC8\n",
        )
        val result = CheatRepo.importZip(zip, files)
        assertEquals(0, result.files)
        assertEquals(1, result.skipped)
        assertEquals(0, result.gameCount)
        assertFalse(CheatRepo.file(CheatRepo.dir(files, "01001F5010DFA000"), "AEE8F150DDA1B5A8").exists())
    }

    @Test
    fun `the credit beside the file is imported too`() {
        val files = folder()
        val zip = zip(
            "cheats/01006B601380E000/FA24EA3C3A512C07.txt" to "[移動速度X2]\n580F0000 01652508\n",
            "cheats/01006B601380E000/FA24EA3C3A512C07.credit" to "vgtxc/switch-cheat\n",
        )
        CheatRepo.importZip(zip, files)
        val credit = CheatRepo.creditFile(CheatRepo.dir(files, "01006B601380E000"), "FA24EA3C3A512C07")
        assertTrue(credit.isFile)
        assertEquals("vgtxc/switch-cheat", credit.readText().trim())
    }

    @Test
    fun `the files that are not cheat files are left out`() {
        val files = folder()
        val zip = zip(
            "readme.txt" to "hello",
            "cheats/01004AB00A260000/notes.txt" to "notes",
            "cheats/DF3766A2BB651A3E.txt" to "[A]\n04000000 011A1D00 52800108\n",
        )
        val result = CheatRepo.importZip(zip, files)
        assertEquals(0, result.files)
        assertEquals(1, result.unknown)   // the one without a game folder
    }

    @Test
    fun `a second import replaces the file of the same build`() {
        val files = folder()
        CheatRepo.importZip(zip("cheats/01004AB00A260000/DF3766A2BB651A3E.txt" to "[A]\n04000000 011A1D00 52800108\n"), files)
        CheatRepo.importZip(zip("cheats/01004AB00A260000/DF3766A2BB651A3E.txt" to "[B]\n04000000 011A1D04 3909DC08\n"), files)
        val out = CheatRepo.file(CheatRepo.dir(files, "01004AB00A260000"), "DF3766A2BB651A3E")
        assertEquals("[B]\n04000000 011A1D04 3909DC08\n", out.readText())
        assertEquals(1, CheatRepo.root(files).listFiles()!!.size)
    }

    /** A zip with the given entries (the path and its text) */
    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File(System.getProperty("java.io.tmpdir"), "nx2a-cheats-test-${counter++}.zip")
        file.delete()
        ZipOutputStream(FileOutputStream(file)).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    /** A source that holds one entry (the tests do not need the network) */
    private fun source(label: String, vararg entries: Pair<String, String>): CheatRepo.Source = object : CheatRepo.Source {
        override val label = label
        override fun fileOf(titleId: String, build: String): CheatRepo.RawFile =
            CheatRepo.RawFile(entries.map { CheatRepo.Entry(it.first, "[${it.first}]\n${it.second}") }, "")
    }

    /** A server that answers with the text (the code is 200 unless the test says otherwise) */
    private fun server(text: String, code: Int = 200): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/cheats/01004AB00A260000.json"
    }

    /** A folder of the test's own (the app's files folder is not there) */
    private fun folder(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "nx2a-cheats-test-${counter++}")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }
}
