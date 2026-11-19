package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * App 自己那份日志文件：写进去的话读得回来，清掉之后读不到；长过它的上限之后只留尾巴。
 *
 * 「清空日志」清的就是它（屏幕上的那份是另一处记忆，见 PortState.clearLog）。
 */
class AppLogTest {

    @Test
    fun theLogIsReadBackAndCleared() {
        val dir = Files.createTempDirectory("nx2a-applog").toFile()
        val file = File(dir, "log.txt")
        AppLog.init(file)

        AppLog.line("first")
        AppLog.line("second")
        assertEquals("first\nsecond", AppLog.text())
        assertTrue("the log has the lines in it", AppLog.text().contains("second"))

        AppLog.clear()
        assertEquals("nothing is left after the clear", "", AppLog.text())

        file.delete()
        dir.delete()
    }

    @Test
    fun aLogPastItsCapKeepsItsTail() {
        val dir = Files.createTempDirectory("nx2a-applog").toFile()
        val file = File(dir, "log.txt")
        // a log that has grown past the cap: the first line is long gone by now
        file.writeText("the oldest line\n" + "filler\n".repeat((AppLog.MAX_BYTES / 4).toInt()) + "the newest line\n")
        assertTrue("the file is past the cap", file.length() > AppLog.MAX_BYTES)

        AppLog.init(file)

        assertTrue("the file is within the cap now: ${file.length()}", file.length() <= AppLog.MAX_BYTES)
        val text = AppLog.text(maxLines = 10)
        assertTrue("the newest lines are kept: $text", text.contains("the newest line"))
        assertFalse("the oldest lines are cut: $text", text.contains("the oldest line"))

        file.delete()
        dir.delete()
    }
}
