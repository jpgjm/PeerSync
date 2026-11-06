package com.example.peersync.data

import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.example.peersync.proto.DownloadTargets
import com.example.peersync.proto.SinkResult
import com.example.peersync.text.PsText
import com.example.peersync.ui.SaveDestHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "${AppInfo.NAME}/SaveDest"

/**
 * Platform access to the save folders (Android: Download/PeerSync + SAF tree, desktop: Downloads\PeerSync + a path).
 * The custom folder is identified by a token (Android: SAF tree URI, desktop: folder path).
 */
interface SaveFolderAccess {
    /** Real path of the default folder (shown under "Default"). */
    val defaultPath: String

    /** Where files go when "Default" is selected. */
    fun defaultTargets(): DownloadTargets

    /** Real path of the custom folder, or null when it can't be used (permission lost, folder gone). */
    fun customPath(token: String): String?

    /** Folder name of the custom folder (shown when it can't be used). */
    fun customName(token: String): String

    /** Where files go in the custom folder, or null when it can't be used. */
    fun customTargets(token: String): DownloadTargets?

    /** A new custom folder was picked (Android: keep the new permission, release the old one). */
    fun onCustomReplaced(oldToken: String?, newToken: String) {}
}

/**
 * "Save to" for Android and the desktop (0.2.32). Same behaviour as the iOS SaveDestinationStore:
 * files go to `<folder>/<peer name>/<remote path>`; when "Custom" is selected but can't be used,
 * [create] returns [SinkResult.Unavailable] and the UI asks to pick the folder again or save to Default.
 */
class SaveDestController(
    private val settings: Settings,
    private val access: SaveFolderAccess,
) : DownloadTargets {
    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SaveDestHooks.State> = _state.asStateFlow()

    private fun buildState(): SaveDestHooks.State {
        val token = settings.saveDestToken.value
        return SaveDestHooks.State(
            kind = settings.saveDestKind.value,
            appPath = access.defaultPath,
            defaultLabel = PsText.saveDestDefault,
            customName = token?.let { access.customName(it) },
            customPath = token?.let { access.customPath(it) },
        )
    }

    /** Re-reads the folders (for example after the app comes back to the front). */
    fun refresh() {
        _state.value = buildState()
    }

    fun selectApp() {
        settings.setSaveDestKind(Settings.SAVE_DEST_APP)
        refresh()
    }

    /** Switches to the already picked custom folder (only when it can be used). */
    fun selectCustom() {
        settings.setSaveDestKind(Settings.SAVE_DEST_CUSTOM)
        refresh()
    }

    /** A folder was picked: remember it and switch to it. */
    fun setCustomFolder(token: String) {
        access.onCustomReplaced(settings.saveDestToken.value, token)
        settings.setSaveDestToken(token)
        settings.setSaveDestKind(Settings.SAVE_DEST_CUSTOM)
        refresh()
        PsLog.i(TAG, "custom save folder: ${_state.value.customPath ?: access.customName(token)}")
    }

    override fun create(peerName: String, remotePath: String): SinkResult {
        if (settings.saveDestKind.value != Settings.SAVE_DEST_CUSTOM) {
            return access.defaultTargets().create(peerName, remotePath)
        }
        val token = settings.saveDestToken.value
            ?: return SinkResult.Unavailable(PsText.saveDestNotPicked).also { PsLog.w(TAG, "custom selected but no folder") }
        val targets = access.customTargets(token)
        if (targets == null) {
            PsLog.w(TAG, "custom folder unusable: ${access.customName(token)}")
            refresh()
            return SinkResult.Unavailable(PsText.saveDestUnusable(access.customName(token)))
        }
        return targets.create(peerName, remotePath)
    }

    /**
     * The [SaveDestHooks] for the UI. Picking and opening a folder need the platform's UI
     * (Android: the activity, desktop: Swing), so they are passed in.
     */
    fun hooks(
        openLabel: String,
        pick: (current: String?, onResult: (String?) -> Unit) -> Unit,
        open: (SaveDestHooks.State) -> Unit,
    ): SaveDestHooks = object : SaveDestHooks {
        override val state: StateFlow<SaveDestHooks.State> = this@SaveDestController.state
        override val openLabel = openLabel

        override fun selectApp() = this@SaveDestController.selectApp()

        override fun selectCustom() = this@SaveDestController.selectCustom()

        override fun pickCustomFolder(onDone: (Boolean) -> Unit) {
            pick(settings.saveDestToken.value) { token ->
                if (token == null) {
                    PsLog.d(TAG, "save folder picker cancelled")
                    onDone(false)
                } else {
                    setCustomFolder(token)
                    onDone(_state.value.customPath != null)
                }
            }
        }

        override fun openInFilesApp() = open(_state.value)
    }
}
