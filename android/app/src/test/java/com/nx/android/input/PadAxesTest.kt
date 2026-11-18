package com.nx.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轴事件的集合与轴角色的推断：数组的顺序是宿主那边的契约（jni 的 dm_java_event.axis），
 * 角色推断决定一只手柄的扳机与右摇杆在哪几根轴上（Xbox 族与 NS 的老约定正相反）。
 */
class PadAxesTest {

    @Test
    fun `the axis array keeps the host's slot order`() {
        val slots = PadAxes.read { axis -> axis.toFloat() }
        assertEquals(PadAxes.COUNT, slots.size)
        // 前 10 个槽是老顺序（老宿主只读它们），RX/RY 是追加的两个
        assertEquals(
            listOf(0f, 1f, 11f, 14f, 17f, 18f, 15f, 16f, 23f, 22f, 12f, 13f),
            slots.toList(),
        )
    }

    @Test
    fun `a pad with no known topology gets no roles`() {
        // Z/RZ 双向（老约定的右摇杆），没有 RX/RY：NS 手柄与老式安卓约定
        val roles = PadAxes.rolesOf(listOf(0 to -1f, 1 to -1f, 11 to -1f, 14 to -1f))
        assertEquals(PadAxes.Roles(), roles)
    }

    @Test
    fun `a pad whose triggers live on Z RZ gets the ztrig role`() {
        // Xbox 族：Z/RZ 单向（从 0 起），右摇杆在 RX/RY
        val roles = PadAxes.rolesOf(listOf(0 to -1f, 1 to -1f, 11 to 0f, 14 to 0f, 12 to -1f, 13 to -1f))
        assertTrue(roles.zTrig)
        assertTrue(roles.rxStick)
    }

    @Test
    fun `rx ry present means the right stick lives there`() {
        // 两对都在且都双向（有的手柄为了兼容两套都报）：右摇杆认 RX/RY，Z/RZ 不当扳机
        val roles = PadAxes.rolesOf(listOf(11 to -1f, 14 to -1f, 12 to -1f, 13 to -1f))
        assertFalse(roles.zTrig)
        assertTrue(roles.rxStick)
    }

    @Test
    fun `ztrig without rx ry still claims the triggers`() {
        val roles = PadAxes.rolesOf(listOf(0 to -1f, 1 to -1f, 11 to 0f, 14 to 0f))
        assertTrue(roles.zTrig)
        assertFalse(roles.rxStick)
    }

    @Test
    fun `half a trigger pair is not a trigger pair`() {
        // 只有 Z 单向、RZ 双向：不冒认（两根都单向才算）
        val roles = PadAxes.rolesOf(listOf(11 to 0f, 14 to -1f, 12 to -1f, 13 to -1f))
        assertFalse(roles.zTrig)
        assertTrue(roles.rxStick)
    }

    @Test
    fun `a stick-only pad has no z rz at all`() {
        val roles = PadAxes.rolesOf(listOf(0 to -1f, 1 to -1f, 12 to -1f, 13 to -1f, 17 to 0f, 18 to 0f))
        assertFalse(roles.zTrig)
        assertTrue(roles.rxStick)
    }

    @Test
    fun `a trigger-like z rz is bindable a stick-like one is not`() {
        // 单向的 Z/RZ 是抓键的自救出口；双向的是右摇杆，抓键处不会把它喂进来
        assertTrue(PadSources.bindableRange(11, 0f))
        assertTrue(PadSources.bindableRange(14, 0f))
        assertFalse(PadSources.bindableRange(11, -1f))
        assertFalse(PadSources.bindableRange(14, -1f))
        // 其余可绑的轴不看量程
        assertTrue(PadSources.bindableRange(17, 0f))
        assertTrue(PadSources.bindableRange(16, -1f))
        // 抓来的 Z/RZ 绑定要能过推表的闸门（isKnown），不然存了也推不出去
        assertTrue(PadSources.isKnown(PadSources.axis(11, 1f)))
        assertTrue(PadSources.isKnown(PadSources.axis(14, -1f)))
    }
}
