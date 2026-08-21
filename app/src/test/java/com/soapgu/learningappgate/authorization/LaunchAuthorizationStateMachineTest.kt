package com.soapgu.learningappgate.authorization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0.4/M0.5/M0.6 授权状态机单元测试（纯 JVM，时钟由测试传入）。
 *
 * 重点锁定额度制语义：离开前台/关屏暂停结算（不消耗额度）、重新前台从剩余继续、
 * 额度耗尽收回、暂停期重置；多次快速切换后累计正确、状态转换唯一。
 */
class LaunchAuthorizationStateMachineTest {

    private fun machine() = LaunchAuthorizationStateMachine()

    @Test
    fun createPending_inIdle_succeeds() {
        val machine = machine()

        assertTrue(machine.createPending(nowMs = 1_000L, totalMs = 30_000L))
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L, totalMs = 30_000L), machine.state)
    }

    @Test
    fun createPending_whilePendingOrActive_isRejected() {
        val machine = machine()

        // Pending 期间拒绝重复授权（连点、多次点击入口）。
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        assertFalse(machine.createPending(nowMs = 1_200L, totalMs = 30_000L))

        // Active 期间同样拒绝。
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))
        assertFalse(machine.createPending(nowMs = 2_200L, totalMs = 30_000L))
        assertEquals(
            LaunchAuthorizationState.Active(activatedAtMs = 2_000L, totalMs = 30_000L),
            machine.state,
        )
    }

    @Test
    fun createPending_whilePaused_resetsOldQuota() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))
        machine.onTargetLeftForeground(nowMs = 12_000L)
        assertTrue(machine.state is LaunchAuthorizationState.Paused)

        // 暂停期重置（2026-08-21 用户决策）：旧额度作废，直接创建新 Pending。
        assertTrue(machine.createPending(nowMs = 60_000L, totalMs = 600_000L))
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 60_000L, totalMs = 600_000L), machine.state)
    }

    @Test
    fun createPending_afterPendingTimeout_succeedsAgain() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)

        // Pending 已过期但尚未被任何事件惰性失效（豆包没进前台、用户没离开守卫页），
        // 再次点击入口必须允许重新授权，而不是误报"已有授权进行中"。
        assertTrue(machine.createPending(nowMs = 6_100L, totalMs = 600_000L))
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 6_100L, totalMs = 600_000L), machine.state)
    }

    @Test
    fun createPending_afterRevoked_succeedsAgain() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        machine.revoke("启动失败")

        // 失效后允许重新授权。
        assertTrue(machine.createPending(nowMs = 3_000L, totalMs = 30_000L))
    }

    @Test
    fun onTargetForeground_withinValidity_activatesWithQuota() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)

        // 荣耀广告页等过渡事件不经过本方法；豆包前台事件在 5 秒内到达即激活，
        // 激活时刻起算额度（M0.5：30 秒档）。
        assertTrue(machine.onTargetForeground(nowMs = 2_800L))
        assertEquals(
            LaunchAuthorizationState.Active(activatedAtMs = 2_800L, totalMs = 30_000L),
            machine.state,
        )

        // Active 且额度未尽期间持续放行。
        assertTrue(machine.onTargetForeground(nowMs = 4_000L))
    }

    @Test
    fun onTargetForeground_afterValidity_expiresAndRejects() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)

        // 边界值：5 秒内（5000ms 整）放行，超过 5 秒拒绝（真机 startActivity 到
        // 豆包前台约 1.8 秒，5 秒为 ROADMAP 约定值）。
        assertTrue(machine.onTargetForeground(nowMs = 6_000L))

        val expired = machine()
        expired.createPending(nowMs = 1_000L, totalMs = 30_000L)
        assertFalse(expired.onTargetForeground(nowMs = 6_001L))
        assertTrue(expired.state is LaunchAuthorizationState.Revoked)
        // 失效后豆包事件持续拒绝（回到无授权拦截）。
        assertFalse(expired.onTargetForeground(nowMs = 7_000L))
    }

    @Test
    fun onTargetForeground_quotaExhausted_lazilyRevokesAndRejects() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))

        // 定时器丢失的兜底：额度耗尽后的下一个豆包前台事件（即使状态仍是 Active）
        // 必须惰性转 Revoked 并拒绝放行，不允许额度穿透。
        assertFalse(machine.onTargetForeground(nowMs = 32_100L))
        val revoked = machine.state as LaunchAuthorizationState.Revoked
        assertEquals(LaunchAuthorizationStateMachine.TIME_EXPIRED_REASON, revoked.reason)

        // 耗尽后持续拒绝。
        assertFalse(machine.onTargetForeground(nowMs = 33_000L))
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
    fun onTargetLeftForeground_afterActive_pausesWithSettlement() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))

        // M0.6：离开前台不再失效，而是结算当前片段（2s~12s 共 10 秒）转 Paused。
        machine.onTargetLeftForeground(nowMs = 12_000L)
        val paused = machine.state as LaunchAuthorizationState.Paused
        assertEquals(10_000L, paused.accumulatedForegroundMs)
        assertEquals(30_000L, paused.totalMs)
        assertEquals(12_000L, paused.pausedAtMs)
        assertEquals(20_000L, paused.remainingMs())

        // 重新进入前台：从剩余 20 秒继续（新片段，累计值保留）。
        assertTrue(machine.onTargetForeground(nowMs = 60_000L))
        val resumed = machine.state as LaunchAuthorizationState.Active
        assertEquals(10_000L, resumed.accumulatedForegroundMs)
        assertEquals(60_000L, resumed.segmentStartAtMs)
        assertEquals(20_000L, resumed.remainingMs(nowMs = 60_000L))
        assertEquals(10_000L, resumed.remainingMs(nowMs = 70_000L))
    }

    @Test
    fun rapidSwitching_accumulatesOnlyForegroundTime() {
        val machine = machine()
        machine.createPending(nowMs = 0L, totalMs = 30_000L)
        assertTrue(machine.onTargetForeground(nowMs = 1_000L))

        // 多次快速切换（验收：累计时间只含前台片段，状态转换唯一）。
        machine.onTargetLeftForeground(nowMs = 3_000L)                      // 片段 1：2 秒
        assertTrue(machine.onTargetForeground(nowMs = 10_000L))
        machine.onTargetLeftForeground(nowMs = 11_000L)                     // 片段 2：1 秒
        assertTrue(machine.onTargetForeground(nowMs = 20_000L))
        machine.onTargetLeftForeground(nowMs = 24_000L)                     // 片段 3：4 秒

        val paused = machine.state as LaunchAuthorizationState.Paused
        assertEquals(7_000L, paused.accumulatedForegroundMs)
        assertEquals(23_000L, paused.remainingMs())
    }

    @Test
    fun onTargetLeftForeground_whilePending_isNoOp() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)

        // 荣耀广告页/AlertDialog 是非目标事件且发生在启动过渡期，
        // 不得让待生效授权失效（否则跨应用启动必被误杀）。
        machine.onTargetLeftForeground(nowMs = 1_500L)
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L, totalMs = 30_000L), machine.state)

        // 豆包随后进入前台仍能正常激活。
        assertTrue(machine.onTargetForeground(nowMs = 2_500L))
    }

    @Test
    fun onTargetLeftForeground_inIdleOrPausedOrRevoked_isNoOp() {
        val machine = machine()

        machine.onTargetLeftForeground(nowMs = 1_000L)
        assertEquals(LaunchAuthorizationState.Idle, machine.state)

        // 已暂停：再次非目标事件（其他应用继续切换）不得改变暂停态或额度。
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        machine.onTargetForeground(nowMs = 2_000L)
        machine.onTargetLeftForeground(nowMs = 4_000L)
        machine.onTargetLeftForeground(nowMs = 9_000L)
        val paused = machine.state as LaunchAuthorizationState.Paused
        assertEquals(2_000L, paused.accumulatedForegroundMs)

        machine.revoke("测试撤销")
        machine.onTargetLeftForeground(nowMs = 10_000L)
        assertTrue(machine.state is LaunchAuthorizationState.Revoked)
    }

    @Test
    fun onScreenOff_whileActive_pausesWithSettlement() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        assertTrue(machine.onTargetForeground(nowMs = 2_000L))

        // 豆包在前台直接关屏：结算片段转 Paused（关屏期间不消耗额度）。
        machine.onScreenOff(nowMs = 8_000L)
        val paused = machine.state as LaunchAuthorizationState.Paused
        assertEquals(6_000L, paused.accumulatedForegroundMs)
        assertEquals(24_000L, paused.remainingMs())

        // 解锁回到豆包：从剩余 24 秒继续。
        assertTrue(machine.onTargetForeground(nowMs = 60_000L))
        assertEquals(24_000L, (machine.state as LaunchAuthorizationState.Active).remainingMs(nowMs = 60_000L))
    }

    @Test
    fun onScreenOff_whilePendingOrPausedOrIdle_isNoOp() {
        val machine = machine()

        machine.onScreenOff(nowMs = 1_000L)
        assertEquals(LaunchAuthorizationState.Idle, machine.state)

        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        machine.onScreenOff(nowMs = 2_000L)
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L, totalMs = 30_000L), machine.state)

        machine.onTargetForeground(nowMs = 3_000L)
        machine.onScreenOff(nowMs = 5_000L)
        machine.onScreenOff(nowMs = 9_000L)
        val paused = machine.state as LaunchAuthorizationState.Paused
        assertEquals(2_000L, paused.accumulatedForegroundMs)
    }

    @Test
    fun onTargetForeground_pausedWithZeroRemaining_revokesAndRejects() {
        val machine = machine()
        machine.createPending(nowMs = 0L, totalMs = 10_000L)
        assertTrue(machine.onTargetForeground(nowMs = 1_000L))

        // 额度恰在切出瞬间耗尽（1s~11s 恰好用满 10 秒）：暂停后剩余为 0，
        // 再进豆包直接收回并拒绝（走未授权拦截链路）。
        machine.onTargetLeftForeground(nowMs = 11_000L)
        assertFalse(machine.onTargetForeground(nowMs = 50_000L))
        val revoked = machine.state as LaunchAuthorizationState.Revoked
        assertEquals(LaunchAuthorizationStateMachine.TIME_EXPIRED_REASON, revoked.reason)
    }

    @Test
    fun revoke_fromAnyState_transitionsToRevoked() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)

        machine.revoke("启动失败：SecurityException")
        val revoked = machine.state as LaunchAuthorizationState.Revoked
        assertEquals("启动失败：SecurityException", revoked.reason)

        // Revoked 后豆包事件拒绝放行。
        assertFalse(machine.onTargetForeground(nowMs = 2_000L))

        // Paused 态撤销同样直达 Revoked。
        val pausedMachine = machine()
        pausedMachine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        pausedMachine.onTargetForeground(nowMs = 2_000L)
        pausedMachine.onTargetLeftForeground(nowMs = 4_000L)
        pausedMachine.revoke("验收诊断")
        assertTrue(pausedMachine.state is LaunchAuthorizationState.Revoked)
        assertFalse(pausedMachine.onTargetForeground(nowMs = 5_000L))
    }

    @Test
    fun expireIfPendingTimeout_onlyExpiresNeverActivates() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)

        // 未过期：读取快照不改变状态（界面刷新不得消耗授权）。
        machine.expireIfPendingTimeout(nowMs = 3_000L)
        assertEquals(LaunchAuthorizationState.Pending(createdAtMs = 1_000L, totalMs = 30_000L), machine.state)

        // 已过期：读取快照触发惰性失效，但不激活。
        machine.expireIfPendingTimeout(nowMs = 6_100L)
        assertTrue(machine.state is LaunchAuthorizationState.Revoked)
    }

    @Test
    fun remainingMs_neverNegative_acrossBoundaries() {
        val machine = machine()
        machine.createPending(nowMs = 1_000L, totalMs = 30_000L)
        machine.onTargetForeground(nowMs = 2_000L)
        val active = machine.state as LaunchAuthorizationState.Active

        // 起点剩余为全额；途中线性扣减；耗尽与超耗后钳制为 0（验收：不出现负数时长）。
        assertEquals(30_000L, active.remainingMs(nowMs = 2_000L))
        assertEquals(20_000L, active.remainingMs(nowMs = 12_000L))
        assertEquals(0L, active.remainingMs(nowMs = 32_000L))
        assertEquals(0L, active.remainingMs(nowMs = 100_000L))
    }
}
