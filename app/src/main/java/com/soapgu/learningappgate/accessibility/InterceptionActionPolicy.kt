package com.soapgu.learningappgate.accessibility

/**
 * 拦截退出动作的方案开关（M0.3-plus 验证阶段）。
 *
 * 进程内存态、不持久化：服务与主页同进程共享；进程或服务重启后回到默认 BACK 方案。
 * 验证通过后若 BACK 转正，此开关将随方案固化一并清理。
 */
enum class ExitAction {
    /** 两下 GLOBAL_ACTION_BACK，自然退出目标应用。 */
    BACK,

    /** GLOBAL_ACTION_HOME，直接回到桌面（M0.3 已验收方案，保留作对比与回归）。 */
    HOME,
}

object InterceptionActionPolicy {
    @Volatile
    var exitAction: ExitAction = ExitAction.BACK
}
