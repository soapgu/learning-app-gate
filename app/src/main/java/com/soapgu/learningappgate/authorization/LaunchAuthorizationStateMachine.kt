package com.soapgu.learningappgate.authorization

/**
 * M0.4/M0.5/M0.6 临时授权的状态集合。
 *
 * M0.6 起生命周期为"额度制"：授权激活期间豆包在前台不被拦截；豆包离开前台或
 * 关屏时转为 [Paused] 并挂起计时（不消耗额度），重新进入前台从剩余额度继续；
 * 额度耗尽、启动失败或暂停期被重置时转为 [Revoked]，再进入豆包按无授权拦截。
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
     * [accumulatedForegroundMs] 为已结算的累计前台时长，[segmentStartAtMs] 为
     * 当前片段起点（恢复计时即开启新片段）。
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
     * 已暂停（M0.6）：豆包离开前台或关屏时结算当前片段并挂起计时，
     * 重新进入前台从剩余额度继续。暂停无有效期（2026-08-21 用户决策）；
     * 暂停期再次创建授权 = 重置（作废旧额度）。
     */
    data class Paused(
        val totalMs: Long,
        val accumulatedForegroundMs: Long,
        val pausedAtMs: Long,
    ) : LaunchAuthorizationState {
        /** 剩余额度（毫秒）：暂停期间不消耗，结构上非负。 */
        fun remainingMs(): Long = (totalMs - accumulatedForegroundMs).coerceAtLeast(0L)
    }

    /** 已失效：携带失效原因，供界面展示与诊断日志使用。 */
    data class Revoked(val reason: String) : LaunchAuthorizationState
}

/**
 * M0.4/M0.5/M0.6 临时授权状态机（纯逻辑，时钟由调用方传入，保证纯 JVM 可测）。
 *
 * 转换规则（额度制）：
 * - Idle/Paused --createPending--> Pending：仅守卫入口可触发，携带授权总额；
 *   Paused 期创建 = 重置（作废旧额度，2026-08-21 用户决策）；
 *   Pending/Active 期间拒绝重复创建；
 * - Pending --豆包前台(未超时)--> Active：放行并起算额度；
 * - Pending --超时/启动失败--> Revoked：惰性判定，不引入定时器；
 * - Active --额度耗尽--> Revoked：定时器驱动（控制器）或前台事件惰性兜底；
 * - Active --豆包离开前台/关屏--> Paused：结算当前片段并挂起计时；
 * - Paused --豆包重新前台(剩余>0)--> Active：从剩余额度继续（新片段）；
 * - Paused --重新前台(剩余<=0)--> Revoked：额度恰在切出瞬间耗尽的边界；
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
     * @return 是否创建成功；Paused 态创建 = 重置旧授权并返回 true；
     *   已有未过期 Pending/Active 授权期间返回 false（拒绝重复授权）。
     */
    fun createPending(nowMs: Long, totalMs: Long): Boolean {
        require(totalMs > 0) { "授权总额必须为正数：totalMs=$totalMs" }
        expireIfPendingTimeout(nowMs)
        return when (state) {
            LaunchAuthorizationState.Idle, is LaunchAuthorizationState.Revoked -> {
                state = LaunchAuthorizationState.Pending(nowMs, totalMs)
                true
            }

            // 暂停期重置：守卫入口拥有最高控制权，旧额度直接作废（用户决策）。
            is LaunchAuthorizationState.Paused -> {
                state = LaunchAuthorizationState.Pending(nowMs, totalMs)
                true
            }

            is LaunchAuthorizationState.Pending, is LaunchAuthorizationState.Active -> false
        }
    }

    /**
     * 豆包前台事件到达时调用；返回守卫是否放行。
     *
     * Pending 未超时 -> 激活为 Active 并放行；Active 且额度未尽 -> 放行；
     * Paused 且剩余 > 0 -> 恢复为 Active（新片段）并放行；
     * Pending 已超时 / Active 额度耗尽 / Paused 剩余耗尽 -> 惰性转 Revoked 并
     * 拒绝（定时器丢失时的兜底，防额度穿透）；Idle/Revoked -> 拒绝。
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

            is LaunchAuthorizationState.Paused -> {
                if (current.remainingMs() > 0L) {
                    state = LaunchAuthorizationState.Active(
                        activatedAtMs = nowMs,
                        totalMs = current.totalMs,
                        accumulatedForegroundMs = current.accumulatedForegroundMs,
                        segmentStartAtMs = nowMs,
                    )
                    true
                } else {
                    state = LaunchAuthorizationState.Revoked(TIME_EXPIRED_REASON)
                    false
                }
            }

            LaunchAuthorizationState.Idle, is LaunchAuthorizationState.Revoked -> false
        }
    }

    /**
     * 豆包离开前台时调用（非目标前台窗口事件）。
     *
     * Active -> Paused：结算当前片段并挂起计时（额度制，M0.6）；
     * Pending（启动过渡期，如荣耀广告页占前台）与其他状态不做处理。
     */
    fun onTargetLeftForeground(nowMs: Long) {
        pauseIfActive(nowMs)
    }

    /**
     * 屏幕关闭时调用（SCREEN_OFF 广播）：豆包可能仍在前台但不再有窗口事件，
     * 计时必须立即挂起，否则关屏期间额度持续消耗。
     * Active -> Paused（结算当前片段）；其余状态不做处理。
     */
    fun onScreenOff(nowMs: Long) {
        pauseIfActive(nowMs)
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

    private fun pauseIfActive(nowMs: Long) {
        val current = state
        if (current is LaunchAuthorizationState.Active) {
            val accumulated = current.accumulatedForegroundMs + (nowMs - current.segmentStartAtMs)
            state = LaunchAuthorizationState.Paused(
                totalMs = current.totalMs,
                accumulatedForegroundMs = accumulated.coerceAtMost(current.totalMs),
                pausedAtMs = nowMs,
            )
        }
    }

    companion object {
        /** Pending 有效期（毫秒）：超过后豆包前台事件按无授权拦截。 */
        const val PENDING_VALIDITY_MS = 5_000L

        /** 额度耗尽的失效原因；定时器收回与前台事件惰性兜底共用，保证终态一致。 */
        const val TIME_EXPIRED_REASON = "本次使用时间已结束"
    }
}
