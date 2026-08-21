package com.soapgu.learningappgate.controller

import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import com.soapgu.learningappgate.authorization.LaunchAuthorizationStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0.5 控制器单元测试（纯 JVM：注入可控时钟与 fake 调度器）。
 *
 * 重点锁定 ROADMAP M0.5 验收：到期收回恰好一次（幂等）、定时器早触发重算不误收回、
 * 剩余时长恒非负、离开前台结算片段并取消计时、Revoked 终态不被旧任务重新激活。
 */
class PrototypeAccessControllerTest {

    /** fake 调度器：记录最近一次调度与取消；保留已触发任务引用以模拟旧任务迟到重放。 */
    private class FakeScheduler : AccessScheduler {
        var postedDelayMs: Long? = null
            private set
        var postedAction: (() -> Unit)? = null
            private set
        var isCancelled = false
            private set

        override fun postDelayed(delayMs: Long, action: () -> Unit) {
            postedDelayMs = delayMs
            postedAction = action
            isCancelled = false
        }

        override fun cancel() {
            isCancelled = true
        }

        fun fire() {
            postedAction?.invoke()
        }
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val logs = mutableListOf<String>()
        var nowMs = 0L
        var expiredCount = 0

        val controller = PrototypeAccessController(
            clock = { nowMs },
            scheduler = scheduler,
            log = { message -> logs.add(message) },
        )

        fun activate(totalMs: Long, atMs: Long) {
            nowMs = atMs - 1_000L
            assertTrue(controller.createPending(totalMs))
            nowMs = atMs
            assertTrue(controller.onTargetForeground())
        }
    }

    @Test
    fun activation_schedulesExpiryWithFullQuota() {
        val h = Harness()

        h.nowMs = 1_000L
        assertTrue(h.controller.createPending(totalMs = 30_000L))
        h.nowMs = 2_000L
        assertTrue(h.controller.onTargetForeground())

        // 激活即起表：到期任务按剩余额度（此处为全额 30 秒）调度。
        assertEquals(
            LaunchAuthorizationState.Active(activatedAtMs = 2_000L, totalMs = 30_000L),
            h.controller.state,
        )
        assertEquals(30_000L, h.scheduler.postedDelayMs)
    }

    @Test
    fun expiry_atQuotaEnd_revokesAndInvokesCallbackExactlyOnce() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // 额度耗尽（激活 2s + 30s = 32s）：到期任务触发即原子转 Revoked 并回调一次。
        h.nowMs = 32_000L
        h.scheduler.fire()
        val revoked = h.controller.state as LaunchAuthorizationState.Revoked
        assertEquals(LaunchAuthorizationStateMachine.TIME_EXPIRED_REASON, revoked.reason)
        assertEquals(1, h.expiredCount)

        // 同一任务重复触发（迟到/重放）：幂等，不重复回调、不改状态。
        h.scheduler.fire()
        assertEquals(1, h.expiredCount)
        assertEquals(revoked, h.controller.state)

        // 收回后再进豆包：拒绝放行（回到未授权拦截）。
        h.nowMs = 33_000L
        assertFalse(h.controller.onTargetForeground())
    }

    @Test
    fun expiry_firedEarly_recalculatesRemainingAndReschedules() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // 定时器早触发（激活后仅 8 秒，实际剩余 22 秒）：重算后重新调度，不误收回。
        h.nowMs = 10_000L
        h.scheduler.fire()
        assertTrue(h.controller.state is LaunchAuthorizationState.Active)
        assertEquals(0, h.expiredCount)
        assertEquals(22_000L, h.scheduler.postedDelayMs)

        // 重调度后按真实到期时间触发：正常收回。
        h.nowMs = 32_000L
        h.scheduler.fire()
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
        assertEquals(1, h.expiredCount)
    }

    @Test
    fun tenMinuteQuota_expiresAtBoundaryAndRecalls() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 600_000L, atMs = 1_000L)
        assertEquals(600_000L, h.scheduler.postedDelayMs)

        // 10 分钟档与 30 秒档逻辑同构：激活 1s + 600s = 601s 处收回。
        h.nowMs = 601_000L
        h.scheduler.fire()
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
        assertEquals(1, h.expiredCount)
        h.nowMs = 602_000L
        assertFalse(h.controller.onTargetForeground())
    }

    @Test
    fun onTargetLeftForeground_settlesSegmentCancelsAndRevokes() {
        val h = Harness()
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // 豆包离开前台：结算当前片段（消耗 10 秒）写入诊断日志、取消到期任务、授权结束。
        h.nowMs = 12_000L
        h.controller.onTargetLeftForeground()
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
        assertTrue(h.scheduler.isCancelled)
        assertTrue(h.logs.any { it.contains("授权片段结算：consumedForegroundMs=10000") })

        // 再进豆包按未授权拦截。
        h.nowMs = 13_000L
        assertFalse(h.controller.onTargetForeground())
    }

    @Test
    fun lazyFallback_whenTimerLost_revokesAndRejects() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // 定时器丢失（如调度线程异常）：额度耗尽后的豆包前台事件惰性兜底收回，
        // 不允许额度穿透；此路径不触发到期回调（由拦截链路处理该事件）。
        h.nowMs = 32_500L
        assertFalse(h.controller.onTargetForeground())
        assertEquals(LaunchAuthorizationStateMachine.TIME_EXPIRED_REASON, (h.controller.state as LaunchAuthorizationState.Revoked).reason)
        assertEquals(0, h.expiredCount)
        assertTrue(h.scheduler.isCancelled)
    }

    @Test
    fun revoke_cancelsScheduledExpiryAndOldTaskDoesNothing() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        h.controller.revoke("启动失败")
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
        assertTrue(h.scheduler.isCancelled)

        // 旧到期任务（若仍被系统派发）迟到：不做任何事，Revoked 为终态。
        h.nowMs = 40_000L
        h.scheduler.fire()
        assertEquals(0, h.expiredCount)
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
    }

    @Test
    fun remainingMs_clampedAtZero_andNeverNegative() {
        val h = Harness()
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        assertEquals(30_000L, h.controller.remainingMs())
        h.nowMs = 17_000L
        assertEquals(15_000L, h.controller.remainingMs())

        // 额度超耗后剩余钳制为 0（验收：不出现负数时长）。
        h.nowMs = 200_000L
        assertEquals(0L, h.controller.remainingMs())
    }
}
