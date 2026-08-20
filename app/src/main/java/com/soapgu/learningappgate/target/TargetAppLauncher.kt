package com.soapgu.learningappgate.target

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.orhanobut.logger.Logger

/** 目标应用启动入口的解析结果，用于区分未安装和已安装但不可启动。 */
sealed interface TargetAppResolution {
    /** 已解析出可启动的组件与启动 Intent；Intent 与组件来自同一次解析，避免二次查询产生竞态。 */
    data class Available(
        val componentName: ComponentName,
        val launchIntent: Intent,
    ) : TargetAppResolution

    data object NotInstalled : TargetAppResolution
    data object NoLaunchActivity : TargetAppResolution
}

/** 一次目标应用启动请求的结构化结果。 */
sealed interface TargetAppLaunchResult {
    data class Started(val componentName: ComponentName) : TargetAppLaunchResult
    data object NotInstalled : TargetAppLaunchResult
    data object NoLaunchActivity : TargetAppLaunchResult
    data class Failed(val reason: String) : TargetAppLaunchResult
}

/**
 * 通过系统 PackageManager 解析并启动目标应用。
 *
 * 不硬编码豆包的 Activity 别名，避免应用升级或渠道差异导致启动入口失效。
 */
class TargetAppLauncher(private val context: Context) {
    private val packageManager = context.packageManager

    fun resolve(targetApp: TargetApp): TargetAppResolution {
        if (!isInstalled(targetApp.packageName)) {
            Logger.d("未安装目标应用：${targetApp.packageName}")
            return TargetAppResolution.NotInstalled
        }

        // 一次解析同时产出组件名和启动 Intent，launch() 直接复用，避免两次查询之间目标被卸载或更新。
        val launchIntent = packageManager.getLaunchIntentForPackage(targetApp.packageName)
            ?: return TargetAppResolution.NoLaunchActivity.also {
                Logger.d("目标应用没有启动入口：${targetApp.packageName}")
            }
        val componentName = launchIntent.component
            ?: return TargetAppResolution.NoLaunchActivity.also {
                Logger.d("目标应用启动 Intent 没有组件：${targetApp.packageName}")
            }

        Logger.d("已解析目标应用：${targetApp.packageName}，组件：${componentName.flattenToShortString()}")
        return TargetAppResolution.Available(componentName, launchIntent)
    }

    fun launch(targetApp: TargetApp): TargetAppLaunchResult {
        return when (val resolution = resolve(targetApp)) {
            TargetAppResolution.NotInstalled -> TargetAppLaunchResult.NotInstalled
            TargetAppResolution.NoLaunchActivity -> TargetAppLaunchResult.NoLaunchActivity
            is TargetAppResolution.Available -> {
                try {
                    // 持有的是 Application Context，启动 Activity 时必须创建新的任务栈入口。
                    context.startActivity(resolution.launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Logger.d(
                        "已启动目标应用：${targetApp.packageName}，组件：" +
                            resolution.componentName.flattenToShortString(),
                    )
                    TargetAppLaunchResult.Started(resolution.componentName)
                } catch (error: ActivityNotFoundException) {
                    launchFailed(targetApp, error)
                } catch (error: SecurityException) {
                    launchFailed(targetApp, error)
                }
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            // Android 13 起使用类型安全的 Flags API，同时保留 minSdk 26 的兼容分支。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun launchFailed(targetApp: TargetApp, error: RuntimeException): TargetAppLaunchResult.Failed {
        val reason = error::class.java.simpleName
        Logger.d("启动目标应用失败：${targetApp.packageName}，原因：$reason")
        return TargetAppLaunchResult.Failed(reason)
    }
}
