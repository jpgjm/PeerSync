package com.example.peersync.ui

import androidx.compose.runtime.Composable

/**
 * System back action (Android only; desktop and iOS use the on-screen back button).
 */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)
