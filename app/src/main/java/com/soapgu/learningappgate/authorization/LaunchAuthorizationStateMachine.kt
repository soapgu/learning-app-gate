package com.soapgu.learningappgate.authorization

/**
 * M0.4/M0.5 临时授权的状态集合。
 *
 * 生命周期为单次会话：授权激活期间豆包在前台不被拦截；豆包一旦真正离开前台
 * （自然退出、被杀、切换到其他应用），授权立即失效，再进入豆包按无授权拦截。
 */
sealed interface LaunchAuthorizationState {
    /** 无授权：豆包前台事件将被守卫按 M0.3-plus 拦截。 */
    data object Idle : LaunchAuthorizationState

    /**
     * 待生效：守卫入口已创建授权并发出启动 Intent，等待豆包进入前台。
     * 超过 [LaunchAuthorizationStateMachine.PENDING_VALIDITY_MS] 未激活则失效。
     */
    data class Pending(val createdAtMs: Long, val totalMs: Long) : LaunchAuthorizationState

    /**
     * 已激活：豆包在前台，守卫放行；[totalMs] 为本档授权总额，
     * [accumulatedForegroundMs] 与 [segmentStartAtMs] 为 M0.6 暂停恢复预埋的
     * 累计前台时长与当前片段起点（M0.5 单片段，累计值恒为 0）。
     */
    data class Active(
        val activatedAtMs: Long,
        val totalMs: Long,
        val accumulatedForegroundMs: Long = 0L,
        val segmentStartAtMs: Long = activatedAtMs,
    ) : LaunchAuthorizationState {
        /** 剩余额度（毫秒）：结构上钳制为非负，杜绝负数时长。 */
        fun remainingMs(nowMs: Long): Long {
            val consumedMs = accumulatedForegroundMs + (nowMs - segmentStartAtMs)
            return (totalMs - consumedMs).coerceAtLeast(0L)
        }
    }

    /**
     * 暂停（M0.6）：计时阶段豆包离开前台时授权暂停、计时挂起。
     * M0.5 的单次会话语义不产生此状态（离开前台直接失效）。
     */
    data object Paused : LaunchAuthorizationState

    /** 已失效：携带失效原因，供界面展示与诊断日志使用。 */
    data class Revoked(val reason: String) : LaunchAuthorizationState
}

/**
 * M0.4/M0.5 临时授权状态机（纯逻辑，时钟由调用方传入，保证纯 JVM 可测）。
 *
 * 转换规则（单次会话）：
 * - Idle --createPending--> Pending：仅守卫入口可触发，携带授权总额；Pending/Active 期间拒绝重复创建；
 * - Pending --豆包前台(未超时)--> Active：放行并起算额度；
 * - Pending --超时/启动失败--> Revoked：惰性判定，不引入定时器；
 * - Active --额度耗尽--> Revoked：定时器驱动（控制器）或前台事件惰性兜底；
 * - Active --豆包离开前台--> Revoked：授权单次会话结束，再进豆包即拦截；
 * - 任意态 --revoke--> Revoked。
 *
 * Pending 有效期取 5 秒的依据：真机时序（2026-08-21 日志）显示守卫
 * startActivity 到豆包 MainActivity 前台事件约 1.8 秒（含荣耀 systemmanager
 * 开屏广告约 1.2 秒的跨应用启动干扰），5 秒留有充足余量。
 */
class LaunchAuthorizationStateMachine {

    /** 当前状态；由调用方保证同一线程访问或外部同步。 */
    var state: LaunchAuthorizationState = LaunchAuthorizationState.Idle
        private set

    /**
     * 创建待生效授权（守卫入口点击"授权并启动"）。
     *
     * 先做过期判定：已超时但尚未被事件惰性失效的 Pending 视同 Revoked，
     * 允许重新授权（否则豆包启动失败后用户停留在守卫页会被"进行中"卡住）。
     *
     * @return 是否创建成功；已有未过期 Pending/Active 授权期间返回 false（拒绝重复授权）。
     */
    fun createPending(nowMs: Long, totalMs: Long): Boolean {
        require(totalMs > 0) { "授权总额必须为正数：totalMs=$totalMs" }
        expireIfPendingTimeout(nowMs)
        return when (state) {
            LaunchAuthorizationState.Idle, is LaunchAuthorizationState.Revoked -> {
                state = LaunchAuthorizationState.Pending(nowMs, totalMs)
                true
            }

            is LaunchAuthorizationState.Pending, is LaunchAuthorizationState.Active -> false

            // M0.5 不产生 Paused；出现即编程错误，按拒绝处理并保持现状。
            LaunchAuthorizationState.Paused -> false
        }
    }

    /**
     * 豆包前台事件到达时调用；返回守卫是否放行。
     *
     * Pending 未超时 -> 激活为 Active 并放行；Active 且额度未尽 -> 放行；
     * Pending 已超时 / Active 额度耗尽 -> 惰性转 Revoked 并拒绝（定时器丢失时的
     * 兜底，防额度穿透）；Idle/Revoked/Paused -> 拒绝。
     */
    fun onTargetForeground(nowMs: Long): Boolean {
        return when (val current = state) {
            is LaunchAuthorizationState.Pending -> {
                if (nowMs - current.createdAtMs > PENDING_VALIDITY_MS) {
                    state = LaunchAuthorizationState.Revoked("授权等待超时：豆包未在 ${PENDING_VALIDITY_MS / 1000} 秒内进入前台")
                    false
                } else {
                    state = LaunchAuthorizationState.Active(nowMs, current.totalMs)
                    true
                }
            }

            is LaunchAuthorizationState.Active -> {
                if (current.remainingMs(nowMs) <= 0L) {
                    state = LaunchAuthorizationState.Revoked(TIME_EXPIRED_REASON)
                    false
                } else {
                    true
                }
            }

            LaunchAuthorizationState.Idle, is LaunchAuthorizationState.Revoked,
            LaunchAuthorizationState.Paused,
            -> false
        }
    }

    /**
     * 豆包离开前台时调用（非目标前台窗口事件）。
     *
     * 单次会话语义：Active -> Revoked（本次授权结束）；
     * Pending（启动过渡期，如荣耀广告页占前台）与其他状态不做处理。
     */
    fun onTargetLeftForeground(nowMs: Long) {
        if (state is LaunchAuthorizationState.Active) {
            state = LaunchAuthorizationState.Revoked("豆包已离开前台，本次授权结束")
        }
    }

    /** 撤销授权：任意状态 -> Revoked（启动失败、验收诊断等场景）。 */
    fun revoke(reason: String) {
        state = LaunchAuthorizationState.Revoked(reason)
    }

    /**
     * 惰性超时判定：Pending 已过期则转 Revoked，其余状态不做处理。
     *
     * 与 [onTargetForeground] 的区别：本方法只失效、不激活，供界面读取
     * 状态快照时使用（界面刷新不得把未过期的 Pending 消耗成 Active）。
     */
    fun expireIfPendingTimeout(nowMs: Long) {
        val current = state
        if (current is LaunchAuthorizationState.Pending &&
            nowMs - current.createdAtMs > PENDING_VALIDITY_MS
        ) {
            state = LaunchAuthorizationState.Revoked("授权等待超时：豆包未在 ${PENDING_VALIDITY_MS / 1000} 秒内进入前台")
        }
    }

    companion object {
        /** Pending 有效期（毫秒）：超过后豆包前台事件按无授权拦截。 */
        const val PENDING_VALIDITY_MS = 5_000L

        /** 额度耗尽的失效原因；定时器收回与前台事件惰性兜底共用，保证终态一致。 */
        const val TIME_EXPIRED_REASON = "本次使用时间已结束"
    }
}
