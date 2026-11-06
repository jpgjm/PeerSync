package com.example.peersync.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {
    // iOS uses the on-screen back button.
}
