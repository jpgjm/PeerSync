package com.example.peersync.ui

import com.example.peersync.text.PsText
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Components for UI patterns repeated across screens (0.2.23).
 * Defaults keep the previous look, padding and text sizes.
 *
 *   SectionCard      … card with a title (+ description) (Settings tab, This device, iOS Save to)
 *   ActionDividerRow … row of "content | vertical divider | icon button(s)" (peer row, relay, device name, custom Save to)
 *   ValueActionRow   … ActionDividerRow whose left side is a one-line value (relay, device name)
 *   SwitchRow        … title (+ description) with a switch (Listening, Receiving, iOS keep-alive)
 *   ConfirmDialog    … confirm dialog with title, text-only body, confirm and cancel
 *   DetailTopBar     … top bar with a back button (history, logs)
 *   MarqueeText      … one-line value that scrolls when it doesn't fit
 *
 * Dialogs with non-text bodies (lists, QR, 3 buttons) stay plain AlertDialogs (different role, not shared).
 */

/** A: card with a title (+ description, + action at the title's right end). */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    /** Action at the right end of the title (iOS Save to's "Open in Files"). */
    titleAction: (@Composable () -> Unit)? = null,
    spacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (titleAction == null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    titleAction()
                }
            }
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

/**
 * B: row of "content | vertical divider | icon button".
 * The left content gets RowScope; the caller adds Modifier.weight(1f).
 */
@Composable
internal fun ActionDividerRow(
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    dividerPadding: Dp = 4.dp,
    actionModifier: Modifier = Modifier.padding(start = 4.dp),
    content: @Composable RowScope.() -> Unit,
) = ActionDividerRow(
    actions = { TooltipIconButton(actionLabel, actionIcon, modifier = actionModifier, onClick = onAction) },
    modifier = modifier,
    dividerPadding = dividerPadding,
    content = content,
)

/**
 * B: row of "content | vertical divider | any actions" (0.2.36: the peer row has Send and Unpair,
 * Settings' device ID row shows a spinner while resetting).
 */
@Composable
internal fun ActionDividerRow(
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    dividerPadding: Dp = 4.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        VerticalDivider(Modifier.padding(vertical = dividerPadding))
        actions()
    }
}

/** B: ActionDividerRow whose left side is a one-line value (marquee). */
@Composable
internal fun ValueActionRow(
    value: String,
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
) {
    ActionDividerRow(actionLabel, actionIcon, onAction) {
        MarqueeText(
            value,
            Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** C: row with a title (+ description) and a switch. */
@Composable
internal fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * D: confirm dialog with a text-only body.
 * dismissLabel = null hides the cancel button (notice with OK only).
 * dismissOnOutside = false ignores outside taps and back (decision via buttons only).
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = PsText.cancel,
    dismissOnOutside: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (dismissOnOutside) onDismiss() },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = dismissLabel?.let { label -> { TextButton(onClick = onDismiss) { Text(label) } } },
    )
}

/** E: top bar with a back button (text title). */
@Composable
internal fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) = DetailTopBar(title = { Text(title) }, onBack = onBack, actions = actions)

/** E: top bar with a back button (any title content). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTopBar(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
    )
}


/**
 * One-line text that scrolls horizontally when it doesn't fit (same as AlarmX's MarqueeText).
 * Without infinite iterations, LazyColumn rows may not scroll again after leaving and re-entering the screen.
 * (Moved here from the browse screen in 0.2.36.)
 */
@Composable
internal fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE),
    )
}
