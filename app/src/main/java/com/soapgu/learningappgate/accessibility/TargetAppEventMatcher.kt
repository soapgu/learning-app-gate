package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.target.TargetApp

/**
 * 根据包名识别目标应用事件。
 *
 * 豆包从桌面、搜索、最近任务或外部链接进入时 Activity 可能不同，包名才是稳定的识别边界。
 */
object TargetAppEventMatcher {
    fun matches(packageName: CharSequence?, targetApp: TargetApp): Boolean {
        return packageName?.toString() == targetApp.packageName
    }
}
