package com.paper2binary.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colours with a fixed meaning across the scanner, review and results screens. */
object StatusColors {
    val Good = Color(0xFF2E7D4F)
    val Attention = Color(0xFFB7791F)
    val Problem = Color(0xFFC0392B)
}

/** Bar and heat-map colour, checked for lightness, chroma and contrast against each surface. */
object ChartColors {
    val Light = Color(0xFF2E6FB0)
    val Dark = Color(0xFF5490CC)

    val current: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark else Light
}

private val Light = lightColorScheme(
    primary = Color(0xFF1B3A57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E4F2),
    onPrimaryContainer = Color(0xFF0C2438),
    secondary = Color(0xFF4A6072),
    secondaryContainer = Color(0xFFDDE5EC),
    onSecondaryContainer = Color(0xFF16232E),
    surface = Color(0xFFFBFBF9),
    background = Color(0xFFFBFBF9),
    surfaceVariant = Color(0xFFE6E8E6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9CBE8),
    onPrimary = Color(0xFF0C2438),
    primaryContainer = Color(0xFF28465F),
    onPrimaryContainer = Color(0xFFD5E4F2),
    secondary = Color(0xFFB4C6D4),
    secondaryContainer = Color(0xFF34495A),
    onSecondaryContainer = Color(0xFFDDE5EC),
    surface = Color(0xFF141618),
    background = Color(0xFF141618),
)

@Composable
fun P2BTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
