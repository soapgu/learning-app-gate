package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import java.util.Locale

/** 剩余时间胶囊的纯显示策略。 */
object RemainingTimeDisplayPolicy {
    fun shouldShow(state: LaunchAuthorizationState): Boolean =
        state is LaunchAuthorizationState.Active

    /** 毫秒向上取整到秒；一小时内显示 MM:SS，一小时起显示 H:MM:SS。 */
    fun formatDuration(remainingMs: Long): String {
        val safeMs = remainingMs.coerceAtLeast(0L)
        val totalSeconds = if (safeMs == 0L) 0L else (safeMs + 999L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun isWarning(remainingMs: Long): Boolean = remainingMs in 1..60_000L
}
