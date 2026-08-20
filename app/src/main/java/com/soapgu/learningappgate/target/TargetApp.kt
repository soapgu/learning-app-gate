package com.soapgu.learningappgate.target

/** 守卫可识别和启动的目标应用定义。 */
data class TargetApp(
    val packageName: String,
    val displayName: String,
)

/** 集中维护目标应用，避免在界面、规则和无障碍服务中散落包名。 */
object TargetApps {
    // 已在 HONOR GT 真机上确认；启动 Activity 不固定，因此不在这里配置组件名。
    val DOUBAO = TargetApp(
        packageName = "com.larus.nova",
        displayName = "豆包",
    )
}
