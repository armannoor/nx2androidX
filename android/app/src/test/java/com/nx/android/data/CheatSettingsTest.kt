package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 金手指的总开关与启动时给宿主的那一行 */
class CheatSettingsTest {

    @Test
    fun `the master on gives the host the folder`() {
        assertEquals(
            listOf("DM_CHEATS=/sdcard/pack/cheats"),
            CheatKnobs.envLine(master = true, cheatsDir = "/sdcard/pack/cheats").toList(),
        )
    }

    @Test
    fun `the master off says nothing to the host`() {
        assertEquals(
            emptyList<String>(),
            CheatKnobs.envLine(master = false, cheatsDir = "/sdcard/pack/cheats").toList(),
        )
    }

    @Test
    fun `a pack without cheats says nothing to the host`() {
        assertEquals(
            emptyList<String>(),
            CheatKnobs.envLine(master = true, cheatsDir = null).toList(),
        )
        assertEquals(
            emptyList<String>(),
            CheatKnobs.envLine(master = true, cheatsDir = "").toList(),
        )
    }
}
