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
 * M0.5 前台计时与实时收回控制器：串行处理授权转换与到期计时，
 * 无障碍服务只上报前后台事件并执行拦截动作（服务注册 [onExpired] 回调）。
 *
 * 到期流程（ROADMAP M0.5）：定时器触发时重算实际剩余时间；仍有剩余则
 * 重新调度（防定时器早触发/时钟漂移误收回），归零则先原子化转 Revoked，
 * 再在锁外触发 [onExpired]（BACK 拦截与提示由服务执行，避免锁内做系统动作）。
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

    /** 创建待生效授权（守卫入口点击限时"授权并启动"）；已有有效授权时返回 false。 */
    fun createPending(totalMs: Long): Boolean = synchronized(lock) {
        val nowMs = clock()
        val created = stateMachine.createPending(nowMs, totalMs)
        log(
            "授权状态：${if (created) "Idle/Revoked -> Pending(totalMs=$totalMs)" else "保持 ${stateMachine.state}（拒绝重复授权）"} elapsedRealtime=$nowMs",
        )
        created
    }

    /** 豆包前台事件：返回守卫是否放行；激活起表，额度耗尽时惰性收回。 */
    fun onTargetForeground(): Boolean = synchronized(lock) {
        val nowMs = clock()
        val before = stateMachine.state
        val permitted = stateMachine.onTargetForeground(nowMs)
        when {
            before is LaunchAuthorizationState.Pending &&
                stateMachine.state is LaunchAuthorizationState.Active -> {
                // 激活即起表：按剩余额度调度一次到期任务。
                val remainingMs = (stateMachine.state as LaunchAuthorizationState.Active).remainingMs(nowMs)
                scheduler.postDelayed(remainingMs, ::handleExpiry)
            }

            before is LaunchAuthorizationState.Active && !permitted -> {
                // 前台事件惰性兜底收回（定时器丢失场景）：清掉可能残留的到期任务。
                scheduler.cancel()
            }
        }
        if (before != stateMachine.state || permitted) {
            log("授权状态：$before -> ${stateMachine.state} permitted=$permitted elapsedRealtime=$nowMs")
        }
        permitted
    }

    /** 豆包离开前台：结算当前片段（诊断日志）并结束单次会话授权，取消到期任务。 */
    fun onTargetLeftForeground() = synchronized(lock) {
        val nowMs = clock()
        val before = stateMachine.state
        if (before is LaunchAuthorizationState.Active) {
            val consumedMs = before.accumulatedForegroundMs + (nowMs - before.segmentStartAtMs)
            log(
                "授权片段结算：consumedForegroundMs=$consumedMs " +
                    "remainingMs=${before.remainingMs(nowMs)} elapsedRealtime=$nowMs",
            )
            scheduler.cancel()
        }
        stateMachine.onTargetLeftForeground(nowMs)
        if (before != stateMachine.state) {
            log("授权状态：$before -> ${stateMachine.state} elapsedRealtime=$nowMs")
        }
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

    /** 剩余额度（毫秒）；非 Active 态恒为 0，结构上非负。 */
    fun remainingMs(): Long = synchronized(lock) {
        val nowMs = clock()
        stateMachine.expireIfPendingTimeout(nowMs)
        (stateMachine.state as? LaunchAuthorizationState.Active)?.remainingMs(nowMs) ?: 0L
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
