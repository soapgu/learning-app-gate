package com.soapgu.learningappgate.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.soapgu.learningappgate.ui.theme.LearningAppGateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quickActions_executeOnlyTheirGuardFunctions() {
        var accessibilityOpenCount = 0
        setHomeContent(onOpenAccessibilitySettings = { accessibilityOpenCount++ })

        composeRule.onNodeWithTag(HomeTestTags.quickAction(HomeAction.RULES)).performClick()
        composeRule.onNodeWithText("每天 07:20～20:30 可以使用官方豆包。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("知道了").performClick()

        composeRule.onNodeWithTag(HomeTestTags.quickAction(HomeAction.ACCESSIBILITY)).performClick()
        assertEquals(1, accessibilityOpenCount)

        composeRule.onNodeWithTag(HomeTestTags.quickAction(HomeAction.INSTALLATION)).performClick()
        composeRule.onNodeWithText("已检测到官方豆包及其启动入口。").assertIsDisplayed()
        composeRule.onNodeWithText("知道了").performClick()

        composeRule.onNodeWithTag(HomeTestTags.quickAction(HomeAction.ABOUT)).performClick()
        composeRule.onNodeWithText("本应用不提供聊天、内容生成", substring = true).assertIsDisplayed()
    }

    @Test
    fun drawerActions_executeOnlyTheirGuardFunctions() {
        var accessibilityOpenCount = 0
        setHomeContent(onOpenAccessibilitySettings = { accessibilityOpenCount++ })

        openDrawerAndClick(HomeAction.RULES)
        composeRule.onNodeWithText("每天 07:20～20:30 可以使用官方豆包。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("知道了").performClick()

        openDrawerAndClick(HomeAction.ACCESSIBILITY)
        assertEquals(1, accessibilityOpenCount)

        openDrawerAndClick(HomeAction.INSTALLATION)
        composeRule.onNodeWithText("已检测到官方豆包及其启动入口。").assertIsDisplayed()
        composeRule.onNodeWithText("知道了").performClick()

        openDrawerAndClick(HomeAction.ABOUT)
        composeRule.onNodeWithText("本应用不提供聊天、内容生成", substring = true).assertIsDisplayed()
    }

    @Test
    fun debugTitle_requiresFullFiveSecondHold() {
        var diagnosticsOpenCount = 0
        setHomeContent(
            debugFeaturesEnabled = true,
            onOpenDiagnostics = { diagnosticsOpenCount++ },
        )

        composeRule.onNodeWithTag(HomeTestTags.TITLE).performTouchInput {
            down(center)
            advanceEventTime(4_999L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0, diagnosticsOpenCount)

        composeRule.onNodeWithTag(HomeTestTags.TITLE).performTouchInput {
            down(center)
            advanceEventTime(5_001L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, diagnosticsOpenCount)
    }

    @Test
    fun releaseTitle_hasNoDiagnosticsGesture() {
        var diagnosticsOpenCount = 0
        setHomeContent(
            debugFeaturesEnabled = false,
            onOpenDiagnostics = { diagnosticsOpenCount++ },
        )

        composeRule.onNodeWithTag(HomeTestTags.TITLE).performTouchInput {
            longClick()
        }

        assertEquals(0, diagnosticsOpenCount)
    }

    @Test
    fun bottomBar_hasNoClickAction() {
        setHomeContent()

        composeRule.onNodeWithTag(HomeTestTags.DISABLED_LAUNCH_BAR)
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun largeFont_statusLabelAndValueDoNotOverlap() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LearningAppGateTheme {
                    HomeRoute(
                        state = installedState(),
                        debugFeaturesEnabled = false,
                        onOpenDiagnostics = {},
                        onOpenAccessibilitySettings = {},
                    )
                }
            }
        }

        val labelBounds = composeRule.onNodeWithTag(HomeTestTags.ALLOWED_WINDOW_LABEL)
            .fetchSemanticsNode().boundsInRoot
        val valueBounds = composeRule.onNodeWithTag(HomeTestTags.ALLOWED_WINDOW_VALUE)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(labelBounds.right <= valueBounds.left)
    }

    private fun setHomeContent(
        debugFeaturesEnabled: Boolean = false,
        onOpenDiagnostics: () -> Unit = {},
        onOpenAccessibilitySettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            LearningAppGateTheme {
                HomeRoute(
                    state = installedState(),
                    debugFeaturesEnabled = debugFeaturesEnabled,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
            }
        }
    }

    private fun openDrawerAndClick(action: HomeAction) {
        composeRule.onNodeWithContentDescription("打开菜单").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HomeTestTags.drawerAction(action)).performClick()
        composeRule.waitForIdle()
    }

    private fun installedState(): HomeUiState = createHomeUiState(
        targetStatus = TargetAppStatusUi.INSTALLED,
        accessibilityEnabled = true,
    )
}
