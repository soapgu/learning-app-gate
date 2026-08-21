package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.orhanobut.logger.Logger
import com.soapgu.learningappgate.R
import com.soapgu.learningappgate.authorization.AuthorizationCenter
import com.soapgu.learningappgate.target.TargetApps

/**
 * M0.3 的无障碍守卫服务；M0.4 起接入临时授权放行。
 *
 * 事件到达时驱动 [GateInterceptionStateMachine] 做防重入裁决；裁决为拦截时执行退出动作，
 * 覆盖层提示延迟到会话结束（观测到豆包真正离开前台）后显示，避免提示窗口自身的
 * TYPE_WINDOW_STATE_CHANGED 干扰状态机（2026-08-21 时序日志证实）。
 * 授权激活期间（[AuthorizationCenter]）豆包前台事件直接放行；豆包离开前台即结束授权。
 * 服务自身不承载授权、计时等业务规则（M0.5 由 PrototypeAccessController 接管）。
 */
class GateAccessibilityService : AccessibilityService() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val interceptionStateMachine = GateInterceptionStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var interceptionOverlay: InterceptionOverlay

    /** 本会话待显示的拦截提示；会话结束时（豆包真正离开前台）显示并清除。 */
    private var pendingOverlayNotice = false

    /** 本会话最近一次豆包前台事件的 class；用于识别"页面被 BACK 回退"的 class 变化。 */
    private var lastTargetClassName: String? = null

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
        var refilledBack = false
        var permitted = false

        val decision = when {
            // 目标前台事件（两种窗口事件均可触发，避免个别入口只上报其中一种）。
            isTarget -> {
                // M0.4 授权放行查询先行：授权激活期间（守卫入口"授权并启动"）
                // 豆包前台事件不进入拦截链路；豆包离开前台后授权立即失效。
                permitted = AuthorizationCenter.onTargetForeground()
                if (permitted) {
                    // 放行期间不维护拦截状态机与 class 记忆：授权结束后的
                    // 拦截会话自行学习首见 class（首见事件跳过补发比较）。
                    null
                } else {
                    // 先识别 class 变化：抑制期内豆包 class 变化说明第一发 BACK 被用于
                    // 页面回退（如 MainActivity -> DrawerLayout），是精准补发第二发的信号。
                    val className = event.className?.toString()
                    val previousClassName = lastTargetClassName
                    if (previousClassName != null && className != previousClassName) {
                        if (!isForegroundStillTarget()) {
                            // 条件驱动补发（2026-08-22 真机结论）：BACK 注入与豆包退出存在
                            // 竞态，第 3 发曾穿透到刚 resume 的守卫上把它一起退掉；补发前确认
                            // 活跃窗口仍属于豆包，否则立即停止补发（不消耗配额）。
                            Logger.d(
                                "跳过精准补发：活跃窗口已非豆包 " +
                                    "current=${rootInActiveWindow?.packageName} elapsedRealtime=$nowMs",
                            )
                        } else if (interceptionStateMachine.onTargetClassChanged(nowMs)) {
                            refilledBack = true
                            Logger.d(
                                "精准补发：豆包页面回退 class=$previousClassName -> $className " +
                                    "refill=${interceptionStateMachine.backRefillCount} " +
                                    "elapsedRealtime=$nowMs",
                            )
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }
                    lastTargetClassName = className
                    interceptionStateMachine.onTargetForeground(nowMs)
                }
            }

            // 非目标前台事件：说明豆包已实际离开前台，结束当前抑制会话。
            // 仅窗口状态变化可靠携带包名；windows 变化常无包名，不作为重置信号。
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // M0.4 单次会话授权：豆包真正离开前台即结束授权
                //（Pending 期不受影响，荣耀广告页等过渡事件不误伤待生效授权）。
                AuthorizationCenter.onTargetLeftForeground()
                if (interceptionStateMachine.isSuppressing) {
                    finishInterceptionSession(event.packageName?.toString().orEmpty(), nowMs)
                } else {
                    interceptionStateMachine.onOtherForeground()
                }
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
                "decision=${decision?.let(::decisionName) ?: "none"}" +
                (if (permitted) " permitted=true" else "") +
                (if (refilledBack) " refilledBack=true" else ""),
        )
    }

    override fun onInterrupt() {
        Logger.d("服务被中断")
    }

    override fun onDestroy() {
        Logger.d("服务已销毁")
        mainHandler.removeCallbacksAndMessages(null)
        if (::interceptionOverlay.isInitialized) {
            interceptionOverlay.hide()
        }
        super.onDestroy()
    }

    /** 执行一次拦截：按当前方案退出目标应用并记录诊断数据；提示延迟到会话结束显示。 */
    private fun performInterception(nowMs: Long) {
        when (val exitAction = InterceptionActionPolicy.exitAction) {
            ExitAction.BACK -> performBackInterception(nowMs)
            ExitAction.HOME -> performHomeInterception(nowMs)
        }
        InterceptionDiagnostics.record(nowMs)
        pendingOverlayNotice = true
    }

    /**
     * 拦截会话结束（观测到豆包真正离开前台）：重置状态机并显示延迟的拦截提示。
     * 此时覆盖层出现在豆包已离开之后，其自身窗口事件落在 Idle 态，不再干扰状态机。
     */
    private fun finishInterceptionSession(currentForegroundPackage: String, nowMs: Long) {
        interceptionStateMachine.onOtherForeground()
        Logger.d(
            "会话结束：豆包已离开前台，当前前台=$currentForegroundPackage " +
                "elapsedRealtime=$nowMs",
        )
        if (pendingOverlayNotice) {
            pendingOverlayNotice = false
            if (ENABLE_OVERLAY_PROMPT) {
                Logger.d("显示拦截提示（延迟至会话结束）：elapsedRealtime=$nowMs")
                interceptionOverlay.show(getString(R.string.interception_message))
            } else {
                // 2026-08-22 真机验证结论：覆盖层窗口事件是拦截死循环的必要环节，
                // 用户确认 M0.3-plus 不恢复覆盖层提示（BACK 退出后直接回守卫即可）。
                Logger.d("覆盖层已禁用：elapsedRealtime=$nowMs")
            }
        }
    }

    /**
     * BACK 方案（M0.3-plus）：单次 `GLOBAL_ACTION_BACK` + 抑制期内 class 变化精准补发。
     *
     * 真机时序日志（2026-08-21）证实：第一发 BACK 常被豆包用于内部页面回退
     * （MainActivity -> DrawerLayout），随后的 class 变化事件是补发第二发的明确信号；
     * 冷启动期间 BACK 无效且无 class 变化时，由 3 秒抑制超时兜底再次拦截。
     */
    private fun performBackInterception(nowMs: Long) {
        val result = performGlobalAction(GLOBAL_ACTION_BACK)
        Logger.d(
            "执行拦截（BACK 单次）：elapsedRealtime=$nowMs result=$result " +
                "count=${interceptionStateMachine.interceptionCount}",
        )
    }

    /** HOME 方案（M0.3 已验收）：直接回到桌面，保留作对比与回归。 */
    private fun performHomeInterception(nowMs: Long) {
        val homeResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Logger.d(
            "执行拦截（HOME）：elapsedRealtime=$nowMs homeResult=$homeResult " +
                "count=${interceptionStateMachine.interceptionCount}",
        )
    }

    private fun decisionName(decision: InterceptionDecision): String {
        return when (decision) {
            InterceptionDecision.Intercept -> "intercept"
            InterceptionDecision.Ignore -> "ignore"
        }
    }

    /**
     * 补发前的活跃窗口校验：仅当活跃窗口已明确切换到其他应用（守卫/桌面）时才阻止补发。
     *
     * [rootInActiveWindow] 在窗口切换瞬间（恰是页面回退信号到达时）返回 null，
     * 属过渡态：触发补发的事件本身就是豆包的窗口上报（豆包存活的直接证据），
     * 此时放行（2026-08-22 真机结论：null 按拒绝处理会误杀全部真实回退信号，
     * 豆包退不掉）。真正的穿透防护由状态机的补发间隔下限承担。
     */
    private fun isForegroundStillTarget(): Boolean {
        val activePackage = rootInActiveWindow?.packageName ?: return true
        return TargetAppEventMatcher.matches(activePackage, TargetApps.DOUBAO)
    }

    private companion object {
        // 与无障碍服务 XML 中声明的事件类型保持一致，避免处理无关的页面内容事件。
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )

        // M0.3-plus 拦截提示开关：覆盖层在拦截瞬间显示会引发死循环（其窗口事件在
        // 抑制期内被当作"豆包已离开"重置状态机），现改为延迟到会话结束（豆包真正
        // 离开前台、状态机已回 Idle）后显示。2026-08-22 用户要求重新启用验证。
        const val ENABLE_OVERLAY_PROMPT = true
    }
}
