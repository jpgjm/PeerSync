package com.example.peersync.app

import com.example.peersync.AppInfo
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.peersync.data.AndroidSendItems
import com.example.peersync.data.Settings
import com.example.peersync.proto.SendItem
import com.example.peersync.ui.App
import com.example.peersync.ui.AppTheme
import com.example.peersync.ui.SendOption
import com.example.peersync.ui.SaveDestHooks
import com.example.peersync.ui.SendPick
import com.example.peersync.ui.ShareAnchor
import com.example.peersync.ui.UiHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "${AppInfo.NAME}/Main"

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var folderCallback: ((String?) -> Unit)? = null
    private val treeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            Log.d(TAG, "folder picked: $uri")
            folderCallback?.invoke(uri?.toString())
            folderCallback = null
        }

    /**
     * "Always listen" waiting for notifications to be turned on (0.2.32). Set when the switch was turned on
     * with notifications off; checked when the user comes back from the notification settings.
     */
    private var pendingAlwaysListen: (() -> Unit)? = null

    /** Google Code Scanner (0.2.38, replaces zxing-android-embedded; no CAMERA permission). */
    private val codeScanner by lazy { CodeScanner(this) }

    // ---- Pickers for "Send" (match FlyingCarpet's Files App / Camera Roll / Send Folder) ----

    private var sendCallback: ((SendPick?) -> Unit)? = null

    /** Files (multiple): SAF. Takes persistable permission on the URIs and releases it when the send batch ends. */
    private val sendFilesLauncher =
        registerForActivityResult(PersistableOpenMultipleDocuments()) { uris ->
            Log.d(TAG, "send files picked: ${uris.size}")
            finishPick(uris, persisted = true) { AndroidSendItems.ofUris(this, uris) }
        }

    /** Folder: SAF tree. Expands its contents recursively. */
    private val sendFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            Log.d(TAG, "send folder picked: $uri")
            if (uri == null) {
                finishPick(emptyList(), persisted = false) { emptyList() }
            } else {
                finishPick(listOf(uri), persisted = true) { AndroidSendItems.ofTree(this, uri) }
            }
        }

    /**
     * Photos (multiple): photo picker (images only).
     * The limit is PickMultipleVisualMedia's default = MediaStore.getPickImagesMaxLimit() (androidx.activity, 0.2.31+).
     */
    private val sendPhotosLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            Log.d(TAG, "send photos picked: ${uris.size}")
            finishPick(uris, persisted = false) { AndroidSendItems.ofUris(this, uris) }
        }

    /** Videos (multiple): photo picker (videos only). */
    private val sendVideosLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            Log.d(TAG, "send videos picked: ${uris.size}")
            finishPick(uris, persisted = false) { AndroidSendItems.ofUris(this, uris) }
        }

    /**
     * Converts picker results to SendItems (metadata and tree expansion run on the IO thread).
     * When persisted = true, the persistable permission is released when the send batch ends.
     */
    private fun finishPick(uris: List<Uri>, persisted: Boolean, build: () -> List<SendItem>) {
        val cb = sendCallback ?: return
        sendCallback = null
        if (uris.isEmpty()) {
            cb(null)
            return
        }
        if (persisted) {
            for (u in uris) {
                runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .onFailure { Log.w(TAG, "takePersistableUriPermission failed for $u: $it") }
            }
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching(build).getOrElse { emptyList() } }
            Log.d(TAG, "send items ready: ${items.size}")
            if (items.isEmpty()) {
                releasePersisted(uris, persisted)
                cb(null)
            } else {
                cb(SendPick(items) { releasePersisted(uris, persisted) })
            }
        }
    }

    private fun releasePersisted(uris: List<Uri>, persisted: Boolean) {
        if (!persisted) return
        for (u in uris) {
            runCatching { contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** OpenMultipleDocuments plus persistable permission flags (so files stay readable after the activity closes). */
    private class PersistableOpenMultipleDocuments : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }


    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    companion object {
        /** Whether in the foreground (only the foreground app can write the clipboard; used to decide on received-text notifications). */
        @Volatile
        var isInForeground = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // minSdk 36, so no version check is needed for POST_NOTIFICATIONS (API 33+) (0.2.29)
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = PeerSyncApp.from(this)
        val graph = app.graph
        applyKeepScreenOn(graph.settings.keepScreenOn.value)
        // If "Always listen" is on, start the service at launch. The app is in the foreground here,
        // so the foreground service start restrictions (Android 12+) don't apply.
        if (graph.settings.alwaysListen.value) {
            Log.d(TAG, "alwaysListen is on; ensuring NodeService is running")
            NodeService.start(this)
        }

        val hooks = object : UiHooks {
            override val canScanQr = true

            override fun scanQr(onResult: (String?) -> Unit, onMessage: (String) -> Unit) {
                codeScanner.scan(onResult, onMessage)
            }

            override val canShareText = true

            override fun shareText(text: String, anchor: ShareAnchor?) {
                // Android's share sheet slides up from the bottom, so anchor is unused
                Log.d(TAG, "share sheet: ${text.length} chars")
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        "Share device ID",
                    ),
                )
            }

            override val sendOptions = SendOption.all

            override fun pickSendItems(option: SendOption, onResult: (SendPick?) -> Unit) {
                sendCallback = onResult
                when (option) {
                    SendOption.Files -> sendFilesLauncher.launch(arrayOf("*/*"))
                    SendOption.Folder -> sendFolderLauncher.launch(null)
                    SendOption.Photos -> sendPhotosLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                    SendOption.Videos -> sendVideosLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                    else -> {
                        sendCallback = null
                        onResult(null)
                    }
                }
            }

            /** Clipboard image (when there's no text). Sends the content URI as is (same as LocalSend). */
            override fun readClipboardImage(): SendPick? {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm.primaryClip ?: return null
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri ?: continue
                    val mime = contentResolver.getType(uri) ?: clip.description.getMimeType(0)
                    if (mime == null || !mime.startsWith("image/")) continue
                    val items = runCatching { AndroidSendItems.ofUris(this@MainActivity, listOf(uri)) }
                        .onFailure { Log.w(TAG, "clipboard image: $it") }.getOrDefault(emptyList())
                    if (items.isEmpty()) continue
                    Log.d(TAG, "clipboard image: $uri ($mime)")
                    return SendPick(items)
                }
                return null
            }

            override val alwaysListenDescription = "Keeps listening after you close the app and after the device restarts."

            override fun onAlwaysListenChanged(on: Boolean) {
                if (on) NodeService.start(this@MainActivity) else NodeService.stop(this@MainActivity)
            }

            // 0.2.32: listening shows an ongoing notification, so notifications must be on first
            override fun beforeEnableAlwaysListen(onReady: () -> Unit) {
                if (NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                    onReady()
                } else {
                    Log.d(TAG, "notifications are off; opening the app's notification settings")
                    pendingAlwaysListen = onReady
                    startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName),
                    )
                }
            }

            override fun applyKeepScreenOn(on: Boolean) = this@MainActivity.applyKeepScreenOn(on)

            // 0.2.32: "Save to" (Download/PeerSync or a folder picked with SAF). The tree picker grants write access.
            override val saveDest: SaveDestHooks = app.saveDest.hooks(
                openLabel = "Open in Files",
                pick = { current, onResult ->
                    folderCallback = onResult
                    treeLauncher.launch(current?.let(Uri::parse))
                },
                open = { state -> openFolderInFiles(state, graph.settings) },
            )
        }

        setContent {
            val ctx = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            AppTheme(colorScheme = colors) {
                App(graph, hooks)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        // The save folder's permission may have changed while away (for example revoked in Settings)
        PeerSyncApp.from(this).saveDest.refresh()
        pendingAlwaysListen?.let { enable ->
            pendingAlwaysListen = null
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Log.d(TAG, "notifications turned on; enabling alwaysListen")
                enable()
            } else {
                Log.d(TAG, "notifications still off; alwaysListen stays off")
            }
        }
    }

    /** Keep screen on (0.2.32): only while this activity is shown, like iOS's isIdleTimerDisabled. */
    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "keepScreenOn=$on")
    }

    /**
     * Opens the save folder in the Files app (DocumentsUI handles ACTION_VIEW for
     * vnd.android.document/directory). The default folder is addressed through ExternalStorageProvider.
     *
     * 0.2.33: no FLAG_GRANT_READ_URI_PERMISSION. Granting a URI we have no permission for (the default folder is
     * written with java.io, not SAF) makes startActivity throw SecurityException
     * (AOSP android16 UriGrantsManagerService.checkGrantUriPermissionUnlocked; ExternalStorageProvider requires
     * MANAGE_DOCUMENTS) — that was the 0.2.32 crash. DocumentsUI holds MANAGE_DOCUMENTS itself, so no grant is needed.
     */
    private fun openFolderInFiles(state: SaveDestHooks.State, settings: Settings) {
        val token = settings.saveDestToken.value
        val uri = if (state.kind == Settings.SAVE_DEST_CUSTOM && state.customPath != null && token != null) {
            val tree = Uri.parse(token)
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } else {
            val dir = PeerSyncApp.downloadDir().also { it.mkdirs() }
            val rel = dir.path.removePrefix(Environment.getExternalStorageDirectory().path).trimStart('/')
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        }
        Log.d(TAG, "open folder: $uri")
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
        )
    }
}
