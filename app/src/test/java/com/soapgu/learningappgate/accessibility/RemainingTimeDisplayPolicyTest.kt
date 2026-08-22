package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemainingTimeDisplayPolicyTest {
    @Test
    fun formatDuration_roundsUpAndFormatsBoundaries() {
        assertEquals("00:30", RemainingTimeDisplayPolicy.formatDuration(30_000L))
        assertEquals("00:30", RemainingTimeDisplayPolicy.formatDuration(29_001L))
        assertEquals("01:00", RemainingTimeDisplayPolicy.formatDuration(60_000L))
        assertEquals("10:00", RemainingTimeDisplayPolicy.formatDuration(600_000L))
        assertEquals("1:00:00", RemainingTimeDisplayPolicy.formatDuration(3_600_000L))
        assertEquals("00:00", RemainingTimeDisplayPolicy.formatDuration(0L))
        assertEquals("00:00", RemainingTimeDisplayPolicy.formatDuration(-1L))
    }

    @Test
    fun shouldShow_onlyForActiveAuthorization() {
        assertTrue(RemainingTimeDisplayPolicy.shouldShow(LaunchAuthorizationState.Active(0L, 30_000L)))
        assertFalse(RemainingTimeDisplayPolicy.shouldShow(LaunchAuthorizationState.Idle))
        assertFalse(RemainingTimeDisplayPolicy.shouldShow(LaunchAuthorizationState.Pending(0L, 30_000L)))
        assertFalse(RemainingTimeDisplayPolicy.shouldShow(LaunchAuthorizationState.Paused(30_000L, 1_000L, 1_000L)))
        assertFalse(RemainingTimeDisplayPolicy.shouldShow(LaunchAuthorizationState.Revoked("测试")))
    }

    @Test
    fun isWarning_onlyForPositiveFinalMinute() {
        assertFalse(RemainingTimeDisplayPolicy.isWarning(60_001L))
        assertTrue(RemainingTimeDisplayPolicy.isWarning(60_000L))
        assertTrue(RemainingTimeDisplayPolicy.isWarning(1L))
        assertFalse(RemainingTimeDisplayPolicy.isWarning(0L))
    }
}
