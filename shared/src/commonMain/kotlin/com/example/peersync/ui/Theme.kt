package com.example.peersync.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

/**
 * App-wide theme (0.2.29).
 *
 * Until 0.2.28 Android, desktop and iOS each called `MaterialTheme(colorScheme = …)` separately.
 * Now unified on M3 Expressive's `MaterialExpressiveTheme`, with expressive motion (slightly bouncy springs).
 * Colors come from the platform (dynamic color on Android, standard light/dark on desktop and iOS).
 * Shapes and typography keep the MaterialTheme defaults (Shapes() / Typography()).
 *
 * `MaterialExpressiveTheme` is `@ExperimentalMaterial3ExpressiveApi` in material3 1.9.0-alpha04 (checked in the jar).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
