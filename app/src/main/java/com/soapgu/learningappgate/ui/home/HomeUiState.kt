package com.soapgu.learningappgate.ui.home

import com.soapgu.learningappgate.rule.AccessPolicy
import com.soapgu.learningappgate.target.TargetAppResolution
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AllowedWindowTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

sealed interface HomeAccessStatusUi {
    data class Allowed(val closesAtText: String) : HomeAccessStatusUi
    data class OutsideWindow(val nextOpeningText: String) : HomeAccessStatusUi
    data object IntegrationPending : HomeAccessStatusUi
    data object TargetUnavailable : HomeAccessStatusUi
}

enum class TargetAppStatusUi {
    INSTALLED,
    NOT_INSTALLED,
    NO_LAUNCH_ACTIVITY,
}

data class HomeUiState(
    val accessStatus: HomeAccessStatusUi,
    val allowedWindowText: String,
    val accessibilityEnabled: Boolean,
    val targetAppStatus: TargetAppStatusUi,
    val launchEnabled: Boolean,
)

fun buildHomeUiState(
    resolution: TargetAppResolution,
    accessibilityEnabled: Boolean,
): HomeUiState {
    val targetStatus = when (resolution) {
        is TargetAppResolution.Available -> TargetAppStatusUi.INSTALLED
        TargetAppResolution.NotInstalled -> TargetAppStatusUi.NOT_INSTALLED
        TargetAppResolution.NoLaunchActivity -> TargetAppStatusUi.NO_LAUNCH_ACTIVITY
    }
    return createHomeUiState(targetStatus, accessibilityEnabled)
}

internal fun createHomeUiState(
    targetStatus: TargetAppStatusUi,
    accessibilityEnabled: Boolean,
): HomeUiState {
    return HomeUiState(
        accessStatus = if (targetStatus == TargetAppStatusUi.INSTALLED) {
            HomeAccessStatusUi.IntegrationPending
        } else {
            HomeAccessStatusUi.TargetUnavailable
        },
        allowedWindowText = defaultAllowedWindowText(),
        accessibilityEnabled = accessibilityEnabled,
        targetAppStatus = targetStatus,
        // M1.2 只交付 UI 壳；真实准入在 M1.3/M1.4 完成前始终关闭。
        launchEnabled = false,
    )
}

internal fun defaultAllowedWindowText(): String {
    val window = AccessPolicy.DEFAULT.timeWindow
    return buildString {
        append(window.startInclusive.format(AllowedWindowTimeFormatter))
        append('～')
        append(window.endExclusive.format(AllowedWindowTimeFormatter))
    }
}
