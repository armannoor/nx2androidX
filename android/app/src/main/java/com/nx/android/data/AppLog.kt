package com.nx.android.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile

/**
 * The app's own log: the things the app itself has to say (a step that failed, a file that could not be
 * read) land in a file (files/nx2a/log.txt) in the app's own directory. The tools' own words do not go
 * here - a build's output belongs to that build, a game's belongs to that game - so the file stays small
 * enough to read and to carry along in a log bundle.
 */
object AppLog {

    /** The file's cap: past it only the tail is kept, the same cap the log bundle gives this file */
    const val MAX_BYTES = 4L shl 20

    private var file: File? = null
    private var writer: BufferedWriter? = null

    fun init(ctx: Context) {
        if (file != null) return
        init(File(ctx.filesDir, "nx2a/log.txt"))
    }

    /** The log file itself: the app's own directory in the app, a temp file in the tests */
    fun init(f: File) {
        try { writer?.close() } catch (e: Exception) {}
        writer = null
        f.parentFile?.mkdirs()
        file = f
        trim()
    }

    fun line(s: String) {
        val f = file ?: return
        try {
            val w = writer ?: BufferedWriter(FileWriter(f, true)).also { writer = it }
            w.write(s + "\n")
            w.flush()
        } catch (e: Exception) {
        }
    }

    /** The log file itself: the bundle takes it as it is */
    fun logFile(): File? = file

    /** The log's text (the tail, for sharing) */
    fun text(maxLines: Int = 400): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return try {
            f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    fun clear() {
        try { writer?.close() } catch (e: Exception) {}
        writer = null
        file?.delete()
    }

    /**
     * Keeps the file within its cap: the tail is what happened lately, the head is what happened long
     * ago. The cut may land in the middle of a line - that first half-line is dropped.
     */
    private fun trim() {
        val f = file ?: return
        try {
            if (!f.isFile || f.length() <= MAX_BYTES) return
            val kept = RandomAccessFile(f, "r").use { raf ->
                raf.seek(f.length() - MAX_BYTES)
                val bytes = ByteArray(MAX_BYTES.toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val cut = text.indexOf('\n')
                if (cut >= 0) text.substring(cut + 1) else text
            }
            f.writeText(kept, Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }
}
