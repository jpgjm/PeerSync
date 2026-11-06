package com.example.peersync.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific operations called from the shared UI.
 * The layout is shared (based on Android 0.1.6); platform-specific rows are
 * shown depending on these values.
 */
interface UiHooks {
    /** Whether QR scanning is available (Android: camera / desktop: webcam / iOS: camera). */
    val canScanQr: Boolean

    /**
     * Scans a QR code and returns the text via [onResult] (null if cancelled).
     * [onMessage] shows status / error text in a snackbar (0.2.38: Android's Google Code Scanner module
     * download and "couldn't open"; iOS and desktop don't use it).
     */
    fun scanQr(onResult: (String?) -> Unit, onMessage: (String) -> Unit)

    /**
     * "Share" button (the OS share sheet).
     * Android: ACTION_SEND chooser / iOS: UIActivityViewController /
     * Windows: the Windows share window (IDataTransferManagerInterop; back in 0.2.38, replacing "Copy").
     * Elsewhere (Linux and macOS desktop) false, so the button is hidden.
     * Tapping the ID box still copies the raw ID on every platform.
     */
    val canShareText: Boolean get() = false

    /**
     * Opens the share sheet.
     * @param anchor Position of the "Share" button (window coordinates, dp/pt), used to attach the
     *   iPad popover to the button. Null if unavailable.
     */
    fun shareText(text: String, anchor: ShareAnchor?) {}

    /**
     * Description of the "Always listen" switch. Null hides the switch (iOS).
     * Android: foreground service / desktop: start at sign-in + stay in the tray.
     */
    val alwaysListenDescription: String?

    /** Platform work when the switch changes (start/stop the service, register at startup). */
    fun onAlwaysListenChanged(on: Boolean) {}

    /**
     * Called before "Always listen" is turned on (0.2.32). Call [onReady] when it may be turned on.
     * Android: if notifications are off, opens the app's notification settings first and calls
     * [onReady] when the user comes back with notifications on (otherwise never).
     */
    fun beforeEnableAlwaysListen(onReady: () -> Unit) = onReady()

    /** Keep the screen on while the app is in front (0.2.32: all platforms). */
    fun applyKeepScreenOn(on: Boolean) {}

    /** iOS only: show the "Keep alive with silent audio" switch. */
    val showKeepAliveAudio: Boolean get() = false
    fun applyKeepAliveAudio(on: Boolean) {}

    /** The "Save to" section (iOS since 0.1.x, Android and desktop since 0.2.32). Null hides it. */
    val saveDest: SaveDestHooks? get() = null

    /**
     * Choices for "Send" (the Send button on each Device Hub peer row, 0.2.36). Text and Clipboard are always added.
     * Android: Files / Folder / Photos / Videos, iOS: same, Windows: Files / Folder.
     */
    val sendOptions: List<SendOption> get() = emptyList()

    /**
     * Opens the picker for the choice and returns the picked files (null if cancelled).
     * Transfers calls the result's onFinished when the send batch ends (so iOS can clean up
     * temp files and the security scope).
     */
    fun pickSendItems(option: SendOption, onResult: (SendPick?) -> Unit) { onResult(null) }

    /**
     * For "Send" -> "Clipboard", looks for an image when the clipboard has no text
     * (like LocalSend: images are sent as PNG files). Null if none.
     * Text is checked first by shared code (LocalClipboardManager).
     */
    fun readClipboardImage(): SendPick? = null

    /** Screen title. */
    val appTitle: String get() = com.example.peersync.AppInfo.NAME
}

/** A "Send" choice. id is for platform branching ("files" / "folder" / "photos" / "videos"; iOS passes it to Swift as is). */
data class SendOption(val id: String, val label: String) {
    /** Choices shared by all three platforms (0.2.25; ids and labels in one place). */
    companion object {
        val Files = SendOption("files", "Files")
        val Folder = SendOption("folder", "Folder")
        val Photos = SendOption("photos", "Photos")
        val Videos = SendOption("videos", "Videos")

        /** The four used on Android and iOS (Windows uses only Files and Folder). */
        val all: List<SendOption> get() = listOf(Files, Folder, Photos, Videos)
    }
}

/** Picker result. */
class SendPick(
    val items: List<com.example.peersync.proto.SendItem>,
    /** Cleanup called when the send batch ends (success, failure or cancel). */
    val onFinished: () -> Unit = {},
)

/** Share sheet origin (button rect). Origin at the window's top left, in dp (pt on iOS). */
data class ShareAnchor(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The "Save to" setting (default folder / custom folder).
 * iOS: backed by Swift's SaveDestinationStore. Android and desktop: [com.example.peersync.data.SaveDestController].
 */
interface SaveDestHooks {
    data class State(
        /** "app" or "custom". */
        val kind: String,
        /** Real path of the default folder. */
        val appPath: String,
        /** Label of the default row: "Default", or on iOS "Default (Standalone)" / "Default (LiveContainer)". */
        val defaultLabel: String,
        /** Folder name of the custom save location (null if not selected). */
        val customName: String?,
        /** Real path of the custom save location (null if not accessible). */
        val customPath: String?,
    )

    val state: StateFlow<State>

    fun selectApp()

    /** Switches to the already selected custom folder (0.2.16, for the radio button; call only when the folder is usable). */
    fun selectCustom()

    /** Opens the folder picker and switches to the custom folder once one is picked. */
    fun pickCustomFolder(onDone: (Boolean) -> Unit)

    /** Tooltip of the "open folder" button (for example "Open in Files"). */
    val openLabel: String

    /** Opens the current save folder in the platform's file manager. */
    fun openInFilesApp()
}
