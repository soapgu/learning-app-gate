package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.orhanobut.logger.Logger
import com.soapgu.learningappgate.R
import com.soapgu.learningappgate.target.TargetApps

/**
 * M0.3 的无障碍守卫服务。
 *
 * 事件到达时驱动 [GateInterceptionStateMachine] 做防重入裁决；裁决为拦截时执行一次
 * GLOBAL_ACTION_HOME 并通过 [InterceptionOverlay] 显示友好提示。服务自身不承载授权、
 * 计时等业务规则（M0.5 由 PrototypeAccessController 接管）。
 */
class GateAccessibilityService : AccessibilityService() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val interceptionStateMachine = GateInterceptionStateMachine()
    private lateinit var interceptionOverlay: InterceptionOverlay

    override fun onServiceConnected() {
        super.onServiceConnected()
        // TYPE_ACCESSIBILITY_OVERLAY 窗口由已绑定的无障碍服务直接创建即可，无需额外 flag
        //（与官方 GlobalActionBarService 示例一致）。
        interceptionOverlay = InterceptionOverlay(this)
        Logger.d("服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in OBSERVED_EVENT_TYPES) {
            return
        }

        val isTarget = TargetAppEventMatcher.matches(event.packageName, TargetApps.DOUBAO)
        val nowMs = SystemClock.elapsedRealtime()

        val decision = when {
            // 目标前台事件（两种窗口事件均可触发，避免个别入口只上报其中一种）。
            isTarget -> interceptionStateMachine.onTargetForeground(nowMs)

            // 非目标前台事件：说明豆包已实际离开前台，结束当前抑制会话。
            // 仅窗口状态变化可靠携带包名；windows 变化常无包名，不作为重置信号。
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                interceptionStateMachine.onOtherForeground()
                null
            }

            else -> null
        }

        if (decision == InterceptionDecision.Intercept) {
            performInterception(nowMs)
        }

        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        Logger.d(
            "elapsedRealtime=$nowMs " +
                "eventType=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=$packageName class=$className " +
                "interactive=${powerManager.isInteractive} target=$isTarget " +
                "decision=${decision?.let(::decisionName) ?: "none"}",
        )
    }

    override fun onInterrupt() {
        Logger.d("服务被中断")
    }

    override fun onDestroy() {
        Logger.d("服务已销毁")
        if (::interceptionOverlay.isInitialized) {
            interceptionOverlay.hide()
        }
        super.onDestroy()
    }

    /** 执行一次拦截：回到桌面、显示提示并记录诊断数据。 */
    private fun performInterception(nowMs: Long) {
        val homeResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Logger.d(
            "执行拦截：elapsedRealtime=$nowMs homeResult=$homeResult " +
                "count=${interceptionStateMachine.interceptionCount}",
        )
        InterceptionDiagnostics.record(nowMs)
        interceptionOverlay.show(getString(R.string.interception_message))
    }

    private fun decisionName(decision: InterceptionDecision): String {
        return when (decision) {
            InterceptionDecision.Intercept -> "intercept"
            InterceptionDecision.Ignore -> "ignore"
        }
    }

    private companion object {
        // 与无障碍服务 XML 中声明的事件类型保持一致，避免处理无关的页面内容事件。
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}
