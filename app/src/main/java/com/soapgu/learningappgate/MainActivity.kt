package com.soapgu.learningappgate

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.soapgu.learningappgate.accessibility.AccessibilityServiceStatus
import com.soapgu.learningappgate.accessibility.ExitAction
import com.soapgu.learningappgate.accessibility.GateAccessibilityService
import com.soapgu.learningappgate.accessibility.InterceptionActionPolicy
import com.soapgu.learningappgate.accessibility.InterceptionDiagnostics
import com.soapgu.learningappgate.authorization.LaunchAuthorizationStateMachine
import com.soapgu.learningappgate.authorization.LaunchAuthorizationState
import com.soapgu.learningappgate.controller.prototypeAccessController
import com.soapgu.learningappgate.target.TargetApp
import com.soapgu.learningappgate.target.TargetAppLaunchResult
import com.soapgu.learningappgate.target.TargetAppLauncher
import com.soapgu.learningappgate.target.TargetAppResolution
import com.soapgu.learningappgate.target.TargetApps
import com.soapgu.learningappgate.ui.theme.LearningAppGateTheme
import com.soapgu.learningappgate.ui.home.HomeRoute
import com.soapgu.learningappgate.ui.home.buildHomeUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** M0.5 Debug 两档授权额度（毫秒）；Release 构建不渲染授权入口。 */
private const val AUTHORIZATION_SHORT_MS = 30_000L
private const val AUTHORIZATION_LONG_MS = 600_000L

private enum class AppScreen {
    HOME,
    DIAGNOSTICS,
}

/**
 * M0 诊断页的宿主及系统动作协调器。M1.2 起默认展示豆包风格受控首页，诊断页仅能在
 * Debug 构建中长按标题 5 秒进入；Release 不注册入口。诊断页继续提供 30 秒 / 10 分钟
 * 两档限时"授权并启动豆包"能力，用于独立回归 M0。
 *
 * 授权流程：解析成功 -> 创建 Pending 授权（5 秒有效）-> 发送官方启动 Intent；
 * 豆包进入前台后由无障碍事件驱动激活并起算额度，到期由控制器收回并走 BACK 拦截；
 * 启动失败则立即撤销授权并显示错误。直接启动豆包（未经授权）仍由守卫按 M0.3-plus 拦截。
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
    private var authorizationRemainingMs by mutableStateOf(0L)
    private var currentScreen by mutableStateOf(AppScreen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetAppLauncher = TargetAppLauncher(applicationContext)
        refreshStatus()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        setContent {
            LearningAppGateTheme {
                when (currentScreen) {
                    AppScreen.HOME -> HomeRoute(
                        state = buildHomeUiState(resolution, accessibilityEnabled),
                        debugFeaturesEnabled = isDebugFeaturesEnabled(),
                        onOpenDiagnostics = {
                            if (isDebugFeaturesEnabled()) currentScreen = AppScreen.DIAGNOSTICS
                        },
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    )
                    AppScreen.DIAGNOSTICS -> {
                        BackHandler { currentScreen = AppScreen.HOME }
                        DiagnosticsScreen(
                            targetApp = TargetApps.DOUBAO,
                            resolution = resolution,
                            accessibilityEnabled = accessibilityEnabled,
                            interceptionCount = interceptionCount,
                            lastInterceptionSeconds = lastInterceptionSeconds,
                            exitAction = exitAction,
                            onExitActionChange = ::selectExitAction,
                            debugFeaturesEnabled = isDebugFeaturesEnabled(),
                            authorizationState = authorizationState,
                            authorizationRemainingMs = authorizationRemainingMs,
                            launchMessage = launchMessage,
                            onLaunchWithoutAuthorization = ::launchTargetAppWithoutAuthorization,
                            onLaunch = ::authorizeAndLaunchTargetApp,
                            onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        )
                    }
                }
            }
        }
        // 前台期间每秒刷新授权快照与剩余额度：支撑"到期后 1 秒内收回"的真机观察，
        // 同时让 Pending 过期/会话结束等惰性转换及时反映到界面（读取只做惰性判定，
        // 不会把未过期的 Pending 消耗成 Active）。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    authorizationState = prototypeAccessController.state
                    authorizationRemainingMs = prototypeAccessController.remainingMs()
                    delay(1_000)
                }
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
        authorizationState = prototypeAccessController.state
        authorizationRemainingMs = prototypeAccessController.remainingMs()
    }

    /**
     * Debug 未授权启动验证：先撤销所有残留内存授权，再直接发送目标应用启动 Intent。
     * 豆包进入前台后应由无障碍服务按未授权链路立即拦截。
     */
    private fun launchTargetAppWithoutAuthorization() {
        prototypeAccessController.revoke("Debug 未授权启动验证")
        launchMessage = when (val result = targetAppLauncher.launch(TargetApps.DOUBAO)) {
            is TargetAppLaunchResult.Started -> getString(
                R.string.launch_unauthorized_started,
                result.componentName.flattenToShortString(),
            )
            TargetAppLaunchResult.NotInstalled -> getString(R.string.target_not_installed)
            TargetAppLaunchResult.NoLaunchActivity -> getString(R.string.target_no_launch_activity)
            is TargetAppLaunchResult.Failed -> getString(R.string.launch_failed, result.reason)
        }
        authorizationState = prototypeAccessController.state
        authorizationRemainingMs = prototypeAccessController.remainingMs()
    }

    private fun selectExitAction(action: ExitAction) {
        InterceptionActionPolicy.exitAction = action
        exitAction = action
    }

    /** Debug 构建判定：沿用 FLAG_DEBUGGABLE（与 InterceptionOverlay 诊断开关同一先例）。 */
    private fun isDebugFeaturesEnabled(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 限时授权并启动目标应用（M0.5）：
     * 解析失败 -> 显示错误且不创建授权；成功 -> 创建 Pending（携带额度）-> 启动；
     * 启动异常 -> 立即撤销授权并显示错误，不留悬挂 Pending。
     */
    private fun authorizeAndLaunchTargetApp(totalMs: Long) {
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

        if (!prototypeAccessController.createPending(totalMs)) {
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
                prototypeAccessController.revoke("目标应用未安装")
                getString(R.string.target_not_installed)
            }
            TargetAppLaunchResult.NoLaunchActivity -> {
                prototypeAccessController.revoke("目标应用没有启动入口")
                getString(R.string.target_no_launch_activity)
            }
            is TargetAppLaunchResult.Failed -> {
                prototypeAccessController.revoke("启动失败：${result.reason}")
                getString(R.string.launch_failed, result.reason)
            }
        }
        authorizationState = prototypeAccessController.state
        authorizationRemainingMs = prototypeAccessController.remainingMs()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

/**
 * 主页的无状态 Compose 入口，所有系统操作均通过回调交给 Activity 执行。
 */
@Composable
fun DiagnosticsScreen(
    targetApp: TargetApp,
    resolution: TargetAppResolution,
    accessibilityEnabled: Boolean,
    interceptionCount: Int,
    lastInterceptionSeconds: Long?,
    exitAction: ExitAction,
    onExitActionChange: (ExitAction) -> Unit,
    debugFeaturesEnabled: Boolean,
    authorizationState: LaunchAuthorizationState,
    authorizationRemainingMs: Long,
    launchMessage: String?,
    onLaunchWithoutAuthorization: () -> Unit,
    onLaunch: (Long) -> Unit,
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
            debugFeaturesEnabled = debugFeaturesEnabled,
            authorizationState = authorizationState,
            authorizationRemainingMs = authorizationRemainingMs,
            launchMessage = launchMessage,
            onLaunchWithoutAuthorization = onLaunchWithoutAuthorization,
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
    debugFeaturesEnabled: Boolean,
    authorizationState: LaunchAuthorizationState,
    authorizationRemainingMs: Long,
    launchMessage: String?,
    onLaunchWithoutAuthorization: () -> Unit,
    onLaunch: (Long) -> Unit,
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
        if (debugFeaturesEnabled) {
            OutlinedButton(
                onClick = onLaunchWithoutAuthorization,
                enabled = resolution is TargetAppResolution.Available,
            ) {
                Text(stringResource(R.string.launch_without_authorization, targetApp.displayName))
            }
            Spacer(modifier = Modifier.height(8.dp))
            // M0.5 两档限时授权入口：仅 Debug 构建渲染（Release 不暴露时长入口）。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onLaunch(AUTHORIZATION_SHORT_MS) },
                    enabled = resolution is TargetAppResolution.Available,
                ) {
                    Text(stringResource(R.string.authorize_30s_and_launch, targetApp.displayName))
                }
                Button(
                    onClick = { onLaunch(AUTHORIZATION_LONG_MS) },
                    enabled = resolution is TargetAppResolution.Available,
                ) {
                    Text(stringResource(R.string.authorize_10min_and_launch, targetApp.displayName))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.authorization_status_label,
                authorizationStateDescription(authorizationState, authorizationRemainingMs),
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
private fun authorizationStateDescription(
    state: LaunchAuthorizationState,
    remainingMs: Long,
): String {
    return when (state) {
        LaunchAuthorizationState.Idle -> stringResource(R.string.authorization_state_idle)
        is LaunchAuthorizationState.Pending -> stringResource(
            R.string.authorization_state_pending,
            LaunchAuthorizationStateMachine.PENDING_VALIDITY_MS.toInt() / 1000,
        )
        is LaunchAuthorizationState.Active -> stringResource(
            R.string.authorization_state_active,
            // 向上取整：30 秒额度从激活即显示 30，而不是 29。
            ((remainingMs + 999L) / 1000L).toInt(),
        )
        is LaunchAuthorizationState.Paused -> stringResource(
            R.string.authorization_state_paused,
            // 暂停期间额度冻结，剩余值即恢复后可用额度。
            ((remainingMs + 999L) / 1000L).toInt(),
        )
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
private fun DiagnosticsScreenPreview() {
    LearningAppGateTheme {
        DiagnosticsScreen(
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
            debugFeaturesEnabled = true,
            authorizationState = LaunchAuthorizationState.Idle,
            authorizationRemainingMs = 0L,
            launchMessage = null,
            onLaunchWithoutAuthorization = {},
            onLaunch = {},
            onOpenAccessibilitySettings = {},
        )
    }
}
