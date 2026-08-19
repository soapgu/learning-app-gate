package com.soapgu.learningappgate.target

data class TargetApp(
    val packageName: String,
    val displayName: String,
)

object TargetApps {
    val DOUBAO = TargetApp(
        packageName = "com.larus.nova",
        displayName = "豆包",
    )
}

