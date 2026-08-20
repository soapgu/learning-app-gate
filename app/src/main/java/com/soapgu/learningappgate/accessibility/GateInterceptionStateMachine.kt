package com.soapgu.learningappgate.accessibility

/**
 * 拦截状态机对单个目标前台事件的裁决结果。
 */
sealed interface InterceptionDecision {
    /** 执行一次拦截：返回桌面并显示提示。 */
    data object Intercept : InterceptionDecision

    /** 忽略本次事件：同一次前台会话已拦截过，或事件属于拦截动作产生的级联。 */
    data object Ignore : InterceptionDecision
}

/**
 * M0.3 的未授权拦截状态机。
 *
 * 职责：决定豆包进入前台时是否需要执行新的拦截，防止 HOME/BACK 动作和窗口事件重复上报
 * 造成的无限重复拦截或重复提示。授权判断在 M0.4 引入，当前阶段所有目标前台均视为未授权。
 *
 * 状态说明：
 * - [GateInterceptionState.Idle]：未拦截状态，目标进入前台即拦截。
 * - [GateInterceptionState.Intercepted]：本会话已拦截，忽略后续目标事件；
 *   观察到非目标应用进入前台时回到 Idle（豆包已实际离开）。
 * - 惰性超时兜底：若 Intercepted 后迟迟等不到非目标前台事件（拦截动作未生效或事件丢失），
 *   超过 [suppressTimeoutMs] 后的下一个目标事件按新会话重新拦截。
 *
 * 真机结论（M0.3-plus）：BACK 退出豆包的过程中系统会产生非目标的过渡窗口事件提前重置
 * 本状态机，随后豆包回到前台的事件在 Idle 下触发新拦截、由下一次 BACK 补足退出--
 * 这不是误拦截，而是豆包需要两下返回才能退出的自然结果（计数 +2 为已接受边界）。
 * 曾尝试的离开去抖方案会吞掉豆包回前台事件导致退出失败，已真机验证并否决。
 *
 * 时钟由调用方传入（单调时钟），保证纯 JVM 可测。
 */
class GateInterceptionStateMachine(
    private val suppressTimeoutMs: Long = DEFAULT_SUPPRESS_TIMEOUT_MS,
) {
    var interceptionCount: Int = 0
        private set

    var lastInterceptionAtMs: Long = 0L
        private set

    private var state: GateInterceptionState = GateInterceptionState.Idle

    /** 目标应用进入前台时调用；返回本次事件的拦截裁决。 */
    fun onTargetForeground(nowMs: Long): InterceptionDecision {
        val current = state
        return when (current) {
            is GateInterceptionState.Idle -> intercept(nowMs)
            is GateInterceptionState.Intercepted -> {
                if (nowMs - current.atMs >= suppressTimeoutMs) {
                    // 抑制期内始终未观察到豆包离开前台，按拦截动作未生效或事件丢失兜底处理。
                    intercept(nowMs)
                } else {
                    InterceptionDecision.Ignore
                }
            }
        }
    }

    /** 非目标应用进入前台时调用；目标已实际离开，本会话结束，恢复可拦截状态。 */
    fun onOtherForeground() {
        state = GateInterceptionState.Idle
    }

    val isSuppressing: Boolean
        get() = state is GateInterceptionState.Intercepted

    private fun intercept(nowMs: Long): InterceptionDecision {
        state = GateInterceptionState.Intercepted(nowMs)
        interceptionCount += 1
        lastInterceptionAtMs = nowMs
        return InterceptionDecision.Intercept
    }

    private companion object {
        const val DEFAULT_SUPPRESS_TIMEOUT_MS = 3_000L
    }
}

/** 状态机的内部状态。 */
private sealed interface GateInterceptionState {
    data object Idle : GateInterceptionState

    /** [atMs] 为本次拦截发生时的单调时钟时间。 */
    data class Intercepted(val atMs: Long) : GateInterceptionState
}
