package com.happy.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Mint = Color(0xFF7FD1B9)
private val Deep = Color(0xFF12232E)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Deep,
    background = Deep,
    surface = Color(0xFF17303D),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B57),
    background = Color(0xFFF6F8F7),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun HappyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
