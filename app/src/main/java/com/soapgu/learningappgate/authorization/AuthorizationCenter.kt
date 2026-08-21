package com.soapgu.learningappgate.authorization

import android.os.SystemClock
import com.orhanobut.logger.Logger

/**
 * M0.4 临时授权的进程内共享入口。
 *
 * 无障碍服务（事件驱动转换）与 MainActivity（创建授权、状态展示）同进程共享
 * 同一状态机实例；模式对齐 [com.soapgu.learningappgate.accessibility.InterceptionDiagnostics]。
 * M0.5 引入 PrototypeAccessController 后由控制器接管授权转换，此层届时收编。
 */
object AuthorizationCenter {

    private val stateMachine = LaunchAuthorizationStateMachine()

    /** 创建待生效授权（守卫入口点击"授权并启动"）；已有有效授权时返回 false。 */
    fun createPending(): Boolean = synchronized(stateMachine) {
        val nowMs = SystemClock.elapsedRealtime()
        val created = stateMachine.createPending(nowMs)
        Logger.d("授权状态：${if (created) "Idle/Revoked -> Pending" else "保持 ${stateMachine.state}（拒绝重复授权）"} elapsedRealtime=$nowMs")
        created
    }

    /** 豆包前台事件：返回守卫是否放行，并驱动 Pending->Active / 超时失效转换。 */
    fun onTargetForeground(): Boolean = synchronized(stateMachine) {
        val nowMs = SystemClock.elapsedRealtime()
        val before = stateMachine.state
        val permitted = stateMachine.onTargetForeground(nowMs)
        if (before != stateMachine.state || permitted) {
            Logger.d("授权状态：$before -> ${stateMachine.state} permitted=$permitted elapsedRealtime=$nowMs")
        }
        permitted
    }

    /** 豆包离开前台：单次会话语义下 Active -> Revoked。 */
    fun onTargetLeftForeground() = synchronized(stateMachine) {
        val nowMs = SystemClock.elapsedRealtime()
        val before = stateMachine.state
        stateMachine.onTargetLeftForeground(nowMs)
        if (before != stateMachine.state) {
            Logger.d("授权状态：$before -> ${stateMachine.state} elapsedRealtime=$nowMs")
        }
    }

    /** 撤销授权（启动失败等场景）。 */
    fun revoke(reason: String) = synchronized(stateMachine) {
        val nowMs = SystemClock.elapsedRealtime()
        val before = stateMachine.state
        stateMachine.revoke(reason)
        Logger.d("授权状态：$before -> ${stateMachine.state} elapsedRealtime=$nowMs")
    }

    /**
     * 当前授权状态快照。
     *
     * 读取时只做惰性超时判定（过期 Pending 显示为已失效），不做
     * Pending->Active 激活：激活必须由无障碍服务的豆包前台事件驱动，
     * 否则界面刷新会误消耗授权。
     */
    val state: LaunchAuthorizationState
        get() = synchronized(stateMachine) {
            stateMachine.expireIfPendingTimeout(SystemClock.elapsedRealtime())
            stateMachine.state
        }
}
