package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.orhanobut.logger.Logger
import com.soapgu.learningappgate.R
import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import com.soapgu.learningappgate.controller.prototypeAccessController
import com.soapgu.learningappgate.target.TargetApps

/**
 * M0.3 的无障碍守卫服务；M0.5 起接入限时授权放行与到期收回，M0.6 起接入暂停恢复。
 *
 * 事件到达时驱动 [GateInterceptionStateMachine] 做防重入裁决；裁决为拦截时执行退出动作，
 * 覆盖层提示延迟到会话结束（观测到豆包真正离开前台）后显示，避免提示窗口自身的
 * TYPE_WINDOW_STATE_CHANGED 干扰状态机（2026-08-21 时序日志证实）。
 * 授权激活期间（[prototypeAccessController]）豆包前台事件直接放行；豆包离开前台或
 * 关屏即挂起计时（Paused），重新前台从剩余额度继续；额度到期由控制器回调，走与
 * 未授权拦截完全相同的 BACK 管线收回。
 * 服务自身不承载授权、计时等业务规则（由 PrototypeAccessController 接管）。
 */
class GateAccessibilityService : AccessibilityService() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val interceptionStateMachine = GateInterceptionStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var interceptionOverlay: InterceptionOverlay
    private lateinit var remainingTimeOverlay: RemainingTimeOverlay

    /** 剩余时间胶囊的每秒刷新任务；非 Active 或豆包离开前台时取消。 */
    private var remainingTimeRefreshRunnable: Runnable? = null

    /** 本会话待显示的拦截提示（null 表示无）；会话结束时（豆包真正离开前台）显示并清除。 */
    private var pendingOverlayNoticeMessage: String? = null

    /** 本会话最近一次豆包前台事件的 class；用于识别"页面被 BACK 回退"的 class 变化。 */
    private var lastTargetClassName: String? = null

    /**
     * M0.6 暂停仲裁：排队中的"活跃窗口过渡态"延迟复检（爆发式事件只排一次）。
     * 豆包前台事件到达时作废（豆包窗口事件是前台的确定性证据，见 cancelPendingPauseRecheck）。
     */
    private var pauseRecheckPending = false
    private var pauseRecheckRunnable: Runnable? = null

    /** 到期时活跃窗口为 Unknown 的单次延迟复检；不得向未知前台发送 BACK。 */
    private var expiryRecheckRunnable: Runnable? = null

    /** M0.6 屏幕事件接收器：关屏立即暂停计时；解锁后若豆包在前台则恢复（窗口事件缺失的兜底）。 */
    private val screenEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Logger.d("屏幕关闭：挂起计时 elapsedRealtime=${SystemClock.elapsedRealtime()}")
                    prototypeAccessController.onScreenOff()
                    stopRemainingTimeOverlay()
                }

                Intent.ACTION_USER_PRESENT -> {
                    // 解锁本身不恢复计时（ROADMAP M0.6）；但解锁直接回到豆包时不保证
                    // 有 TYPE_WINDOW_STATE_CHANGED 上报，若豆包确为活跃窗口则主动恢复，
                    // 避免"豆包可用而额度暂停"的穿透。窗口事件正常到达时此调用为
                    // 幂等 no-op（已 Active，放行）。
                    val activePackage = rootInActiveWindow?.packageName
                    if (prototypeAccessController.state is LaunchAuthorizationState.Paused &&
                        TargetAppEventMatcher.matches(activePackage, TargetApps.DOUBAO)
                    ) {
                        Logger.d(
                            "解锁且豆包在前台：恢复计时 activePackage=$activePackage " +
                                "elapsedRealtime=${SystemClock.elapsedRealtime()}",
                        )
                        if (prototypeAccessController.onTargetForeground()) {
                            startOrRefreshRemainingTimeOverlay()
                        }
                    }
                }
            }
        }
    }
    private var screenEventReceiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // TYPE_ACCESSIBILITY_OVERLAY 窗口由已绑定的无障碍服务直接创建即可，无需额外 flag
        //（与官方 GlobalActionBarService 示例一致）。
        interceptionOverlay = InterceptionOverlay(this)
        remainingTimeOverlay = RemainingTimeOverlay(this)
        // 到期收回（M0.5）：控制器已原子化转 Revoked 后回调；走与未授权拦截相同的
        // 拦截状态机（进入抑制态）与 BACK 管线，后续 class 变化由精准补发兜底，
        // 豆包由守卫入口启动，BACK 会自然退回守卫 App（用户决策，替代原定 HOME）。
        // 到期收回（M0.5）：控制器已原子化转 Revoked 后回调；走与未授权拦截相同的
        // 拦截状态机（进入抑制态）与 BACK 管线，后续 class 变化由精准补发兜底，
        // 豆包由守卫入口启动，BACK 会自然退回守卫 App（用户决策，替代原定 HOME）。
        // M0.7 到期保护：到期瞬间用户可能已切走豆包，此时 BACK 会打到当前前台
        //（2026-08-21 真机实测：到期时守卫/launcher 在前台被连发 3 次 BACK 退出）；
        // 活跃窗口已非豆包则跳过拦截（授权已收回，守卫页可见"已结束"）。
        prototypeAccessController.onExpired = {
            arbitrateExpiryInterception()
        }
        // M0.7 服务重连自愈：授权仍 Active 时按剩余额度补排到期任务。
        prototypeAccessController.ensureExpiryScheduled()
        // M0.6 屏幕事件：SCREEN_OFF 无法 manifest 注册，只能动态注册（主线程）；
        // 部分重复回调 onServiceConnected 的 ROM 需先注销防泄漏。
        if (screenEventReceiverRegistered) {
            unregisterReceiver(screenEventReceiver)
        }
        registerReceiver(screenEventReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        screenEventReceiverRegistered = true
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
                // M0.6 限时授权放行查询先行：授权激活期间（守卫入口"授权并启动"）
                // 豆包前台事件不进入拦截链路；豆包离开前台后授权立即失效。
                // 豆包窗口事件本身是前台的确定性证据，作废排队中的暂停复检
                //（2026-08-21 真机教训：启动过渡期排下的复检在激活后触发，
                // 误杀刚激活 251ms 的授权，豆包前台 50 秒额度纹丝不动）。
                cancelPendingPauseRecheck()
                cancelPendingExpiryRecheck()
                permitted = prototypeAccessController.onTargetForeground()
                if (permitted) {
                    startOrRefreshRemainingTimeOverlay()
                }
                val className = event.className?.toString()
                val targetDecision = if (permitted) {
                    // 放行期间不驱动拦截状态机（decision 为 null）。
                    null
                } else {
                    // 先识别 class 变化：抑制期内豆包 class 变化说明第一发 BACK 被用于
                    // 页面回退（如 MainActivity -> DrawerLayout），是精准补发第二发的信号。
                    val previousClassName = lastTargetClassName
                    if (previousClassName != null && className != previousClassName) {
                        val activeWindowState = currentActiveWindowTargetState()
                        if (!ActiveWindowTargetPolicy.allowsEventDrivenBackRefill(activeWindowState)) {
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
                    interceptionStateMachine.onTargetForeground(nowMs)
                }
                // 放行与拦截路径统一维护 class 记忆：M0.5 到期收回发生在豆包仍处于前台时，
                // 精准补发依赖"拦截前基线 -> 拦截后 class 变化"的连续性（2026-08-21 真机日志：
                // 不维护基线导致到期只发一发 BACK，退出失败）。
                // 必须放在上面的比较之后：若提前覆盖基线，className == lastTargetClassName
                // 恒成立，精准补发永远不触发。
                lastTargetClassName = className
                targetDecision
            }

            // 非目标前台事件：说明豆包已实际离开前台，结束当前抑制会话。
            // 仅窗口状态变化可靠携带包名；windows 变化常无包名，不作为重置信号。
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // M0.6 计时暂停仲裁（方案 A，2026-08-21 三轮真机迭代结论）：
                // ① 不校验 root：荣耀桌面后台刷新噪音把计时反复误冻结（81 秒只记 4 秒）；
                // ② root=null 一律忽略：canRetrieveWindowContent=false 时 root 恒为 null，
                //   整个会话 0 次暂停、到期 BACK 连发误退守卫；
                // ③ 最终：开启 canRetrieveWindowContent（仅读窗口包名，用户决策），
                //   root 非空可信（豆包=忽略噪音，非豆包=立即暂停），null 过渡态延迟复检，
                //   复检仍未解析按离开处理（宁严）；豆包前台事件作废排队中的复检。
                handleNonTargetWindowState(event.packageName?.toString().orEmpty(), nowMs)
                null
            }

            else -> null
        }

        if (decision == InterceptionDecision.Intercept) {
            performInterception(nowMs, getString(R.string.interception_message))
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
        if (screenEventReceiverRegistered) {
            unregisterReceiver(screenEventReceiver)
            screenEventReceiverRegistered = false
        }
        // 注销到期回调、取消计时任务，并撤销全部内存授权（M0.7，ROADMAP：
        // 服务断开后不恢复内存授权，重新进入豆包按未授权处理；开关无障碍 = 授权作废）。
        prototypeAccessController.clearOnExpired()
        prototypeAccessController.cancelScheduledExpiry()
        prototypeAccessController.revoke("守卫服务已断开")
        if (::interceptionOverlay.isInitialized) {
            interceptionOverlay.hide()
        }
        stopRemainingTimeOverlay()
        super.onDestroy()
    }

    /**
     * 执行一次拦截：按当前方案退出目标应用并记录诊断数据；提示延迟到会话结束显示。
     * [noticeMessage] 为延迟提示的文案（未授权拦截与额度到期收回各有专属提示）。
     */
    private fun performInterception(nowMs: Long, noticeMessage: String) {
        stopRemainingTimeOverlay()
        when (val exitAction = InterceptionActionPolicy.exitAction) {
            ExitAction.BACK -> performBackInterception(nowMs)
            ExitAction.HOME -> performHomeInterception(nowMs)
        }
        InterceptionDiagnostics.record(nowMs)
        pendingOverlayNoticeMessage = noticeMessage
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
        pendingOverlayNoticeMessage?.let { message ->
            pendingOverlayNoticeMessage = null
            if (ENABLE_OVERLAY_PROMPT) {
                Logger.d("显示拦截提示（延迟至会话结束）：elapsedRealtime=$nowMs")
                interceptionOverlay.show(message)
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
     * M0.6 暂停仲裁：非目标窗口事件到达时判定豆包是否真正离开前台。
     *
     * rootInActiveWindow 非空时可信：豆包 -> 忽略（桌面后台刷新噪音）；
     * 非豆包 -> 立即暂停。null（窗口切换瞬间的过渡态，恰是事件到达时刻）时
     * 延迟 [WINDOW_RECHECK_DELAY_MS] 复检，过渡态结束后重新判定；复检仍未解析
     * 则按离开处理（宁严方向，防计时穿透）。爆发式事件（真实切走约 1 秒内
     * 多发）通过 [pauseRecheckPending] 去重，只排队一次复检。
     */
    private fun handleNonTargetWindowState(eventPackage: String, nowMs: Long) {
        val activePackage = rootInActiveWindow?.packageName?.toString()
        when (ActiveWindowTargetPolicy.classify(activePackage, TargetApps.DOUBAO)) {
            ActiveWindowTargetState.Target ->
                Logger.d(
                    "忽略过渡窗口事件：豆包仍为活跃窗口 root=$activePackage " +
                        "package=$eventPackage elapsedRealtime=$nowMs",
                )

            ActiveWindowTargetState.Other -> confirmTargetLeft(activePackage, eventPackage, nowMs)

            ActiveWindowTargetState.Unknown -> {
                if (!pauseRecheckPending) {
                    pauseRecheckPending = true
                    Logger.d(
                        "暂停判定排队复检：活跃窗口过渡态 package=$eventPackage " +
                            "elapsedRealtime=$nowMs",
                    )
                    Runnable {
                        pauseRecheckPending = false
                        pauseRecheckRunnable = null
                        recheckPauseArbitration(eventPackage)
                    }.also { runnable ->
                        pauseRecheckRunnable = runnable
                        mainHandler.postDelayed(runnable, WINDOW_RECHECK_DELAY_MS)
                    }
                }
            }
        }
    }

    /** 豆包前台事件到达：作废排队中的暂停复检（陈旧复检会误杀刚激活/恢复的计时）。 */
    private fun cancelPendingPauseRecheck() {
        if (pauseRecheckPending) {
            pauseRecheckPending = false
            pauseRecheckRunnable?.let(mainHandler::removeCallbacks)
            pauseRecheckRunnable = null
            Logger.d("作废暂停复检：豆包前台事件到达 elapsedRealtime=${SystemClock.elapsedRealtime()}")
        }
    }

    /** 豆包窗口事件已提供直接证据并完成裁决，作废尚未执行的到期窗口复检。 */
    private fun cancelPendingExpiryRecheck() {
        expiryRecheckRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            expiryRecheckRunnable = null
            Logger.d("作废到期窗口复检：豆包窗口事件到达 elapsedRealtime=${SystemClock.elapsedRealtime()}")
        }
    }

    /** 复检：重新读取活跃窗口；仍未解析（罕见）按离开处理，宁严防计时穿透。 */
    private fun recheckPauseArbitration(eventPackage: String) {
        val nowMs = SystemClock.elapsedRealtime()
        val activePackage = rootInActiveWindow?.packageName?.toString()
        when (ActiveWindowTargetPolicy.classify(activePackage, TargetApps.DOUBAO)) {
            ActiveWindowTargetState.Target -> Logger.d(
                "复检忽略：豆包仍为活跃窗口 root=$activePackage package=$eventPackage " +
                    "elapsedRealtime=$nowMs",
            )

            // 非目标窗口事件复检后仍无法解析时沿用既有宁严策略：按已离开处理。
            ActiveWindowTargetState.Other,
            ActiveWindowTargetState.Unknown,
            -> confirmTargetLeft(activePackage, eventPackage, nowMs)
        }
    }

    /** 确认豆包离开后，计时暂停与拦截会话结束必须在同一路径完成。 */
    private fun confirmTargetLeft(activePackage: String?, eventPackage: String, nowMs: Long) {
        Logger.d(
            "豆包已离开前台：root=${activePackage ?: "未解析"} package=$eventPackage " +
                "elapsedRealtime=$nowMs",
        )
        stopRemainingTimeOverlay()
        prototypeAccessController.onTargetLeftForeground()
        if (interceptionStateMachine.isSuppressing) {
            finishInterceptionSession(activePackage ?: eventPackage, nowMs)
        } else {
            interceptionStateMachine.onOtherForeground()
        }
    }

    /** 到期回调的严格仲裁：Unknown 只复检一次，仍未知时跳过 BACK。 */
    private fun arbitrateExpiryInterception() {
        // 控制器已先转为 Revoked；无论当前前台判定结果如何，倒计时都必须立即消失。
        stopRemainingTimeOverlay()
        val nowMs = SystemClock.elapsedRealtime()
        when (val state = currentActiveWindowTargetState()) {
            ActiveWindowTargetState.Target -> performExpiryInterception(nowMs)
            ActiveWindowTargetState.Other -> logSkippedExpiryInterception(state, nowMs)
            ActiveWindowTargetState.Unknown -> {
                expiryRecheckRunnable?.let(mainHandler::removeCallbacks)
                Runnable {
                    expiryRecheckRunnable = null
                    val recheckNowMs = SystemClock.elapsedRealtime()
                    val recheckState = currentActiveWindowTargetState()
                    if (ActiveWindowTargetPolicy.allowsExpiryInterception(recheckState)) {
                        performExpiryInterception(recheckNowMs)
                    } else {
                        logSkippedExpiryInterception(recheckState, recheckNowMs)
                    }
                }.also { runnable ->
                    expiryRecheckRunnable = runnable
                    mainHandler.postDelayed(runnable, WINDOW_RECHECK_DELAY_MS)
                }
                Logger.d("到期时活跃窗口未解析：排队单次复检 elapsedRealtime=$nowMs")
            }
        }
    }

    private fun performExpiryInterception(nowMs: Long) {
        if (interceptionStateMachine.onTargetForeground(nowMs) == InterceptionDecision.Intercept) {
            performInterception(nowMs, getString(R.string.time_expired_message))
        }
    }

    private fun logSkippedExpiryInterception(state: ActiveWindowTargetState, nowMs: Long) {
        Logger.d(
            "到期时未明确确认豆包前台：跳过拦截 state=$state " +
                "active=${rootInActiveWindow?.packageName} elapsedRealtime=$nowMs",
        )
    }

    private fun currentActiveWindowTargetState(): ActiveWindowTargetState =
        ActiveWindowTargetPolicy.classify(rootInActiveWindow?.packageName, TargetApps.DOUBAO)

    /** 立即刷新一次并确保只有一个每秒任务在队列中。 */
    private fun startOrRefreshRemainingTimeOverlay() {
        val state = prototypeAccessController.state
        if (!RemainingTimeDisplayPolicy.shouldShow(state)) {
            stopRemainingTimeOverlay()
            return
        }
        remainingTimeOverlay.showOrUpdate(prototypeAccessController.remainingMs())
        if (remainingTimeRefreshRunnable == null) {
            Runnable {
                remainingTimeRefreshRunnable = null
                startOrRefreshRemainingTimeOverlay()
            }.also { runnable ->
                remainingTimeRefreshRunnable = runnable
                mainHandler.postDelayed(runnable, REMAINING_TIME_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopRemainingTimeOverlay() {
        remainingTimeRefreshRunnable?.let(mainHandler::removeCallbacks)
        remainingTimeRefreshRunnable = null
        if (::remainingTimeOverlay.isInitialized) {
            remainingTimeOverlay.hide()
        }
    }

    private companion object {
        // 与无障碍服务 XML 中声明的事件类型保持一致，避免处理无关的页面内容事件。
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )

        // M0.6 暂停仲裁的延迟复检间隔：窗口切换过渡态（rootInActiveWindow 为 null）
        // 通常在数百毫秒内结束；复检过早会仍在过渡态，过晚会延迟暂停结算。
        const val WINDOW_RECHECK_DELAY_MS = 300L

        const val REMAINING_TIME_REFRESH_INTERVAL_MS = 1_000L

        // M0.3-plus 拦截提示开关：覆盖层在拦截瞬间显示会引发死循环（其窗口事件在
        // 抑制期内被当作"豆包已离开"重置状态机），现改为延迟到会话结束（豆包真正
        // 离开前台、状态机已回 Idle）后显示。2026-08-22 用户要求重新启用验证。
        const val ENABLE_OVERLAY_PROMPT = true
    }
}
