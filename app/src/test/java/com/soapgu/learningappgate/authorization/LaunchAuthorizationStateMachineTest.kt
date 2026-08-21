package com.soapgu.learningappgate.authorization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0.4 授权状态机单元测试（纯 JVM，时钟由测试传入）。
 *
 * 重点锁定单次会话语义：Active 后豆包离开前台即失效，再进豆包拒绝放行。
 */
class LaunchAuthorizationStateMachineTest {

    private fun machine() = LaunchAuthorizationStateMachine()

    @Test
    fun createPending_inIdle_succeeds() {
        val machine = machine()

        assertTrue(machine.createPending(nowMs = 1_000L))
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L), machine.state)
    }

    @Test
    fun createPending_whilePendingOrActive_isRejected() {
        val machine = machine()

        // Pending 期间拒绝重复授权（连点、多次点击入口）。
        machine.createPending(nowMs = 1_000L)
        assertFalse(machine.createPending(nowMs = 1_200L))

        // Active 期间同样拒绝。
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))
        assertFalse(machine.createPending(nowMs = 2_200L))
        assertEquals(LaunchAuthorizationState.Active(activatedAtMs = 2_000L), machine.state)
    }

    @Test
    fun createPending_afterPendingTimeout_succeedsAgain() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)

        // Pending 已过期但尚未被任何事件惰性失效（豆包没进前台、用户没离开守卫页），
        // 再次点击入口必须允许重新授权，而不是误报"已有授权进行中"。
        assertTrue(machine.createPending(nowMs = 6_100L))
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 6_100L), machine.state)
    }

    @Test
    fun createPending_afterRevoked_succeedsAgain() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)
        machine.revoke("启动失败")

        // 失效后允许重新授权。
        assertTrue(machine.createPending(nowMs = 3_000L))
    }

    @Test
    fun onTargetForeground_withinValidity_activatesAndPermits() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)

        // 荣耀广告页等过渡事件不经过本方法；豆包前台事件在 5 秒内到达即激活。
        assertTrue(machine.onTargetForeground(nowMs = 2_800L))
        assertEquals(LaunchAuthorizationState.Active(activatedAtMs = 2_800L), machine.state)

        // Active 期间持续放行。
        assertTrue(machine.onTargetForeground(nowMs = 4_000L))
    }

    @Test
    fun onTargetForeground_afterValidity_expiresAndRejects() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)

        // 边界值：5 秒内（5000ms 整）放行，超过 5 秒拒绝（真机 startActivity 到
        // 豆包前台约 1.8 秒，5 秒为 ROADMAP 约定值）。
        assertTrue(machine.onTargetForeground(nowMs = 6_000L))

        val expired = machine()
        expired.createPending(nowMs = 1_000L)
        assertFalse(expired.onTargetForeground(nowMs = 6_001L))
        assertTrue(expired.state is LaunchAuthorizationState.Revoked)
        // 失效后豆包事件持续拒绝（回到无授权拦截）。
        assertFalse(expired.onTargetForeground(nowMs = 7_000L))
    }

    @Test
    fun onTargetForeground_inIdleOrRevoked_rejects() {
        val machine = machine()

        // 无授权：豆包前台事件被拒绝，守卫按 M0.3-plus 拦截。
        assertFalse(machine.onTargetForeground(nowMs = 1_000L))
        assertEquals(LaunchAuthorizationState.Idle, machine.state)

        machine.revoke("测试撤销")
        assertFalse(machine.onTargetForeground(nowMs = 1_500L))
    }

    @Test
    fun onTargetLeftForeground_afterActive_revokes() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))

        // 单次会话核心：豆包离开前台（退出/被杀/切走）授权立即结束。
        machine.onTargetLeftForeground(nowMs = 3_000L)
        val revoked = machine.state
        assertTrue(revoked is LaunchAuthorizationState.Revoked)

        // 豆包被杀后再自己打开：授权已失效，拒绝放行 -> 拦截。
        assertFalse(machine.onTargetForeground(nowMs = 5_000L))
    }

    @Test
    fun onTargetLeftForeground_whilePending_isNoOp() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)

        // 荣耀广告页/AlertDialog 是非目标事件且发生在启动过渡期，
        // 不得让待生效授权失效（否则跨应用启动必被误杀）。
        machine.onTargetLeftForeground(nowMs = 1_500L)
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L), machine.state)

        // 豆包随后进入前台仍能正常激活。
        assertTrue(machine.onTargetForeground(nowMs = 2_500L))
    }

    @Test
    fun onTargetLeftForeground_inIdleOrRevoked_isNoOp() {
        val machine = machine()

        machine.onTargetLeftForeground(nowMs = 1_000L)
        assertEquals(LaunchAuthorizationState.Idle, machine.state)

        machine.createPending(nowMs = 1_000L)
        machine.revoke("测试撤销")
        machine.onTargetLeftForeground(nowMs = 2_000L)
        assertTrue(machine.state is LaunchAuthorizationState.Revoked)
    }

    @Test
    fun revoke_fromAnyState_transitionsToRevoked() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)

        machine.revoke("启动失败：SecurityException")
        val revoked = machine.state as LaunchAuthorizationState.Revoked
        assertEquals("启动失败：SecurityException", revoked.reason)

        // Revoked 后豆包事件拒绝放行。
        assertFalse(machine.onTargetForeground(nowMs = 2_000L))
    }

    @Test
    fun expireIfPendingTimeout_onlyExpiresNeverActivates() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L)

        // 未过期：读取快照不改变状态（界面刷新不得消耗授权）。
        machine.expireIfPendingTimeout(nowMs = 3_000L)
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L), machine.state)

        // 已过期：读取快照触发惰性失效，但不激活。
        machine.expireIfPendingTimeout(nowMs = 6_100L)
        assertTrue(machine.state is LaunchAuthorizationState.Revoked)
    }
}
