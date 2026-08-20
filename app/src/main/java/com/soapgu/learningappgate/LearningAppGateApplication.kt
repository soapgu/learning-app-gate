package com.soapgu.learningappgate

import android.app.Application
import android.content.pm.ApplicationInfo
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import com.orhanobut.logger.PrettyFormatStrategy

/**
 * 应用级初始化入口，集中配置所有进程共享的基础组件。
 */
class LearningAppGateApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // M0 诊断日志只在 Debug 构建中输出，避免 Release 包泄露运行信息。
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }

        val formatStrategy = PrettyFormatStrategy.newBuilder()
            .showThreadInfo(true)
            .tag(LOG_TAG)
            .build()
        Logger.addLogAdapter(AndroidLogAdapter(formatStrategy))
    }

    private companion object {
        const val LOG_TAG = "LearningAppGate"
    }
}
