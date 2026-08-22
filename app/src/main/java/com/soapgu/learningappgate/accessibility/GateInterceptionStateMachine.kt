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
 * 真机结论（M0.3-plus）：单次 BACK 常被豆包用于内部页面回退（如 MainActivity ->
 * DrawerLayout），需要第二发才能退出；覆盖层提示窗口若在拦截瞬间显示，其自身的
 * TYPE_WINDOW_STATE_CHANGED 会被当作非目标前台提前重置状态机（2026-08-21 时序日志
 * 证实），因此提示已改为延迟到会话结束（豆包真正离开前台）后显示。
 *
 * 精准补发：抑制期内"目标 class 变化"的窗口事件是第一发 BACK 被用于页面回退的
 * 明确信号，据此补发第二发（每会话上限 [MAX_BACK_REFILLS] 次，防冷启动启动序列
 * class 变化误触发连环补发）；超出上限或豆包持续未离开时由抑制超时兜底。
 *
 * 补发间隔下限（2026-08-22 真机结论）：真实页面回退耗时约 428ms，而窗口抖动产生的
 * class 变化对仅相隔 7-30ms；距上一发 BACK 不足 [MIN_BACK_INTERVAL_MS] 的 class 变化
 * 是抖动而非导航，不补发也不消耗配额。
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

    /** 本会话已精准补发的次数（新拦截或会话结束时重置）。 */
    var backRefillCount: Int = 0
        private set

    /** 最近一发 BACK（主拦截或补发）的单调时钟时间；用于补发间隔下限判定。 */
    var lastBackAtMs: Long = 0L
        private set

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

    /**
     * 抑制期内目标 class 变化（第一发 BACK 被豆包用于页面回退的信号）。
     *
     * 返回是否应补发第二发 BACK：仅 Intercepted 态有效，距上一发 BACK 不少于
     * [MIN_BACK_INTERVAL_MS]（真实页面回退的观测耗时），且每会话最多补发
     * [MAX_BACK_REFILLS] 次；间隔不足（窗口抖动）或 Idle 态不补发、不消耗配额。
     */
    fun onTargetClassChanged(nowMs: Long): Boolean {
        val current = state
        if (current !is GateInterceptionState.Intercepted) {
            return false
        }
        if (backRefillCount >= MAX_BACK_REFILLS) {
            return false
        }
        if (nowMs - lastBackAtMs < MIN_BACK_INTERVAL_MS) {
            return false
        }
        backRefillCount += 1
        lastBackAtMs = nowMs
        return true
    }

    /**
     * 当前 class 变化距离安全补发窗口还需等待多久。
     * null 表示当前会话/配额不允许补发，0 表示可以立即补发，正数表示应延迟复检。
     */
    fun backRefillDelayMs(nowMs: Long): Long? {
        if (state !is GateInterceptionState.Intercepted || backRefillCount >= MAX_BACK_REFILLS) {
            return null
        }
        return (MIN_BACK_INTERVAL_MS - (nowMs - lastBackAtMs)).coerceAtLeast(0L)
    }

    /** 非目标应用进入前台时调用；目标已实际离开，本会话结束，恢复可拦截状态。 */
    fun onOtherForeground() {
        state = GateInterceptionState.Idle
        backRefillCount = 0
    }

    val isSuppressing: Boolean
        get() = state is GateInterceptionState.Intercepted

    private fun intercept(nowMs: Long): InterceptionDecision {
        state = GateInterceptionState.Intercepted(nowMs)
        interceptionCount += 1
        lastInterceptionAtMs = nowMs
        lastBackAtMs = nowMs
        backRefillCount = 0
        return InterceptionDecision.Intercept
    }

    private companion object {
        const val DEFAULT_SUPPRESS_TIMEOUT_MS = 3_000L

        /** 每会话精准补发上限：正常页面回退仅需 1 次；多余余量防多层页面，超出靠超时兜底。 */
        const val MAX_BACK_REFILLS = 2

        /**
         * 补发间隔下限：真实页面回退（MainActivity -> DrawerLayout）实测约 428ms；
         * 7-30ms 的 class 变化是窗口抖动，间隔不足时不补发（2026-08-22 真机日志）。
         */
        const val MIN_BACK_INTERVAL_MS = 400L
    }
}

/** 状态机的内部状态。 */
private sealed interface GateInterceptionState {
    data object Idle : GateInterceptionState

    /** [atMs] 为本次拦截发生时的单调时钟时间。 */
    data class Intercepted(val atMs: Long) : GateInterceptionState
}
