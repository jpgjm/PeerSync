package com.example.peersync.ui

import com.example.peersync.text.PsText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.peersync.log.LogLevel
import com.example.peersync.log.PsLog

/**
 * In-app log viewer (desktop and iOS). Replaces the old iOS LogViewerView.
 * For iOS devices without a Mac/Xcode console and for desktop, which has no Logcat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val entries by PsLog.entries.collectAsState()
    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current

    PlatformBackHandler(onBack)

    Scaffold(
        topBar = {
            DetailTopBar(
                title = PsText.logTitle,
                onBack = onBack,
                actions = {
                    // 0.2.17: "Copy" and "Clear" became icon buttons (label shown as a tooltip on long press /
                    // hover). Copy uses content_copy like the device ID and confirms with a snackbar like the
                    // ID box. Clear uses delete like Unpair (0.2.26).
                    TooltipIconButton(PsText.copyLabel, Icons.Filled.ContentCopy, onClick = {
                        clipboard.setText(AnnotatedString(PsLog.snapshot(minLevel)))
                        snackbar.show(PsText.copied)
                    })
                    TooltipIconButton("Clear", Icons.Filled.DeleteSweep, /* 0.2.38 */ onClick = { PsLog.clear() })
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = minLevel == level,
                        onClick = { minLevel = level },
                        label = { Text(level.label) },
                    )
                }
            }
            val visible = entries.filter { it.level.ordinal >= minLevel.ordinal }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(visible) { e ->
                    Text(
                        e.line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (e.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
