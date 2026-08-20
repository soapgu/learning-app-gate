package com.soapgu.learningappgate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.soapgu.learningappgate.accessibility.AccessibilityServiceStatus
import com.soapgu.learningappgate.accessibility.GateAccessibilityService
import com.soapgu.learningappgate.target.TargetApp
import com.soapgu.learningappgate.target.TargetAppLaunchResult
import com.soapgu.learningappgate.target.TargetAppLauncher
import com.soapgu.learningappgate.target.TargetAppResolution
import com.soapgu.learningappgate.target.TargetApps
import com.soapgu.learningappgate.ui.theme.LearningAppGateTheme

/**
 * M0 阶段的诊断主页。
 *
 * 当前页面只展示目标应用和无障碍服务状态，并提供启动目标应用、打开系统设置的入口；
 * 授权、计时和拦截逻辑会在后续里程碑中实现。
 */
class MainActivity : ComponentActivity() {
    private lateinit var targetAppLauncher: TargetAppLauncher
    private var resolution by mutableStateOf<TargetAppResolution>(TargetAppResolution.NotInstalled)
    private var launchMessage by mutableStateOf<String?>(null)
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetAppLauncher = TargetAppLauncher(applicationContext)
        refreshStatus()
        enableEdgeToEdge()
        setContent {
            LearningAppGateTheme {
                MainScreen(
                    targetApp = TargetApps.DOUBAO,
                    resolution = resolution,
                    accessibilityEnabled = accessibilityEnabled,
                    launchMessage = launchMessage,
                    onLaunch = ::launchTargetApp,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置返回，或者在外部安装/卸载了目标应用，因此每次恢复都重新查询。
        if (::targetAppLauncher.isInitialized) {
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        resolution = targetAppLauncher.resolve(TargetApps.DOUBAO)
        accessibilityEnabled = AccessibilityServiceStatus.isEnabled(
            context = this,
            serviceClass = GateAccessibilityService::class.java,
        )
    }

    private fun launchTargetApp() {
        launchMessage = when (val result = targetAppLauncher.launch(TargetApps.DOUBAO)) {
            is TargetAppLaunchResult.Started -> getString(
                R.string.launch_succeeded,
                result.componentName.flattenToShortString(),
            )
            TargetAppLaunchResult.NotInstalled -> getString(R.string.target_not_installed)
            TargetAppLaunchResult.NoLaunchActivity -> getString(R.string.target_no_launch_activity)
            is TargetAppLaunchResult.Failed -> getString(R.string.launch_failed, result.reason)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

/**
 * 主页的无状态 Compose 入口，所有系统操作均通过回调交给 Activity 执行。
 */
@Composable
fun MainScreen(
    targetApp: TargetApp,
    resolution: TargetAppResolution,
    accessibilityEnabled: Boolean,
    launchMessage: String?,
    onLaunch: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        MainContent(
            innerPadding = innerPadding,
            targetApp = targetApp,
            resolution = resolution,
            accessibilityEnabled = accessibilityEnabled,
            launchMessage = launchMessage,
            onLaunch = onLaunch,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        )
    }
}

@Composable
private fun MainContent(
    innerPadding: PaddingValues,
    targetApp: TargetApp,
    resolution: TargetAppResolution,
    accessibilityEnabled: Boolean,
    launchMessage: String?,
    onLaunch: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.target_package, targetApp.packageName),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = resolutionDescription(resolution),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (accessibilityEnabled) {
                    R.string.accessibility_enabled
                } else {
                    R.string.accessibility_disabled
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = onOpenAccessibilitySettings) {
            Text(stringResource(R.string.open_accessibility_settings))
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onLaunch,
            enabled = resolution is TargetAppResolution.Available,
        ) {
            Text(stringResource(R.string.launch_target, targetApp.displayName))
        }
        launchMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun resolutionDescription(resolution: TargetAppResolution): String {
    return when (resolution) {
        is TargetAppResolution.Available -> stringResource(
            R.string.target_available,
            resolution.componentName.flattenToShortString(),
        )
        TargetAppResolution.NotInstalled -> stringResource(R.string.target_not_installed)
        TargetAppResolution.NoLaunchActivity -> stringResource(R.string.target_no_launch_activity)
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    LearningAppGateTheme {
        MainScreen(
            targetApp = TargetApps.DOUBAO,
            // 预览组件只用于展示界面；正式启动时始终由 PackageManager 动态解析。
            resolution = TargetAppResolution.Available(
                android.content.ComponentName(
                    "com.larus.nova",
                    "com.larus.home.impl.alias.AliasActivity1",
                ),
            ),
            accessibilityEnabled = false,
            launchMessage = null,
            onLaunch = {},
            onOpenAccessibilitySettings = {},
        )
    }
}
