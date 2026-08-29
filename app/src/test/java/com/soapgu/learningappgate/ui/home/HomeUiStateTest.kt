package com.soapgu.learningappgate.ui.home

import com.soapgu.learningappgate.rule.AccessPolicy
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun installedTarget_showsIntegrationPendingAndNeverEnablesLaunch() {
        val state = createHomeUiState(
            targetStatus = TargetAppStatusUi.INSTALLED,
            accessibilityEnabled = true,
        )

        assertEquals(HomeAccessStatusUi.IntegrationPending, state.accessStatus)
        assertEquals(TargetAppStatusUi.INSTALLED, state.targetAppStatus)
        val window = AccessPolicy.DEFAULT.timeWindow
        val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val expectedWindowText = "${window.startInclusive.format(formatter)}～${window.endExclusive.format(formatter)}"
        assertEquals(expectedWindowText, state.allowedWindowText)
        assertTrue(state.accessibilityEnabled)
        assertFalse(state.launchEnabled)
    }

    @Test
    fun notInstalled_takesPriorityOverIntegrationPending() {
        val state = createHomeUiState(TargetAppStatusUi.NOT_INSTALLED, accessibilityEnabled = false)

        assertEquals(HomeAccessStatusUi.TargetUnavailable, state.accessStatus)
        assertEquals(TargetAppStatusUi.NOT_INSTALLED, state.targetAppStatus)
        assertFalse(state.launchEnabled)
    }

    @Test
    fun noLaunchActivity_takesPriorityOverIntegrationPending() {
        val state = createHomeUiState(TargetAppStatusUi.NO_LAUNCH_ACTIVITY, accessibilityEnabled = true)

        assertEquals(HomeAccessStatusUi.TargetUnavailable, state.accessStatus)
        assertEquals(TargetAppStatusUi.NO_LAUNCH_ACTIVITY, state.targetAppStatus)
        assertFalse(state.launchEnabled)
    }
}
