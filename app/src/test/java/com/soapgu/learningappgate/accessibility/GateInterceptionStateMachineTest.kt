package com.soapgu.learningappgate.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateInterceptionStateMachineTest {
    private fun machine(suppressTimeoutMs: Long = 3_000L) = GateInterceptionStateMachine(suppressTimeoutMs)

    @Test
    fun idle_targetForeground_interceptsOnceAndCounts() {
        val stateMachine = machine()

        val decision = stateMachine.onTargetForeground(nowMs = 1_000L)

        assertEquals(InterceptionDecision.Intercept, decision)
        assertEquals(1, stateMachine.interceptionCount)
        assertEquals(1_000L, stateMachine.lastInterceptionAtMs)
        assertTrue(stateMachine.isSuppressing)
    }

    @Test
    fun intercepted_repeatedTargetEventsWithinSuppression_areIgnored() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 拦截级联与事件重复上报：抑制期内到达的目标事件一律忽略。
        assertEquals(InterceptionDecision.Ignore, stateMachine.onTargetForeground(nowMs = 1_050L))
        assertEquals(InterceptionDecision.Ignore, stateMachine.onTargetForeground(nowMs = 1_500L))
        assertEquals(InterceptionDecision.Ignore, stateMachine.onTargetForeground(nowMs = 3_999L))
        assertEquals(1, stateMachine.interceptionCount)
    }

    @Test
    fun otherForeground_resetsSuppression_soNextTargetInterceptsAgain() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 拦截后非目标前台事件（含 BACK 退出过程中的系统过渡事件）结束本会话。
        stateMachine.onOtherForeground()
        assertFalse(stateMachine.isSuppressing)

        // 豆包再次进入前台属于新会话，必须再次拦截（BACK 方案的第二下正由此提供）。
        assertEquals(InterceptionDecision.Intercept, stateMachine.onTargetForeground(nowMs = 1_200L))
        assertEquals(2, stateMachine.interceptionCount)
        assertEquals(1_200L, stateMachine.lastInterceptionAtMs)
    }

    @Test
    fun otherForeground_inIdleState_isHarmless() {
        val stateMachine = machine()

        stateMachine.onOtherForeground()

        assertEquals(InterceptionDecision.Intercept, stateMachine.onTargetForeground(nowMs = 1_000L))
        assertEquals(1, stateMachine.interceptionCount)
    }

    @Test
    fun intercepted_targetEventAfterSuppressTimeout_interceptsAgain() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 抑制期内一直等不到非目标前台事件（拦截动作未生效或事件丢失），超时后按新会话兜底拦截。
        assertEquals(InterceptionDecision.Intercept, stateMachine.onTargetForeground(nowMs = 4_000L))
        assertEquals(2, stateMachine.interceptionCount)
        assertEquals(4_000L, stateMachine.lastInterceptionAtMs)
    }

    @Test
    fun intercepted_timeoutBoundary_exactlyAtTimeoutIsStillIgnored() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 超时判断使用 >=，恰好达到阈值时仍处于抑制期。
        assertEquals(InterceptionDecision.Ignore, stateMachine.onTargetForeground(nowMs = 3_999L))
        assertEquals(InterceptionDecision.Intercept, stateMachine.onTargetForeground(nowMs = 4_000L))
    }

    @Test
    fun repeatedCycles_interceptResetIntercept_keepCounting() {
        val stateMachine = machine()
        var now = 0L

        repeat(3) { index ->
            assertEquals(InterceptionDecision.Intercept, stateMachine.onTargetForeground(nowMs = now))
            assertEquals(InterceptionDecision.Ignore, stateMachine.onTargetForeground(nowMs = now + 100L))
            stateMachine.onOtherForeground()
            now += 10_000L
            assertEquals(index + 1, stateMachine.interceptionCount)
        }
    }

    @Test
    fun onTargetClassChanged_withinSuppression_allowsRefillUpToLimit() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 抑制期内 class 变化（间隔不小于补发下限）：精准补发有效，每会话最多 2 次。
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 1_500L))
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 1_900L))
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 2_300L))
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 2_700L))
    }

    @Test
    fun onTargetClassChanged_inIdleState_isRejected() {
        val stateMachine = machine()

        // Idle 态（尚未拦截，或残留事件）：class 变化不是补发信号。
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 500L))

        // 会话结束回到 Idle 后，同样拒绝补发（此时的新 class 变化属于新会话）。
        stateMachine.onTargetForeground(nowMs = 1_000L)
        stateMachine.onOtherForeground()
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 1_200L))
    }

    @Test
    fun onTargetClassChanged_refillCount_resetsOnNewSession() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 1_500L))
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 1_900L))
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 2_300L))

        // 抑制超时开启新会话：补发额度与间隔基准重新可用。
        stateMachine.onTargetForeground(nowMs = 4_200L)
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 4_700L))
    }

    @Test
    fun onTargetClassChanged_intervalBelowMinimum_isRejectedWithoutConsumingQuota() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 真实页面回退约需 428ms；主 BACK 后 30ms 内的 class 变化是窗口抖动。
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 1_030L))
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 1_399L))
        assertEquals(0, stateMachine.backRefillCount)

        // 间隔满足后补发仍可用（抖动未消耗配额）。
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 1_400L))
        assertEquals(1, stateMachine.backRefillCount)
    }

    @Test
    fun onTargetClassChanged_intervalMeasuredFromLastBack_includingRefill() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // 第一发补发后，间隔基准切换为补发时刻：主拦截后已过 1 秒不代表可直接连发。
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 2_000L))
        assertFalse(stateMachine.onTargetClassChanged(nowMs = 2_399L))
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 2_400L))
        assertEquals(2, stateMachine.backRefillCount)
    }

    @Test
    fun onTargetClassChanged_doesNotAffectInterceptionDecision() {
        val stateMachine = machine()
        stateMachine.onTargetForeground(nowMs = 1_000L)

        // class 变化补发与拦截裁决互不干扰：抑制期内目标事件仍被忽略。
        assertTrue(stateMachine.onTargetClassChanged(nowMs = 1_500L))
        assertEquals(InterceptionDecision.Ignore, stateMachine.onTargetForeground(nowMs = 1_510L))
        assertEquals(1, stateMachine.interceptionCount)
    }
}
