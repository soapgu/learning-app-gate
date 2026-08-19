package com.soapgu.learningappgate.target

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

sealed interface TargetAppResolution {
    data class Available(val componentName: ComponentName) : TargetAppResolution
    data object NotInstalled : TargetAppResolution
    data object NoLaunchActivity : TargetAppResolution
}

sealed interface TargetAppLaunchResult {
    data class Started(val componentName: ComponentName) : TargetAppLaunchResult
    data object NotInstalled : TargetAppLaunchResult
    data object NoLaunchActivity : TargetAppLaunchResult
    data class Failed(val reason: String) : TargetAppLaunchResult
}

class TargetAppLauncher(private val context: Context) {
    private val packageManager = context.packageManager

    fun resolve(targetApp: TargetApp): TargetAppResolution {
        if (!isInstalled(targetApp.packageName)) {
            log("未安装目标应用：${targetApp.packageName}")
            return TargetAppResolution.NotInstalled
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetApp.packageName)
            ?: return TargetAppResolution.NoLaunchActivity.also {
                log("目标应用没有启动入口：${targetApp.packageName}")
            }
        val componentName = launchIntent.component
            ?: return TargetAppResolution.NoLaunchActivity.also {
                log("目标应用启动 Intent 没有组件：${targetApp.packageName}")
            }

        log("已解析目标应用：${targetApp.packageName}，组件：${componentName.flattenToShortString()}")
        return TargetAppResolution.Available(componentName)
    }

    fun launch(targetApp: TargetApp): TargetAppLaunchResult {
        return when (val resolution = resolve(targetApp)) {
            TargetAppResolution.NotInstalled -> TargetAppLaunchResult.NotInstalled
            TargetAppResolution.NoLaunchActivity -> TargetAppLaunchResult.NoLaunchActivity
            is TargetAppResolution.Available -> {
                val intent = packageManager.getLaunchIntentForPackage(targetApp.packageName)
                    ?: return TargetAppLaunchResult.NoLaunchActivity
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    log(
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
        log("启动目标应用失败：${targetApp.packageName}，原因：$reason")
        return TargetAppLaunchResult.Failed(reason)
    }

    private fun log(message: String) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TAG, message)
        }
    }

    private companion object {
        const val TAG = "TargetAppLauncher"
    }
}

