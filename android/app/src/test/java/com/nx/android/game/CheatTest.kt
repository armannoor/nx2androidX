package com.nx.android.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宿主给的金手指列表：JSON 解析（名字、开关、不能跑的原因） */
class CheatTest {

    @Test
    fun `the list keeps the file order and the states`() {
        val cheats = parseCheats(
            """[{"name":"Infinite Health","on":true,"why":""},{"name":"60 FPS","on":false,"why":"it writes the game's code"}]""",
        )
        assertEquals(2, cheats.size)
        assertEquals("Infinite Health", cheats[0].name)
        assertTrue(cheats[0].on)
        assertEquals("", cheats[0].why)
        assertTrue(!cheats[1].on)
        assertTrue(cheats[1].broken)
    }

    @Test
    fun `a name with a quote does not break the list`() {
        val cheats = parseCheats("""[{"name":"the \"big\" one","on":false,"why":""}]""")
        assertEquals(1, cheats.size)
        assertEquals("the \"big\" one", cheats[0].name)
    }

    @Test
    fun `nothing from the host means no cheats`() {
        assertEquals(emptyList<Cheat>(), parseCheats(""))
        assertEquals(emptyList<Cheat>(), parseCheats("not json"))
    }
}
