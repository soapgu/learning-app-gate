package com.soapgu.learningappgate.accessibility

/**
 * M0.3 的进程内拦截诊断数据。
 *
 * 无障碍服务写入，主页在 onResume 时读取展示；同进程共享，无需 IPC。
 * M0.5 引入正式 GateStatus 后由控制器接管，此处仅做最小诊断展示。
 */
object InterceptionDiagnostics {
    @Volatile
    var interceptionCount: Int = 0
        private set

    /** 最近一次拦截的单调时钟时间；从未拦截时为 null。 */
    @Volatile
    var lastInterceptionAtMs: Long? = null
        private set

    fun record(nowMs: Long) {
        interceptionCount += 1
        lastInterceptionAtMs = nowMs
    }

    fun reset() {
        interceptionCount = 0
        lastInterceptionAtMs = null
    }
}
