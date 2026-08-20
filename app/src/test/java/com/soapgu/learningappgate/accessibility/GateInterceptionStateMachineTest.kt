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
}
