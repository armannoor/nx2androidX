package com.nx.android.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * The cheat files: the community's, in the Atmosphère format.
 *
 * They come to the phone two ways:
 *
 *  - **downloaded** from the community libraries, one build at a time (the drawer has the download row);
 *  - **imported** from a zip the player picked (Settings -> Environment -> Cheats).
 *
 * Either way the file lands in the app's own folder (files/cheats/<title ID>/<build ID>.txt), where the host
 * reads it. A rebuilt data pack does not touch that folder, so the cheats stay where they are.
 *
 * One file per build: the host reads the file named after the build it runs (the first 16 hex digits of main's
 * build ID), so a file of another build would write the wrong places and is never used.
 *
 * The libraries (for the download):
 *
 *  - **switch-cheats-db** (the aggregator): one JSON per game (its title ID), with one section per build - the
 *    keys are the cheat names ("[Inf HP]"), the values the text of the cheat. The section of the build the game
 *    runs is the file the host reads, its texts one after another (each one starts with its own [Name] header,
 *    so the file is the community's .txt byte for byte).
 *  - **MyNXCheats** (one author's own collection): the files are already in the Atmosphère format, but the
 *    folders are named by the game, so the repository's file tree says which file belongs to which game and
 *    build.
 *
 * The sources are tried in that order and the first one with a usable file wins. Nothing is merged across the
 * sources: two files that patch the same place would fight, and the player would not know which one won.
 */
object CheatRepo {

    /** The library's CDN (the community's files, one JSON per game) */
    const val CDN = "https://cdn.jsdelivr.net/gh/HamletDuFromage/switch-cheats-db@master/cheats"

    /**
     * The CDN's own edges: the same file, three networks. One network is often blocked (a reset TLS handshake
     * on some carriers), so the download walks the list and takes the first that answers.
     */
    private val EDGES = listOf("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net")

    /** The second source: one author's collection (its files are already in the Atmosphère format) */
    private const val MYNX_OWNER = "Arch9SK7"
    private const val MYNX_REPO = "MyNXCheats"
    private const val MYNX_REF = "master"

    /** The library's own file tree (the CDN's index of the second source): it names the folders by the game */
    private const val MYNX_TREE = "https://data.jsdelivr.com/v1/packages/gh/$MYNX_OWNER/$MYNX_REPO@$MYNX_REF?structure=flat"

    /**
     * The entries we skip: the resolution patches. The app has its own render scale (the drawer), and the two
     * change the same thing in the game - with both on, the result is not the one the player expects.
     */
    private val RESOLUTION = Regex("""\b\d{3,4}\s*p\b|downscale|upscale|resolution""", RegexOption.IGNORE_CASE)

    /** One cheat entry: its name (with the brackets) and its text. */
    data class Entry(val name: String, val text: String)

    /** A title ID: 16 hex digits (the folders the libraries name the games by) */
    private val TITLE_ID = Regex("""^[0-9A-Fa-f]{16}$""")

    /** A build ID: 16 hex digits (the files are named by it) */
    private val BUILD_ID = Regex("""^[0-9A-Fa-f]{16}$""")

    /** The file of one build: the text the host reads, the credit of the source and the source's name. */
    data class CheatFile(val text: String, val credit: String, val source: String)

    /** The library's file of one game (its title ID, upper case: the library names its files that way) */
    fun url(titleId: String): String = "$CDN/${titleId.uppercase()}.json"

    /** The same file on every edge (the download tries them in turn) */
    fun urls(titleId: String): List<String> =
        EDGES.map { "https://$it/gh/HamletDuFromage/switch-cheats-db@master/cheats/${titleId.uppercase()}.json" }

    /** The folder the app keeps the cheats of one game in (app-private: a rebuilt data pack does not touch it) */
    fun dir(filesDir: File, titleId: String): File = File(filesDir, "cheats/$titleId")

    /** The cheat file of one build (the name the host looks for) */
    fun file(dir: File, build: String): File = File(dir, "${build.uppercase()}.txt")

    /** The line of credit beside the file (what the library says about it) */
    fun creditFile(dir: File, build: String): File = File(dir, "${build.uppercase()}.credit")

    /**
     * The toggles of the cheats (the host writes them beside the file): the names of the cheats that are on, one a
     * line. Deleting that file turns them all off.
     *
     * Returns whether there was one (nothing to turn off when there was none).
     */
    fun clearToggles(dir: File, build: String): Boolean {
        val file = File(dir, "${build.uppercase()}.enabled")
        if (!file.isFile) return false
        return file.delete()
    }

    /**
     * The build ID the cheat files are named by, read from the data pack's rewriter table (its head line:
     * `# BUILD_ID <hex>`). The host knows the same value once it has loaded the modules; this is how to know it
     * before that. The first 16 hex digits, upper case: the name the cheat files carry.
     */
    fun buildIdOf(pack: File): String? {
        val file = File(pack, "mods/main.fixups.tsv")
        if (!file.isFile) return null
        val line = runCatching {
            file.useLines { lines -> lines.firstOrNull { it.trimStart().startsWith("#") && it.contains("BUILD_ID") } }
        }.getOrNull() ?: return null
        val id = line.trim().split(Regex("\\s+")).lastOrNull() ?: return null
        return id.takeIf { it.length >= 16 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            ?.take(16)?.uppercase()
    }

    /** The folder the app keeps all the imported cheats in */
    fun root(filesDir: File): File = File(filesDir, "cheats")

    /** What an import brought in. */
    data class Import(
        /** The games it brought files for: the title ID and the number of files */
        val games: Map<String, Int>,
        /** The files it wrote */
        val files: Int,
        /** The files it left out (only resolution patches, or nothing usable in them) */
        val skipped: Int,
        /** The files whose game it could not tell (no title ID in the path) */
        val unknown: Int,
    ) {
        /** The number of games it brought files for */
        val gameCount: Int get() = games.size
    }

    /**
     * Imports a zip of cheat files into the app's own folder (files/cheats/<title ID>/<build ID>.txt).
     *
     * The files of the zip are matched by their names: the file is named after the build (16 hex digits) and
     * the game's title ID is a folder name in the path. The resolution patches are left out, and a file that
     * holds nothing else is not written at all.
     *
     * The files that are already there are replaced by the new ones; the files of other games are left alone
     * (the player can import another game's pack later).
     */
    fun importZip(zip: File, filesDir: File): Import {
        if (!zip.isFile) throw IOException("the file is not there")
        val found = LinkedHashMap<String, String>()      // "<title ID>/<build ID>" -> the file's text
        val credits = LinkedHashMap<String, String>()    // the same key -> the line of credit
        var skipped = 0
        var unknown = 0
        ZipFile(zip).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val path = e.name
                if (path.startsWith("/") || path.split('/').any { it == ".." }) continue
                val base = path.substringAfterLast('/')
                if (base.endsWith(".credit")) {
                    val build = base.removeSuffix(".credit")
                    val title = titleIdOf(path) ?: continue
                    if (!BUILD_ID.matches(build)) continue
                    val text = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }.trim()
                    if (text.isNotEmpty()) credits["$title/$build"] = text
                    continue
                }
                if (!base.endsWith(".txt")) continue
                val build = base.removeSuffix(".txt")
                if (!BUILD_ID.matches(build)) continue
                val title = titleIdOf(path)
                if (title == null) { unknown++; continue }
                val text = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                // 只有分辨率类的条目：这条不管用，不写
                val kept = fileEntries(text).filterNot { isResolution(it.name) }
                if (kept.isEmpty()) { skipped++; continue }
                found["$title/${build.uppercase()}"] = kept.joinToString("") { it.text }
            }
        }
        val games = LinkedHashMap<String, Int>()
        for ((key, text) in found) {
            val (title, build) = key.split('/')
            val dir = dir(filesDir, title)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("the folder could not be made")
            val out = file(dir, build)
            out.writeText(text, Charsets.UTF_8)
            credits[key]?.let { runCatching { creditFile(dir, build).writeText(it, Charsets.UTF_8) } }
            games[title] = (games[title] ?: 0) + 1
        }
        return Import(games, found.size, skipped, unknown)
    }

    /** The game's title ID in a zip entry's path: the last folder name that is 16 digits. */
    internal fun titleIdOf(path: String): String? {
        val parts = path.split('/')
        // the last part is the file's own name; the title ID is a folder above it
        for (i in parts.size - 2 downTo 0) {
            if (i >= 0 && TITLE_ID.matches(parts[i])) return parts[i].uppercase()
        }
        return null
    }

    /**
     * The library's entries for the build (the section of that build), or null when it has no file for it.
     *
     * The section is a map of cheat names to their text; the file the host reads is those texts one after
     * another (each one starts with its own [Name] header, so the order the library lists them in is kept).
     */
    fun entriesOf(json: String, build: String): List<Entry>? {
        val root = runCatching { OrderedJson.parse(json) }.getOrNull() ?: return null
        val want = build.trim().uppercase()
        // the library's credit is a member of its own, not a build
        val section = root.entries.firstOrNull { it.key.uppercase() == want && it.key.uppercase() != "ATTRIBUTION" }
            ?.value as? Map<*, *> ?: return null
        val out = ArrayList<Entry>()
        for ((name, body) in section) {
            val text = body as? String ?: continue
            if (text.isEmpty()) continue
            out.add(Entry(name.toString(), if (text.endsWith("\n")) text else "$text\n"))
        }
        return out
    }

    /**
     * The entries of an Atmosphère file: the blocks that start with a [Name] or {Name} header. The text before
     * the first header is prose (the host ignores it, so it is dropped here too).
     *
     * The header is read the way the host reads it (the line starts with [ or {, the name is what lies before
     * the first closing bracket), and a block runs from its header to the next one: the text of the file is
     * kept as it is, so what is written back is the community's file byte for byte.
     */
    fun fileEntries(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var name: String? = null
        var start = 0
        var offset = 0
        for (line in text.split("\n")) {
            val header = headerOf(line)
            if (header != null) {
                if (name != null) out.add(Entry(name!!, text.substring(start, offset)))
                name = header
                start = offset
            }
            offset += line.length + 1
        }
        if (name != null) out.add(Entry(name!!, text.substring(start)))
        return out
    }

    /** Is this entry a resolution patch (the ones we skip: the app's own render scale does that)? */
    fun isResolution(name: String): Boolean = RESOLUTION.containsMatchIn(name)

    /**
     * The library's line of credit for the file: the first line of each file it names (the rest is the author's
     * own notes), or "" when it has none.
     */
    fun credit(json: String): String {
        val root = runCatching { OrderedJson.parse(json) }.getOrNull() ?: return ""
        val attribution = root["attribution"] as? Map<*, *> ?: return ""
        val lines = attribution.values.mapNotNull { it as? String }
            .map { it.trim().lines().firstOrNull()?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
        return lines.joinToString(" · ")
    }

    /**
     * Downloads the file of the build into [dir]: the first source that has a usable one, written out as the
     * file the host reads (with the source's credit beside it).
     *
     * Fails with [NoSuchBuild] when no source has a file for the build (the game is not in them, or they have
     * another build of it: a cheat file of another build would write the wrong places) and with [NothingUsable]
     * when the file they have holds nothing but resolution patches.
     */
    fun download(titleId: String, build: String, dir: File): Result<File> =
        downloadFrom(sources, titleId, build, dir)

    /** The same, from sources of one's own (the tests use it) */
    internal fun downloadFrom(sources: List<Source>, titleId: String, build: String, dir: File): Result<File> =
        runCatching {
            val cheat = fetchFile(sources, titleId, build)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("the folder could not be made")
            val out = file(dir, build)
            val tmp = File(out.path + ".part")
            tmp.writeText(cheat.text, Charsets.UTF_8)
            if (out.exists() && !out.delete()) throw IOException("the old file could not be replaced")
            if (!tmp.renameTo(out)) throw IOException("the file could not be put in place")
            // the credit is a note, not the file: a failure to write it must not fail the download
            runCatching { creditFile(dir, build).writeText(cheat.credit, Charsets.UTF_8) }
            out
        }

    /** The library holds no file for this build (the game is not in it, or it has another build of it) */
    class NoSuchBuild(message: String) : Exception(message)

    /** The file the library has for this build holds nothing but resolution patches (the app does that itself) */
    class NothingUsable(message: String) : Exception(message)

    /** Where the cheat files come from (the tests use their own). */
    internal interface Source {
        /** The name of the source (it goes into the credit) */
        val label: String

        /** The file of the build, or null when this source has none for it */
        fun fileOf(titleId: String, build: String): RawFile?
    }

    /** What a source holds of one build: its entries and its credit. */
    internal data class RawFile(val entries: List<Entry>, val credit: String)

    /**
     * The file of the build from the first source that has a usable one: the sources in order, and the first
     * with entries left after the resolution patches are skipped wins.
     */
    internal fun fetchFile(sources: List<Source>, titleId: String, build: String): CheatFile {
        var onlyResolution = false
        var networkError: IOException? = null
        for (source in sources) {
            val raw = try {
                source.fileOf(titleId, build)
            } catch (e: NoSuchBuild) {
                continue
            } catch (e: IOException) {
                networkError = e
                continue
            } ?: continue
            val kept = raw.entries.filterNot { isResolution(it.name) }
            if (kept.isEmpty()) { onlyResolution = true; continue }
            val credit = listOf(source.label, raw.credit).filter { it.isNotEmpty() }.joinToString(" · ")
            return CheatFile(kept.joinToString("") { it.text }, credit, source.label)
        }
        if (onlyResolution) throw NothingUsable("the library's file for this build is all resolution patches")
        // a network that failed is a failure, not an empty library
        networkError?.let { throw it }
        throw NoSuchBuild("no source has a file for this build")
    }

    /** Where the files come from, in order: the aggregator first, then one author's collection. */
    private val sources = listOf(DbSource, MyNxSource)

    /**
     * The aggregator: the community's cheat files, one JSON per game, with one section per build.
     */
    private object DbSource : Source {
        override val label = "switch-cheats-db"

        override fun fileOf(titleId: String, build: String): RawFile? {
            val text = fetch(urls(titleId))
            val entries = entriesOf(text, build) ?: return null
            return RawFile(entries, credit(text))
        }
    }

    /**
     * One author's own collection: the files are already in the Atmosphère format, but the folders are named by
     * the game, so the repository's file tree says which file belongs to which game and build.
     */
    private object MyNxSource : Source {
        override val label = "MyNXCheats"

        override fun fileOf(titleId: String, build: String): RawFile? {
            val path = mynxPath(titleId, build) ?: return null
            val text = fetchMynx(path)
            return RawFile(fileEntries(text), "")
        }
    }

    /** The second source's file tree (once per session: it says which file belongs to which game and build) */
    @Volatile
    private var mynxTree: List<String>? = null

    /**
     * The path of the build's file in the second source, or null when it has none.
     *
     * The tree is read from the network the first time it is needed: a network that fails is an error (the
     * player is told the download failed), not an empty library.
     */
    private fun mynxPath(titleId: String, build: String): String? {
        val tree = mynxTree ?: mynxTreeOf().also { mynxTree = it }
        return mynxFilePath(tree, titleId, build)
    }

    /**
     * The path of the build's file in the second source's tree, or null when it has none.
     *
     * The tree's paths start with the game's folder (`/Pokemon Legends Z-A/0100F43008C44000/cheats/<build>.txt`),
     * so the game's title ID and the build's file name are what the path ends with.
     */
    internal fun mynxFilePath(tree: List<String>, titleId: String, build: String): String? {
        val want = "/$titleId/cheats/${build.uppercase()}.txt"
        return tree.firstOrNull { it.endsWith(want, ignoreCase = true) }
    }

    /** Every .txt file of the second source (the paths start with the game's folder) */
    private fun mynxTreeOf(): List<String> {
        val text = try {
            body(MYNX_TREE)
        } catch (e: NoSuchBuild) {
            throw IOException("the file tree of the library is not there")
        }
        val files = runCatching { JSONObject(text).optJSONArray("files") }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(files.length())
        for (i in 0 until files.length()) {
            val name = files.optJSONObject(i)?.optString("name") ?: continue
            if (name.endsWith(".txt", ignoreCase = true)) out.add(name)
        }
        return out
    }

    /** The second source's file: the first edge that answers */
    private fun fetchMynx(path: String): String {
        var last: IOException = IOException("the library did not answer")
        for (edge in EDGES) {
            val url = "https://$edge/gh/$MYNX_OWNER/$MYNX_REPO@$MYNX_REF${encodePath(path)}"
            try {
                return body(url)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }

    /** A path for the CDN: the spaces and the few odd characters the folder names carry. */
    internal fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        segment.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
            .replace("#", "%23").replace("?", "%3F").replace("&", "%26")
    }

    /** The first URL that answers (a network that fails is tried again on the next edge) */
    private fun fetch(urls: List<String>): String {
        var last: IOException = IOException("the library did not answer")
        for (u in urls) {
            try {
                return body(u)
            } catch (e: NoSuchBuild) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }

    /** The library's answer: the body of it */
    private fun body(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "nx2android")
        }
        try {
            val code = conn.responseCode
            // 404: the library has no file of this game at all (it is not in the library)
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw NoSuchBuild("the library has no file of this game")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    /** The name of an entry's header ([Name] / {Name}), or null when the line is not a header. */
    private fun headerOf(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.length < 3) return null
        val c = trimmed[0]
        if (c != '[' && c != '{') return null
        val end = if (c == '[') ']' else '}'
        val close = trimmed.indexOf(end, 1)
        if (close < 2) return null
        return trimmed.substring(1, close)
    }
}
