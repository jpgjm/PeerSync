package com.example.peersync.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.text.PsText

private const val TAG = "${AppInfo.NAME}/Input"

/**
 * Trailing button of a text field (0.2.37, same as SharePick's text tab): one button whose role follows the input.
 *   empty    -> Paste (fills the field from the clipboard)
 *   not empty -> Clear (empties the field)
 * [onClipboardEmpty] is called when Paste finds no text (the field shows a hint; a snackbar would be hidden
 * behind the dialog).
 */
@Composable
internal fun PasteClearButton(
    value: String,
    onValueChange: (String) -> Unit,
    onClipboardEmpty: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    if (value.isEmpty()) {
        TooltipIconButton(PsText.paste, Icons.Filled.ContentPaste, onClick = {
            val text = clipboard.getText()?.text
            if (text.isNullOrEmpty()) {
                PsLog.d(TAG, "paste: clipboard is empty")
                onClipboardEmpty()
            } else {
                PsLog.d(TAG, "paste: ${text.length} chars")
                onValueChange(text)
            }
        })
    } else {
        TooltipIconButton(PsText.clear, Icons.Filled.Clear, onClick = {
            PsLog.d(TAG, "clear")
            onValueChange("")
        })
    }
}

/**
 * OutlinedTextField with [PasteClearButton] at the end (0.2.37; every text field of the app uses it).
 * [error] is shown under the field and marks it red; otherwise "The clipboard is empty." shows there
 * after Paste found nothing, until the text changes.
 */
@Composable
internal fun PasteClearTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    var clipboardEmpty by remember { mutableStateOf(false) }
    val change: (String) -> Unit = {
        clipboardEmpty = false
        onValueChange(it)
    }
    val supporting = error ?: PsText.clipboardEmpty.takeIf { clipboardEmpty }
    OutlinedTextField(
        value = value,
        onValueChange = change,
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        trailingIcon = { PasteClearButton(value, change, onClipboardEmpty = { clipboardEmpty = true }) },
    )
}
