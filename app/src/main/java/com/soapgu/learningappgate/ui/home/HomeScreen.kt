package com.soapgu.learningappgate.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soapgu.learningappgate.R
import com.soapgu.learningappgate.ui.theme.LearningAppGateTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val PageBackground = Color(0xFFFDFDFD)
private val CardBackground = Color(0xFFF5F5F5)
private val PrimaryBlue = Color(0xFF1677FF)
private val MutedText = Color(0xFF858585)

internal object HomeTestTags {
    const val TITLE = "home_title"
    const val DISABLED_LAUNCH_BAR = "home_disabled_launch_bar"
    const val ALLOWED_WINDOW_LABEL = "home_allowed_window_label"
    const val ALLOWED_WINDOW_VALUE = "home_allowed_window_value"

    fun quickAction(action: HomeAction) = "home_quick_${action.name}"
    fun drawerAction(action: HomeAction) = "home_drawer_${action.name}"
}

internal enum class HomeAction {
    RULES,
    ACCESSIBILITY,
    INSTALLATION,
    ABOUT,
}

@Composable
fun HomeRoute(
    state: HomeUiState,
    debugFeaturesEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<HomeAction?>(null) }

    fun selectAction(action: HomeAction) {
        if (action == HomeAction.ACCESSIBILITY) {
            onOpenAccessibilitySettings()
        } else {
            dialog = action
        }
    }

    HomeScreen(
        state = state,
        debugFeaturesEnabled = debugFeaturesEnabled,
        drawerState = drawerState,
        dialog = dialog,
        onDismissDialog = { dialog = null },
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onDrawerAction = { action ->
            scope.launch { drawerState.close() }
            selectAction(action)
        },
        onAction = ::selectAction,
        modifier = modifier,
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    debugFeaturesEnabled: Boolean,
    drawerState: DrawerState,
    dialog: HomeAction?,
    onDismissDialog: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDrawer: () -> Unit,
    onDrawerAction: (HomeAction) -> Unit,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawer(
                state = state,
                onAction = onDrawerAction,
            )
        },
        modifier = modifier,
    ) {
        HomeContent(
            state = state,
            debugFeaturesEnabled = debugFeaturesEnabled,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenDrawer = onOpenDrawer,
            onAction = onAction,
        )
    }

    dialog?.let { action ->
        HomeInfoDialog(state = state, action = action, onDismiss = onDismissDialog)
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    debugFeaturesEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenDrawer: () -> Unit,
    onAction: (HomeAction) -> Unit,
) {
    Scaffold(
        containerColor = PageBackground,
        bottomBar = { DisabledLaunchBar(state.launchEnabled) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.home_open_menu))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag(HomeTestTags.TITLE).then(if (debugFeaturesEnabled) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        val heldForFiveSeconds = withTimeoutOrNull(5_000L) {
                                            tryAwaitRelease()
                                            false
                                        } ?: true
                                        if (heldForFiveSeconds) onOpenDiagnostics()
                                    },
                                )
                            }
                        } else {
                            Modifier
                        }),
                    )
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        color = MutedText,
                        fontSize = 13.sp,
                    )
                }
                GuardStatusIcon(
                    state = state,
                )
            }

            StatusCard(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )

            QuickActions(onAction = onAction)
        }
    }
}

/** 标题右侧只展示守卫状态；隐藏诊断入口固定注册在标题本身。 */
@Composable
private fun GuardStatusIcon(
    state: HomeUiState,
) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_guard),
            contentDescription = stringResource(R.string.home_guard_status),
            tint = if (state.accessibilityEnabled) PrimaryBlue else MutedText,
        )
    }
}

@Composable
private fun StatusCard(state: HomeUiState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CardBackground, shape = RoundedCornerShape(28.dp)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = statusTitle(state.accessStatus),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = statusDescription(state.accessStatus),
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 27.sp,
            )
            HorizontalDivider(color = Color(0xFFE2E2E2))
            StatusLine(
                label = stringResource(R.string.home_allowed_window),
                value = state.allowedWindowText,
                labelTestTag = HomeTestTags.ALLOWED_WINDOW_LABEL,
                valueTestTag = HomeTestTags.ALLOWED_WINDOW_VALUE,
            )
            StatusLine(
                stringResource(R.string.home_accessibility_status),
                stringResource(
                    if (state.accessibilityEnabled) R.string.home_enabled else R.string.home_disabled,
                ),
            )
            StatusLine(
                stringResource(R.string.home_target_status),
                targetStatusText(state.targetAppStatus),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_security_note),
                color = MutedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    labelTestTag: String? = null,
    valueTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = MutedText,
            modifier = Modifier
                .weight(1f)
                .then(if (labelTestTag == null) Modifier else Modifier.testTag(labelTestTag)),
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .then(if (valueTestTag == null) Modifier else Modifier.testTag(valueTestTag)),
        )
    }
}

@Composable
private fun QuickActions(onAction: (HomeAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTestTags.DISABLED_LAUNCH_BAR)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickAction(HomeAction.RULES, R.drawable.ic_schedule, R.string.home_rules, onAction)
        QuickAction(HomeAction.ACCESSIBILITY, R.drawable.ic_guard, R.string.home_enable_guard, onAction)
        QuickAction(HomeAction.INSTALLATION, R.drawable.ic_apps, R.string.home_installation, onAction)
        QuickAction(HomeAction.ABOUT, R.drawable.ic_info, R.string.home_about, onAction)
    }
}

@Composable
private fun QuickAction(
    action: HomeAction,
    iconRes: Int,
    labelRes: Int,
    onAction: (HomeAction) -> Unit,
) {
    Surface(
        modifier = Modifier.testTag(HomeTestTags.quickAction(action)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E1E1)),
        onClick = { onAction(action) },
        color = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(iconRes), null, modifier = Modifier.size(21.dp))
            Text(stringResource(labelRes), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DisabledLaunchBar(enabled: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.62f),
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_lock), null, tint = PrimaryBlue)
            Text(
                text = stringResource(R.string.home_integration_pending),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(painterResource(R.drawable.ic_guard), null)
        }
    }
}

@Composable
private fun HomeDrawer(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.82f)) {
        Text(
            text = stringResource(R.string.home_drawer_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(24.dp),
        )
        NavigationDrawerItem(
            modifier = Modifier.testTag(HomeTestTags.drawerAction(HomeAction.RULES)),
            label = { Text(stringResource(R.string.home_rules)) },
            selected = false,
            icon = { Icon(painterResource(R.drawable.ic_schedule), null) },
            onClick = { onAction(HomeAction.RULES) },
        )
        NavigationDrawerItem(
            modifier = Modifier.testTag(HomeTestTags.drawerAction(HomeAction.ACCESSIBILITY)),
            label = { Text(stringResource(R.string.home_enable_guard)) },
            badge = { Text(if (state.accessibilityEnabled) stringResource(R.string.home_enabled) else stringResource(R.string.home_disabled)) },
            selected = false,
            icon = { Icon(painterResource(R.drawable.ic_guard), null) },
            onClick = { onAction(HomeAction.ACCESSIBILITY) },
        )
        NavigationDrawerItem(
            modifier = Modifier.testTag(HomeTestTags.drawerAction(HomeAction.INSTALLATION)),
            label = { Text(stringResource(R.string.home_installation)) },
            selected = false,
            icon = { Icon(painterResource(R.drawable.ic_apps), null) },
            onClick = { onAction(HomeAction.INSTALLATION) },
        )
        NavigationDrawerItem(
            modifier = Modifier.testTag(HomeTestTags.drawerAction(HomeAction.ABOUT)),
            label = { Text(stringResource(R.string.home_about)) },
            selected = false,
            icon = { Icon(painterResource(R.drawable.ic_info), null) },
            onClick = { onAction(HomeAction.ABOUT) },
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.home_controlled_entry_note),
            color = MutedText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun HomeInfoDialog(state: HomeUiState, action: HomeAction, onDismiss: () -> Unit) {
    val title = when (action) {
        HomeAction.RULES -> stringResource(R.string.home_rules)
        HomeAction.INSTALLATION -> stringResource(R.string.home_installation)
        HomeAction.ABOUT -> stringResource(R.string.home_about)
        HomeAction.ACCESSIBILITY -> return
    }
    val body = when (action) {
        HomeAction.RULES -> stringResource(R.string.home_rules_detail, state.allowedWindowText)
        HomeAction.INSTALLATION -> targetStatusDetail(state.targetAppStatus)
        HomeAction.ABOUT -> stringResource(R.string.home_about_detail)
        HomeAction.ACCESSIBILITY -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, lineHeight = 24.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_dialog_ok)) } },
    )
}

@Composable
private fun statusTitle(status: HomeAccessStatusUi): String = when (status) {
    is HomeAccessStatusUi.Allowed -> stringResource(R.string.home_status_allowed)
    is HomeAccessStatusUi.OutsideWindow -> stringResource(R.string.home_status_outside)
    HomeAccessStatusUi.IntegrationPending -> stringResource(R.string.home_integration_pending)
    HomeAccessStatusUi.TargetUnavailable -> stringResource(R.string.home_status_target_unavailable)
}

@Composable
private fun statusDescription(status: HomeAccessStatusUi): String = when (status) {
    is HomeAccessStatusUi.Allowed -> stringResource(R.string.home_allowed_detail, status.closesAtText)
    is HomeAccessStatusUi.OutsideWindow -> stringResource(R.string.home_outside_detail, status.nextOpeningText)
    HomeAccessStatusUi.IntegrationPending -> stringResource(R.string.home_integration_pending_detail)
    HomeAccessStatusUi.TargetUnavailable -> stringResource(R.string.home_target_unavailable_detail)
}

@Composable
private fun targetStatusText(status: TargetAppStatusUi): String = when (status) {
    TargetAppStatusUi.INSTALLED -> stringResource(R.string.home_target_installed)
    TargetAppStatusUi.NOT_INSTALLED -> stringResource(R.string.home_target_not_installed)
    TargetAppStatusUi.NO_LAUNCH_ACTIVITY -> stringResource(R.string.home_target_no_entry)
}

@Composable
private fun targetStatusDetail(status: TargetAppStatusUi): String = when (status) {
    TargetAppStatusUi.INSTALLED -> stringResource(R.string.home_target_installed_detail)
    TargetAppStatusUi.NOT_INSTALLED -> stringResource(R.string.home_target_not_installed_detail)
    TargetAppStatusUi.NO_LAUNCH_ACTIVITY -> stringResource(R.string.home_target_no_entry_detail)
}

@Preview(showBackground = true, heightDp = 820)
@Composable
private fun HomePendingPreview() {
    LearningAppGateTheme {
        HomeRoute(
            state = HomeUiState(HomeAccessStatusUi.IntegrationPending, defaultAllowedWindowText(), false, TargetAppStatusUi.INSTALLED, false),
            debugFeaturesEnabled = false,
            onOpenDiagnostics = {},
            onOpenAccessibilitySettings = {},
        )
    }
}

@Preview(showBackground = true, fontScale = 1.3f, heightDp = 820)
@Composable
private fun HomeUnavailableLargeTextPreview() {
    LearningAppGateTheme {
        HomeRoute(
            state = HomeUiState(HomeAccessStatusUi.TargetUnavailable, defaultAllowedWindowText(), true, TargetAppStatusUi.NOT_INSTALLED, false),
            debugFeaturesEnabled = true,
            onOpenDiagnostics = {},
            onOpenAccessibilitySettings = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 820, name = "允许状态（M1.4 预留）")
@Composable
private fun HomeAllowedPreview() {
    LearningAppGateTheme {
        HomeRoute(
            state = HomeUiState(HomeAccessStatusUi.Allowed("20:30"), defaultAllowedWindowText(), true, TargetAppStatusUi.INSTALLED, true),
            debugFeaturesEnabled = false,
            onOpenDiagnostics = {},
            onOpenAccessibilitySettings = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 820, name = "禁止状态（M1.4 预留）")
@Composable
private fun HomeOutsidePreview() {
    LearningAppGateTheme {
        HomeRoute(
            state = HomeUiState(HomeAccessStatusUi.OutsideWindow("明天 07:20"), defaultAllowedWindowText(), true, TargetAppStatusUi.INSTALLED, false),
            debugFeaturesEnabled = false,
            onOpenDiagnostics = {},
            onOpenAccessibilitySettings = {},
        )
    }
}
