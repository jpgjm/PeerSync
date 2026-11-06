package com.example.peersync.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {
    // Desktop has no back gesture; use the on-screen back button.
}
