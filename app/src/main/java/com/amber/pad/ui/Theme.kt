package com.amber.pad.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC61A)

@Composable
fun AmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color(0xFF111111),
            background = Color(0xFF0B0B0C),
            surface = Color(0xFF141416),
            surfaceVariant = Color(0xFF1E1E21),
            onSurface = Color(0xFFF5F5F7),
            onSurfaceVariant = Color(0xFFA8A8AD),
            outline = Color(0xFF3A3A3E),
            secondaryContainer = Color(0xFF2A2110),
            onSecondaryContainer = Amber,
        ),
        content = content
    )
}
