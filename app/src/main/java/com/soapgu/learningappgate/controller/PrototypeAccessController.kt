package com.soapgu.learningappgate.controller

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.orhanobut.logger.Logger
import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import com.soapgu.learningappgate.authorization.LaunchAuthorizationStateMachine

/**
 * 计时调度抽象：控制器只依赖此接口，纯 JVM 测试注入 fake 调度器。
 */
interface AccessScheduler {
    /** 调度一次延时任务；新任务会替换尚未执行的旧任务（同一路径只保留一个到期任务）。 */
    fun postDelayed(delayMs: Long, action: () -> Unit)

    /** 取消尚未执行的到期任务。 */
    fun cancel()
}

/** 生产实现：主线程 Handler（与无障碍事件回调、UI 点击同线程串行）。 */
class HandlerAccessScheduler(private val handler: Handler) : AccessScheduler {
    private val lock = Any()
    private var pendingRunnable: Runnable? = null

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        synchronized(lock) {
            cancelLocked()
            Runnable { action() }.also { runnable ->
                pendingRunnable = runnable
                handler.postDelayed(runnable, delayMs)
            }
        }
    }

    override fun cancel() {
        synchronized(lock) {
            cancelLocked()
        }
    }

    private fun cancelLocked() {
        pendingRunnable?.let(handler::removeCallbacks)
        pendingRunnable = null
    }
}

/**
 * M0.5/M0.6 前台计时与实时收回控制器：串行处理授权转换、到期计时与暂停恢复，
 * 无障碍服务只上报前后台/屏幕事件并执行拦截动作（服务注册 [onExpired] 回调）。
 *
 * 到期流程（ROADMAP M0.5）：定时器触发时重算实际剩余时间；仍有剩余则
 * 重新调度（防定时器早触发/时钟漂移误收回），归零则先原子化转 Revoked，
 * 再在锁外触发 [onExpired]（BACK 拦截与提示由服务执行，避免锁内做系统动作）。
 *
 * 暂停恢复（ROADMAP M0.6）：豆包离开前台/关屏 -> Paused（结算片段、取消到期
 * 任务，额度冻结）；豆包重新前台 -> 按剩余额度恢复计时。暂停无有效期；
 * 暂停期 createPending = 重置（2026-08-21 用户决策）。
 *
 * 时钟与调度器由构造注入；生产环境使用 [prototypeAccessController] 单例。
 */
class PrototypeAccessController(
    private val clock: () -> Long,
    private val scheduler: AccessScheduler,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val stateMachine = LaunchAuthorizationStateMachine()

    /**
     * 额度到期回调（服务注册）：触发时授权已原子化转为 Revoked；
     * 在锁外的主线程执行，实现里可安全调用 performGlobalAction 与覆盖层。
     */
    @Volatile
    var onExpired: (() -> Unit)? = null

    /** 创建待生效授权（守卫入口点击限时"授权并启动"）；已有 Pending/Active 授权时返回 false，Paused 态为重置（作废旧额度）。 */
    fun createPending(totalMs: Long): Boolean = synchronized(lock) {
        val nowMs = clock()
        val before = stateMachine.state
        val created = stateMachine.createPending(nowMs, totalMs)
        log(
            when {
                created && before is LaunchAuthorizationState.Paused ->
                    "授权状态：$before -> Pending(totalMs=$totalMs)（重置，作废旧额度）"

                created -> "授权状态：Idle/Revoked -> Pending(totalMs=$totalMs)"
                else -> "授权状态：保持 ${stateMachine.state}（拒绝重复授权）"
            } + " elapsedRealtime=$nowMs",
        )
        created
    }

    /** 豆包前台事件：返回守卫是否放行；激活/恢复即按剩余额度起表，额度耗尽时惰性收回。 */
    fun onTargetForeground(): Boolean = synchronized(lock) {
        val nowMs = clock()
        val before = stateMachine.state
        val permitted = stateMachine.onTargetForeground(nowMs)
        val after = stateMachine.state
        when {
            // 激活（Pending -> Active）或恢复（Paused -> Active）即按剩余额度调度一次到期任务。
            after is LaunchAuthorizationState.Active &&
                (before is LaunchAuthorizationState.Pending || before is LaunchAuthorizationState.Paused) -> {
                scheduler.postDelayed(after.remainingMs(nowMs), ::handleExpiry)
            }

            // 未处于 Active（含惰性兜底收回）：确保无残留到期任务。
            after !is LaunchAuthorizationState.Active -> scheduler.cancel()
        }
        if (before != after || permitted) {
            log("授权状态：$before -> $after permitted=$permitted elapsedRealtime=$nowMs")
        }
        permitted
    }

    /** 豆包离开前台：结算当前片段并挂起计时（Active -> Paused），取消到期任务。 */
    fun onTargetLeftForeground() = pauseAuthorization("离开前台") { nowMs ->
        stateMachine.onTargetLeftForeground(nowMs)
    }

    /** 屏幕关闭（SCREEN_OFF 广播）：豆包可能仍在前台但不再有窗口事件，立即挂起计时。 */
    fun onScreenOff() = pauseAuthorization("关屏") { nowMs ->
        stateMachine.onScreenOff(nowMs)
    }

    /** 撤销授权（启动失败等场景）：取消到期任务。 */
    fun revoke(reason: String) = synchronized(lock) {
        val nowMs = clock()
        val before = stateMachine.state
        scheduler.cancel()
        stateMachine.revoke(reason)
        log("授权状态：$before -> ${stateMachine.state} elapsedRealtime=$nowMs")
    }

    /** 取消计时任务（服务销毁等场景）；不改变授权状态。 */
    fun cancelScheduledExpiry() = synchronized(lock) {
        scheduler.cancel()
    }

    /**
     * 服务重连自愈（M0.7）：状态为 Active 时按当前剩余额度（重）排一次到期任务。
     *
     * 覆盖"计时曾被取消而授权仍 Active"的罕见路径（如服务销毁取消计时后 ROM
     * 未真正杀死进程又重连），防额度永不到期。调度器的新任务会替换旧任务，
     * 天然不产生重复到期；到期触发时 [handleExpiry] 仍会重算实际剩余。
     * 非 Active 状态 no-op。
     */
    fun ensureExpiryScheduled() = synchronized(lock) {
        val nowMs = clock()
        val active = stateMachine.state as? LaunchAuthorizationState.Active ?: return
        val remainingMs = active.remainingMs(nowMs)
        scheduler.postDelayed(remainingMs, ::handleExpiry)
        log("服务重连自愈：补排到期任务 remainingMs=$remainingMs elapsedRealtime=$nowMs")
    }

    /** 清除到期回调（服务销毁时注销，防泄漏）。 */
    fun clearOnExpired() {
        onExpired = null
    }

    /**
     * 当前授权状态快照。读取时只做惰性超时判定，不做 Pending->Active 激活：
     * 激活必须由无障碍服务的豆包前台事件驱动，否则界面刷新会误消耗授权。
     */
    val state: LaunchAuthorizationState
        get() = synchronized(lock) {
            stateMachine.expireIfPendingTimeout(clock())
            stateMachine.state
        }

    /** 剩余额度（毫秒）；Active 按当前片段实时扣减，Paused 返回冻结值；其余态恒为 0。 */
    fun remainingMs(): Long = synchronized(lock) {
        val nowMs = clock()
        stateMachine.expireIfPendingTimeout(nowMs)
        when (val current = stateMachine.state) {
            is LaunchAuthorizationState.Active -> current.remainingMs(nowMs)
            is LaunchAuthorizationState.Paused -> current.remainingMs()
            else -> 0L
        }
    }

    /** 暂停共用逻辑：取消到期任务、驱动状态机转换、记录结算与状态变化日志。 */
    private fun pauseAuthorization(cause: String, transition: (Long) -> Unit) {
        synchronized(lock) {
            val nowMs = clock()
            val before = stateMachine.state
            if (before is LaunchAuthorizationState.Active) {
                scheduler.cancel()
            }
            transition(nowMs)
            val after = stateMachine.state
            if (after is LaunchAuthorizationState.Paused) {
                log(
                    "授权暂停（$cause）：consumedForegroundMs=${after.accumulatedForegroundMs} " +
                        "remainingMs=${after.remainingMs()} elapsedRealtime=$nowMs",
                )
            }
            if (before != after) {
                log("授权状态：$before -> $after elapsedRealtime=$nowMs")
            }
        }
    }

    /**
     * 到期任务：锁内重算剩余并做幂等收回（重复触发/旧任务迟到不重复处理），
     * 回调动作在锁外执行。
     */
    private fun handleExpiry() {
        val expiredCallback: (() -> Unit)? = synchronized(lock) {
            val nowMs = clock()
            val current = stateMachine.state
            if (current !is LaunchAuthorizationState.Active) {
                // 已被离开前台/撤销/惰性兜底收回，或旧任务迟到：幂等，无事可做。
                log("到期任务忽略：当前状态 ${stateMachine.state} elapsedRealtime=$nowMs")
                null
            } else {
                val remainingMs = current.remainingMs(nowMs)
                if (remainingMs > 0L) {
                    // 定时器早触发或时钟漂移：按实际剩余重新调度，不误收回。
                    log("到期任务重算：剩余 $remainingMs ms，重新调度 elapsedRealtime=$nowMs")
                    scheduler.postDelayed(remainingMs, ::handleExpiry)
                    null
                } else {
                    stateMachine.revoke(LaunchAuthorizationStateMachine.TIME_EXPIRED_REASON)
                    log("授权到期：$current -> ${stateMachine.state} elapsedRealtime=$nowMs")
                    onExpired
                }
            }
        }
        expiredCallback?.invoke()
    }
}

/** 生产单例：单调时钟 + 主线程 Handler 调度。 */
val prototypeAccessController: PrototypeAccessController by lazy {
    PrototypeAccessController(
        clock = SystemClock::elapsedRealtime,
        scheduler = HandlerAccessScheduler(Handler(Looper.getMainLooper())),
        log = { message -> Logger.d(message) },
    )
}
