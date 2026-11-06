package com.example.peersync.bridge

/**
 * Bridge implemented by the Swift service layer (iosApp/Source/).
 * BGContinuedProcessingTask, LiveContainer support, silent-audio keep-alive,
 * security-scoped bookmarks (save location), UIDocumentPicker and QR scanning
 * reuse the proven Swift code; common Kotlin code only sees this interface.
 */
interface IosBridge {
    /**
     * Default device name (until the user sets one in Settings, 0.2.36). uname() machine (a model identifier
     * like "iPad12,1"). Equivalent to Android's Build.MODEL ("F-51F").
     */
    fun deviceName(): String

    /**
     * Opens the share sheet (UIActivityViewController). Equivalent to ShareLink.
     * anchor* is the "Share" button rect (window coordinates, pt). On iPad the popover is
     * anchored there. anchorWidth <= 0 means unknown, shown centered.
     */
    fun shareText(text: String, anchorX: Double, anchorY: Double, anchorWidth: Double, anchorHeight: Double)

    /** One-line runtime summary ("LiveContainer (guest)" / "normal install"). For the startup log. */
    fun runtimeSummary(): String

    // ---- Save location (Swift SaveDestinationStore) ----

    fun saveDestState(): IosSaveDestState

    /** "app" or "custom". */
    fun setSaveDestKind(kind: String)

    /** Opens the Files folder picker and switches to the custom location if chosen. true on success. */
    fun pickSaveDestFolder(callback: (Boolean) -> Unit)

    /** Opens the current save location in the Files app. */
    fun openSaveRootInFilesApp()

    /** Real path of the current save root. null if custom isn't selected or accessible. */
    fun saveRootPath(): String?

    /** Why the save location is unusable (dialog body). */
    fun saveRootUnavailableReason(): String

    // ---- "Send" pickers (Swift SendPickers) ----

    /**
     * kind: "files" (UIDocumentPicker, multiple) / "folder" (recursively expanded) /
     *       "photos" (PHPicker images only; Live Photo = still + paired video) / "videos" (PHPicker videos only).
     * null if nothing chosen. Swift holds security scopes and temp files until endSendBatch(batchId).
     */
    fun pickSendItems(kind: String, callback: (IosSendBatch?) -> Unit)

    /** Ends a send batch (success, failure, cancel). Releases scopes and deletes temp files. */
    fun endSendBatch(batchId: String)

    /** Returns the clipboard (UIPasteboard) image as a temp PNG file, or null. */
    fun clipboardImage(): IosSendBatch?

    // ---- Local notifications (UNUserNotificationCenter) ----
    // 0.2.25: the 8 per-kind functions (notifyIncomingOffer / notifyTextAccepted / notifyFilesAutoAccepted /
    // notifyPairingRequest / notifyUnpaired and 3 clear*) merged into 2: post / clear.
    // Title, body and ID are built in Kotlin (IosNotifications, PsText); Swift only displays.

    /**
     * Posts a local notification only in the background (the foreground has in-app dialogs).
     * @param category category for buttons (IosNotifications.category*). null = no buttons
     * @param token token used to route a button decision back to IosApp.decideOffer / decidePairing
     * @param logLabel short name for logs
     * The parameter isn't named id because it clashes with the Objective-C id type and may be renamed in Swift.
     */
    fun postNotification(notificationId: String, title: String, body: String, category: String?, token: String?, logLabel: String)

    /** Removes a notification (decided, timed out, or closed in the app). */
    fun clearNotification(notificationId: String)

    // ---- QR scanning (AVFoundation) ----

    fun scanQr(callback: (String?) -> Unit)

    // ---- Keep-alive (UIApplication.isIdleTimerDisabled / silent audio) ----

    fun applyKeepScreenOn(on: Boolean)
    fun applyKeepAliveAudio(on: Boolean)

    // ---- Background protection for transfers (8 BGContinuedProcessingTask slots + LiveContainer support) ----

    /** Call on the main thread. null if no slot is free (that transfer proceeds unprotected). */
    fun beginTransferGuard(title: String, subtitle: String, onExpire: () -> Unit): IosTransferGuardHandle?
}

interface IosTransferGuardHandle {
    fun update(subtitle: String, percent: Int)
    fun end(success: Boolean)
}

data class IosSaveDestState(
    /** "app" or "custom". */
    val kind: String,
    /** Real path of the default (in-app) location. */
    val appPath: String,
    /** Whether running as a LiveContainer guest (RuntimeEnvironment.isLiveContainer, 0.2.28). */
    val inLiveContainer: Boolean,
    /** Custom location folder name (null if not selected). */
    val customName: String?,
    /** Custom location real path (null if not accessible). */
    val customPath: String?,
)

/** One file to send (created by the Swift picker). path is a readable real path (scope acquired, or a temp file). */
data class IosSendFile(val relPath: String, val path: String, val size: Long, val mtime: Long)

/** Picker result. batchId is the key for cleanup via endSendBatch. */
data class IosSendBatch(val batchId: String, val files: List<IosSendFile>)
