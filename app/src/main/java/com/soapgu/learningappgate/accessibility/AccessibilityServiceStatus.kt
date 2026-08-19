package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

object AccessibilityServiceStatus {
    fun isEnabled(
        context: Context,
        serviceClass: Class<out AccessibilityService>,
    ): Boolean {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val expectedComponent = ComponentName(context, serviceClass)
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                val actualComponent = ComponentName(
                    serviceInfo.resolveInfo.serviceInfo.packageName,
                    serviceInfo.resolveInfo.serviceInfo.name,
                )
                actualComponent == expectedComponent
            }
    }
}

