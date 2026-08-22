package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.target.TargetApp

/** 活跃窗口与目标应用的三态关系；Unknown 仅表示系统暂时无法提供窗口根节点。 */
enum class ActiveWindowTargetState {
    Target,
    Other,
    Unknown,
}

/**
 * 集中定义不同拦截场景对活跃窗口三态的处理，避免把“事件证据”和“定时器回调”混为一谈。
 */
object ActiveWindowTargetPolicy {
    fun classify(activePackage: CharSequence?, targetApp: TargetApp): ActiveWindowTargetState {
        return when {
            activePackage == null -> ActiveWindowTargetState.Unknown
            TargetAppEventMatcher.matches(activePackage, targetApp) -> ActiveWindowTargetState.Target
            else -> ActiveWindowTargetState.Other
        }
    }

    /** 豆包 class 变化事件是目标仍存活的直接证据，窗口未知时仍允许精准补发。 */
    fun allowsEventDrivenBackRefill(state: ActiveWindowTargetState): Boolean =
        state != ActiveWindowTargetState.Other

    /** 到期回调没有窗口事件作证，只有明确确认豆包前台时才允许发送 BACK。 */
    fun allowsExpiryInterception(state: ActiveWindowTargetState): Boolean =
        state == ActiveWindowTargetState.Target
}
