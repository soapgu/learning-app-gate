package com.soapgu.learningappgate.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** 查询指定无障碍服务是否已被用户在系统设置中启用。 */
object AccessibilityServiceStatus {
    fun isEnabled(
        context: Context,
        serviceClass: Class<out AccessibilityService>,
    ): Boolean {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val expectedComponent = ComponentName(context, serviceClass)
        // 使用公开的 AccessibilityManager 接口，不直接读取系统安全设置。
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
