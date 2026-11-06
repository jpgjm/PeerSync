package com.example.peersync.ui

import com.example.peersync.text.PsText
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The single app-wide snackbar (drawn over the App screen).
 * Spec: has an "×" icon; duration like Android Material LENGTH_SHORT
 * (SnackbarDuration.Short is the closest in Compose Multiplatform).
 */
class SnackbarController(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String) {
        scope.launch {
            // replace any current one (don't queue up on repeated taps)
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message = message, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }

    /**
     * With an action button (0.2.14, for pairing's "Request again"). Also has "×"; duration like
     * Android Material LENGTH_LONG (SnackbarDuration.Long). indefinite = true is like
     * LENGTH_INDEFINITE (stays until "×" or the button is pressed; 0.2.15, for declined requests).
     * Calls onAction when the button is pressed.
     */
    fun showWithAction(message: String, actionLabel: String, indefinite: Boolean = false, onAction: () -> Unit) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = true,
                duration = if (indefinite) SnackbarDuration.Indefinite else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/**
 * "A transfer started" snackbar with a "Show" button that opens the Transfers (history) screen.
 * Used when receiving starts (0.2.33; downloads used it too until 0.2.36).
 * Having an action, the duration is like LENGTH_LONG (showWithAction default).
 */
fun SnackbarController.reportTransferStarted(message: String, onShow: () -> Unit) =
    showWithAction(message, PsText.show, onAction = onShow)

val LocalSnackbar = compositionLocalOf<SnackbarController> { error("SnackbarController not provided") }

/**
 * Snackbar anchor (0.2.18, like Android View's Snackbar.setAnchorView).
 * Compose has no setAnchorView, so we remember the window top of the anchored element (FAB, navigation bar)
 * and App shows the snackbar above it.
 * With several registered, the topmost (smallest top) wins.
 * Screens with none registered (history, logs) show it above the system navigation bar.
 */
class SnackbarAnchors {
    private val tops = mutableStateMapOf<Any, Float>()

    /** Top of the topmost anchor (window coordinates, px), or null if none. */
    val topPx: Float? get() = tops.values.minOrNull()

    internal fun update(key: Any, top: Float) {
        if (tops[key] != top) tops[key] = top
    }

    internal fun remove(key: Any) {
        tops.remove(key)
    }
}

val LocalSnackbarAnchors = staticCompositionLocalOf { SnackbarAnchors() }

/** Makes this element a snackbar anchor (removed automatically when it leaves the screen). */
@Composable
fun Modifier.snackbarAnchor(): Modifier {
    val anchors = LocalSnackbarAnchors.current
    val key = remember { Any() }
    DisposableEffect(anchors, key) {
        onDispose { anchors.remove(key) }
    }
    return this.onGloballyPositioned { anchors.update(key, it.boundsInWindow().top) }
}
