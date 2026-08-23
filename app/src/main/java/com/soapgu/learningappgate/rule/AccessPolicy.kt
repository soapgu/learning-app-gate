package com.soapgu.learningappgate.rule

import com.soapgu.learningappgate.target.TargetApp
import com.soapgu.learningappgate.target.TargetApps
import java.time.LocalTime

/** 每天重复生效的单一允许时段；开始边界包含，结束边界排除。 */
data class DailyTimeWindow(
    val startInclusive: LocalTime,
    val endExclusive: LocalTime,
) {
    init {
        require(startInclusive != endExclusive) { "每日允许时段的开始与结束时间不能相同" }
    }
}

/** 目标应用及其访问时间规则。 */
data class AccessPolicy(
    val targetApp: TargetApp,
    val timeWindow: DailyTimeWindow,
) {
    companion object {
        /** M1 默认规则：豆包每天 07:20（含）至 20:30（不含）可访问。 */
        val DEFAULT = AccessPolicy(
            targetApp = TargetApps.DOUBAO,
            timeWindow = DailyTimeWindow(
                startInclusive = LocalTime.of(7, 20),
                endExclusive = LocalTime.of(20, 30),
            ),
        )
    }
}
