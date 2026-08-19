package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.target.TargetApp

object TargetAppEventMatcher {
    fun matches(packageName: CharSequence?, targetApp: TargetApp): Boolean {
        return packageName?.toString() == targetApp.packageName
    }
}

