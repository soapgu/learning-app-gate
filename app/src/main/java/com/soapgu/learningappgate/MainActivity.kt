package com.soapgu.learningappgate

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.soapgu.learningappgate.accessibility.ExitAction
import com.soapgu.learningappgate.accessibility.GateAccessibilityService
import com.soapgu.learningappgate.accessibility.InterceptionActionPolicy
import com.soapgu.learningappgate.accessibility.InterceptionDiagnostics
import com.soapgu.learningappgate.authorization.AuthorizationCenter
import com.soapgu.learningappgate.authorization.LaunchAuthorizationStateMachine
import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import com.soapgu.learningappgate.target.TargetApp
import com.soapgu.learningappgate.target.TargetAppLaunchResult
import com.soapgu.learningappgate.target.TargetAppLauncher
import com.soapgu.learningappgate.target.TargetAppResolution
import com.soapgu.learningappgate.target.TargetApps
import com.soapgu.learningappgate.ui.theme.LearningAppGateTheme

/**
 * M0 阶段的诊断主页；M0.4 起提供“授权并启动豆包”入口。
 *
 * 授权流程：解析成功 -> 创建 Pending 授权（5 秒有效）-> 发送官方启动 Intent；
 * 豆包进入前台后由无障碍事件驱动激活，启动失败则立即撤销授权并显示错误。
 * 直接启动豆包（未经授权）仍由守卫按 M0.3-plus 拦截。
 */
class MainActivity : ComponentActivity() {
    private lateinit var targetAppLauncher: TargetAppLauncher
    private var resolution by mutableStateOf<TargetAppResolution>(TargetAppResolution.NotInstalled)
    private var launchMessage by mutableStateOf<String?>(null)
    private var accessibilityEnabled by mutableStateOf(false)
    private var interceptionCount by mutableStateOf(0)
    private var lastInterceptionSeconds by mutableStateOf<Long?>(null)
    private var exitAction by mutableStateOf(InterceptionActionPolicy.exitAction)
    private var authorizationState by mutableStateOf<LaunchAuthorizationState>(LaunchAuthorizationState.Idle)

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
                    interceptionCount = interceptionCount,
                    lastInterceptionSeconds = lastInterceptionSeconds,
                    exitAction = exitAction,
                    onExitActionChange = ::selectExitAction,
                    authorizationState = authorizationState,
                    launchMessage = launchMessage,
                    onLaunch = ::authorizeAndLaunchTargetApp,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置返回，或者在外部安装/卸载了目标应用、触发过拦截，
        // 因此每次恢复都重新查询状态和诊断数据。
        refreshStatus()
    }

    private fun refreshStatus() {
        resolution = targetAppLauncher.resolve(TargetApps.DOUBAO)
        accessibilityEnabled = AccessibilityServiceStatus.isEnabled(
            context = this,
            serviceClass = GateAccessibilityService::class.java,
        )
        interceptionCount = InterceptionDiagnostics.interceptionCount
        lastInterceptionSeconds = InterceptionDiagnostics.lastInterceptionAtMs
            ?.let { (SystemClock.elapsedRealtime() - it) / 1000 }
        exitAction = InterceptionActionPolicy.exitAction
        // 读取快照会做惰性超时判定：过期的 Pending 直接显示为已失效。
        authorizationState = AuthorizationCenter.state
    }

    private fun selectExitAction(action: ExitAction) {
        InterceptionActionPolicy.exitAction = action
        exitAction = action
    }

    /**
     * 授权并启动目标应用（M0.4）：
     * 解析失败 -> 显示错误且不创建授权；成功 -> 创建 Pending -> 启动；
     * 启动异常 -> 立即撤销授权并显示错误，不留悬挂 Pending。
     */
    private fun authorizeAndLaunchTargetApp() {
        // 解析先行：未安装/无启动入口直接报错，不进入授权流程。
        when (val resolution = targetAppLauncher.resolve(TargetApps.DOUBAO)) {
            TargetAppResolution.NotInstalled -> {
                launchMessage = getString(R.string.target_not_installed)
                return
            }
            TargetAppResolution.NoLaunchActivity -> {
                launchMessage = getString(R.string.target_no_launch_activity)
                return
            }
            is TargetAppResolution.Available -> Unit
        }

        if (!AuthorizationCenter.createPending()) {
            launchMessage = getString(R.string.authorize_pending_exists)
            return
        }

        launchMessage = when (val result = targetAppLauncher.launch(TargetApps.DOUBAO)) {
            is TargetAppLaunchResult.Started -> getString(
                R.string.launch_authorized_started,
                result.componentName.flattenToShortString(),
            )
            // 以下启动失败路径统一撤销刚创建的 Pending，避免 5 秒内悬挂。
            TargetAppLaunchResult.NotInstalled -> {
                AuthorizationCenter.revoke("目标应用未安装")
                getString(R.string.target_not_installed)
            }
            TargetAppLaunchResult.NoLaunchActivity -> {
                AuthorizationCenter.revoke("目标应用没有启动入口")
                getString(R.string.target_no_launch_activity)
            }
            is TargetAppLaunchResult.Failed -> {
                AuthorizationCenter.revoke("启动失败：${result.reason}")
                getString(R.string.launch_failed, result.reason)
            }
        }
        authorizationState = AuthorizationCenter.state
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
    interceptionCount: Int,
    lastInterceptionSeconds: Long?,
    exitAction: ExitAction,
    onExitActionChange: (ExitAction) -> Unit,
    authorizationState: LaunchAuthorizationState,
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
            interceptionCount = interceptionCount,
            lastInterceptionSeconds = lastInterceptionSeconds,
            exitAction = exitAction,
            onExitActionChange = onExitActionChange,
            authorizationState = authorizationState,
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
    interceptionCount: Int,
    lastInterceptionSeconds: Long?,
    exitAction: ExitAction,
    onExitActionChange: (ExitAction) -> Unit,
    authorizationState: LaunchAuthorizationState,
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
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.exit_action_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = exitAction == ExitAction.BACK,
                onClick = { onExitActionChange(ExitAction.BACK) },
                label = { Text(stringResource(R.string.exit_action_back)) },
            )
            FilterChip(
                selected = exitAction == ExitAction.HOME,
                onClick = { onExitActionChange(ExitAction.HOME) },
                label = { Text(stringResource(R.string.exit_action_home)) },
            )
        }
        if (interceptionCount > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.interception_count, interceptionCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            lastInterceptionSeconds?.let { seconds ->
                Text(
                    text = stringResource(R.string.interception_last_at, seconds),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onLaunch,
            enabled = resolution is TargetAppResolution.Available,
        ) {
            Text(stringResource(R.string.launch_authorized, targetApp.displayName))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.authorization_status_label,
                authorizationStateDescription(authorizationState),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        launchMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 授权状态的界面文案；失效原因对用户简化为统一“已结束”（完整原因见诊断日志）。 */
@Composable
private fun authorizationStateDescription(state: LaunchAuthorizationState): String {
    return when (state) {
        LaunchAuthorizationState.Idle -> stringResource(R.string.authorization_state_idle)
        is LaunchAuthorizationState.Pending -> stringResource(
            R.string.authorization_state_pending,
            LaunchAuthorizationStateMachine.PENDING_VALIDITY_MS.toInt() / 1000,
        )
        is LaunchAuthorizationState.Active -> stringResource(R.string.authorization_state_active)
        LaunchAuthorizationState.Paused -> stringResource(R.string.authorization_state_idle)
        is LaunchAuthorizationState.Revoked -> stringResource(R.string.authorization_state_revoked)
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
                android.content.Intent(),
            ),
            accessibilityEnabled = false,
            interceptionCount = 0,
            lastInterceptionSeconds = null,
            exitAction = ExitAction.BACK,
            onExitActionChange = {},
            authorizationState = LaunchAuthorizationState.Idle,
            launchMessage = null,
            onLaunch = {},
            onOpenAccessibilitySettings = {},
        )
    }
}
