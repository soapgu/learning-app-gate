package com.soapgu.learningappgate.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackRefillRecheckCoordinatorTest {
    @Test
    fun earlyClassChange_keepsOriginalBaselineUntilRecheck() {
        val coordinator = BackRefillRecheckCoordinator()
        coordinator.observeTargetClass("VideoActivity")
        coordinator.schedule("VideoActivity")
        coordinator.observeTargetClass("ConversationActivity")

        assertTrue(coordinator.hasPendingRecheck)
        assertEquals(
            BackRefillRecheckDecision.Refill("VideoActivity", "ConversationActivity"),
            coordinator.consumeRecheck(stillTarget = true),
        )
        assertFalse(coordinator.hasPendingRecheck)
    }

    @Test
    fun laterClassChanges_replaceLatestSignalButNotOriginalBaseline() {
        val coordinator = BackRefillRecheckCoordinator()
        coordinator.observeTargetClass("VideoActivity")
        coordinator.schedule("VideoActivity")
        coordinator.observeTargetClass("ConversationActivity")
        coordinator.schedule("VideoActivity")
        coordinator.observeTargetClass("ListActivity")

        assertEquals(
            BackRefillRecheckDecision.Refill("VideoActivity", "ListActivity"),
            coordinator.consumeRecheck(stillTarget = true),
        )
    }

    @Test
    fun recheckOutsideTarget_cancelsWithoutRefill() {
        val coordinator = BackRefillRecheckCoordinator()
        coordinator.observeTargetClass("VideoActivity")
        coordinator.schedule("VideoActivity")
        coordinator.observeTargetClass("ConversationActivity")

        assertEquals(
            BackRefillRecheckDecision.Cancel(baselineClass = null),
            coordinator.consumeRecheck(stillTarget = false),
        )
        assertFalse(coordinator.hasPendingRecheck)
    }

    @Test
    fun pageReturningToOriginalClass_cancelsAndCommitsOriginalBaseline() {
        val coordinator = BackRefillRecheckCoordinator()
        coordinator.observeTargetClass("VideoActivity")
        coordinator.schedule("VideoActivity")
        coordinator.observeTargetClass("ConversationActivity")
        coordinator.observeTargetClass("VideoActivity")

        assertEquals(
            BackRefillRecheckDecision.Cancel(baselineClass = "VideoActivity"),
            coordinator.consumeRecheck(stillTarget = true),
        )
    }

    @Test
    fun cancel_clearsPendingRecheck() {
        val coordinator = BackRefillRecheckCoordinator()
        coordinator.observeTargetClass("VideoActivity")
        coordinator.schedule("VideoActivity")

        coordinator.cancel()

        assertFalse(coordinator.hasPendingRecheck)
        assertEquals(
            BackRefillRecheckDecision.Cancel(baselineClass = null),
            coordinator.consumeRecheck(stillTarget = true),
        )
    }
}
