package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.soapgu.learningappgate.target.TargetApps

class GateAccessibilityService : AccessibilityService() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        debugLog("服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in OBSERVED_EVENT_TYPES) {
            return
        }

        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        val isTarget = TargetAppEventMatcher.matches(event.packageName, TargetApps.DOUBAO)
        debugLog(
            "elapsedRealtime=${SystemClock.elapsedRealtime()} " +
                "eventType=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=$packageName class=$className " +
                "interactive=${powerManager.isInteractive} target=$isTarget",
        )
    }

    override fun onInterrupt() {
        debugLog("服务被中断")
    }

    override fun onDestroy() {
        debugLog("服务已销毁")
        super.onDestroy()
    }

    private fun debugLog(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TAG, message)
        }
    }

    private companion object {
        const val TAG = "GateAccessibility"
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}

