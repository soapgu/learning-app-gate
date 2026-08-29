package com.soapgu.learningappgate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LearningAppGateColors = lightColorScheme(
    primary = Color(0xFF1677FF),
    onPrimary = Color.White,
    background = Color(0xFFFDFDFD),
    onBackground = Color(0xFF171717),
    surface = Color.White,
    onSurface = Color(0xFF171717),
)

@Composable
fun LearningAppGateTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LearningAppGateColors,
        content = content,
    )
}
