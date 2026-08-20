package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.orhanobut.logger.Logger
import com.soapgu.learningappgate.target.TargetApps

/**
 * M0.2 的无障碍事件观察服务。
 *
 * 本阶段只记录窗口事件的元数据，不读取页面节点、不采集豆包内容，也不执行 HOME 等全局操作。
 */
class GateAccessibilityService : AccessibilityService() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.d("服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in OBSERVED_EVENT_TYPES) {
            return
        }

        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        val isTarget = TargetAppEventMatcher.matches(event.packageName, TargetApps.DOUBAO)
        // 单调时钟不受用户修改系统时间影响，后续可直接用于计算事件和拦截延迟。
        Logger.d(
            "elapsedRealtime=${SystemClock.elapsedRealtime()} " +
                "eventType=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=$packageName class=$className " +
                "interactive=${powerManager.isInteractive} target=$isTarget",
        )
    }

    override fun onInterrupt() {
        Logger.d("服务被中断")
    }

    override fun onDestroy() {
        Logger.d("服务已销毁")
        super.onDestroy()
    }

    private companion object {
        // 与无障碍服务 XML 中声明的事件类型保持一致，避免处理无关的页面内容事件。
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}
