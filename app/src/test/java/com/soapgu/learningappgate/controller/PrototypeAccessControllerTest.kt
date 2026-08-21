package com.soapgu.learningappgate.controller

import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import com.soapgu.learningappgate.authorization.LaunchAuthorizationStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0.5/M0.6 控制器单元测试（纯 JVM：注入可控时钟与 fake 调度器）。
 *
 * 重点锁定验收：到期收回恰好一次（幂等）、定时器早触发重算不误收回、
 * 剩余时长恒非负、暂停取消计时/恢复按剩余重新起表、关屏暂停、
 * 暂停期重置、多次快速暂停恢复后到期仍恰好收回一次。
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
    fun onTargetLeftForeground_pausesSettlesAndCancels_thenResumeReschedules() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // M0.6：离开前台不再失效，而是结算片段（消耗 10 秒）转 Paused 并取消到期任务。
        h.nowMs = 12_000L
        h.controller.onTargetLeftForeground()
        val paused = h.controller.state as LaunchAuthorizationState.Paused
        assertEquals(10_000L, paused.accumulatedForegroundMs)
        assertEquals(20_000L, h.controller.remainingMs())
        assertTrue(h.scheduler.isCancelled)
        assertTrue(h.logs.any { it.contains("授权暂停（离开前台）：consumedForegroundMs=10000") })

        // 暂停期到期的旧任务若仍被派发：非 Active，幂等无事可做。
        h.nowMs = 40_000L
        h.scheduler.fire()
        assertEquals(0, h.expiredCount)
        assertTrue(h.controller.state is LaunchAuthorizationState.Paused)

        // 重新进入前台：从剩余 20 秒恢复，到期任务按剩余额度（非全额）重新起表。
        h.nowMs = 60_000L
        assertTrue(h.controller.onTargetForeground())
        assertEquals(20_000L, h.scheduler.postedDelayMs)

        // 恢复后按剩余额度到期收回（60s + 20s = 80s），恰好一次。
        h.nowMs = 80_000L
        h.scheduler.fire()
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
        assertEquals(1, h.expiredCount)
    }

    @Test
    fun onScreenOff_pausesSettlesAndCancels() {
        val h = Harness()
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // 豆包在前台直接关屏：立即挂起计时（结算 6 秒），关屏期间不消耗额度。
        h.nowMs = 8_000L
        h.controller.onScreenOff()
        val paused = h.controller.state as LaunchAuthorizationState.Paused
        assertEquals(6_000L, paused.accumulatedForegroundMs)
        assertTrue(h.scheduler.isCancelled)
        assertTrue(h.logs.any { it.contains("授权暂停（关屏）") })

        // 关屏很久后解锁回到豆包：从冻结的 24 秒继续。
        h.nowMs = 100_000L
        assertTrue(h.controller.onTargetForeground())
        assertEquals(24_000L, h.scheduler.postedDelayMs)
        assertEquals(24_000L, h.controller.remainingMs())
    }

    @Test
    fun createPending_whilePaused_resetsOldQuota() {
        val h = Harness()
        h.activate(totalMs = 30_000L, atMs = 2_000L)
        h.nowMs = 12_000L
        h.controller.onTargetLeftForeground()

        // 暂停期重置：旧额度作废，新授权生命周期独立。
        h.nowMs = 60_000L
        assertTrue(h.controller.createPending(totalMs = 600_000L))
        assertEquals(
            LaunchAuthorizationState.Pending(createdAtMs = 60_000L, totalMs = 600_000L),
            h.controller.state,
        )
        assertTrue(h.logs.any { it.contains("重置，作废旧额度") })

        // 新授权激活后按新额度起表（与旧授权的 20 秒剩余无关）。
        h.nowMs = 61_000L
        assertTrue(h.controller.onTargetForeground())
        assertEquals(600_000L, h.scheduler.postedDelayMs)
    }

    @Test
    fun rapidPauseResume_thenExpiry_recallsExactlyOnce() {
        val h = Harness()
        h.controller.onExpired = { h.expiredCount += 1 }
        h.activate(totalMs = 30_000L, atMs = 1_000L)

        // 多次快速暂停/恢复（验收：累计正确、转换唯一、无重复收回）。
        h.nowMs = 3_000L
        h.controller.onTargetLeftForeground()
        h.nowMs = 10_000L
        assertTrue(h.controller.onTargetForeground())
        h.nowMs = 11_000L
        h.controller.onScreenOff()
        h.nowMs = 20_000L
        assertTrue(h.controller.onTargetForeground())
        h.nowMs = 24_000L
        h.controller.onTargetLeftForeground()

        // 前台累计 2+1+4=7 秒，剩余 23 秒；恢复后 23 秒到期。
        h.nowMs = 30_000L
        assertTrue(h.controller.onTargetForeground())
        assertEquals(23_000L, h.scheduler.postedDelayMs)
        h.nowMs = 53_000L
        h.scheduler.fire()
        assertTrue(h.controller.state is LaunchAuthorizationState.Revoked)
        assertEquals(1, h.expiredCount)

        // 收回后重放旧任务与再进豆包均不复活授权。
        h.scheduler.fire()
        h.nowMs = 54_000L
        assertFalse(h.controller.onTargetForeground())
        assertEquals(1, h.expiredCount)
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

    @Test
    fun remainingMs_whilePaused_frozenAtSettledValue() {
        val h = Harness()
        h.activate(totalMs = 30_000L, atMs = 2_000L)

        // 暂停期间剩余冻结，不随时间流逝扣减。
        h.nowMs = 12_000L
        h.controller.onTargetLeftForeground()
        h.nowMs = 500_000L
        assertEquals(20_000L, h.controller.remainingMs())
    }
}
