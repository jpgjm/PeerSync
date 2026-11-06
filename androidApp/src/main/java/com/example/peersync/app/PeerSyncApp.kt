package com.example.peersync.app

import com.example.peersync.AppInfo
import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.example.peersync.AppGraph
import com.example.peersync.data.AndroidKeyValueStore
import com.example.peersync.data.AndroidSaveFolderAccess
import com.example.peersync.data.Incoming
import com.example.peersync.data.Pairing
import com.example.peersync.data.SaveDestController
import com.example.peersync.data.Settings
import com.example.peersync.data.Transfers
import com.example.peersync.log.LogLevel
import com.example.peersync.log.PsLog
import com.example.peersync.net.IrohNode
import computer.iroh.IrohAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class PeerSyncApp : Application() {
    /** One per process. Keeps running after the UI closes as long as the foreground service is alive. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var graph: AppGraph
        private set
    lateinit var notifier: TransferNotifierImpl
        private set

    /** "Save to" (0.2.32): Download/PeerSync or a folder picked with SAF. MainActivity adds the picker. */
    lateinit var saveDest: SaveDestController
        private set

    override fun onCreate() {
        super.onCreate()

        // Route shared logs to Logcat (spec 3: debug logs required)
        PsLog.addSink { e ->
            when (e.level) {
                LogLevel.DEBUG -> Log.d(e.tag, e.message)
                LogLevel.INFO -> Log.i(e.tag, e.message)
                LogLevel.WARN -> Log.w(e.tag, e.message)
                LogLevel.ERROR -> Log.e(e.tag, e.message)
            }
        }

        recordCrashes()

        // Startup log (0.2.30; version from gradle.properties -> versionName via PackageManager; same format as desktop/iOS)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        PsLog.i(TAG, "${AppInfo.NAME} started v$version (Android ${android.os.Build.VERSION.RELEASE})")

        // iroh's DNS resolver needs the JavaVM and Context. Call once before creating the Endpoint.
        IrohAndroid.installAndroidContext(this)

        // Device name: the model name until the user sets one in Settings (0.2.36)
        val settings = Settings(AndroidKeyValueStore(this), defaultDeviceName = android.os.Build.MODEL)
        saveDest = SaveDestController(settings, AndroidSaveFolderAccess(this, { downloadDir() }))
        val node = IrohNode(settings, appScope)
        notifier = TransferNotifierImpl(this)
        val transfers = Transfers(
            node = node,
            scope = appScope,
            notifier = notifier,
            onSaved = { path ->
                MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
            },
            historyStore = settings.store,
        )
        // Receiving side of "Send" (consent and saving). Saves to the "Save to" setting (default Download/PeerSync/<peer name>/)
        val incoming = Incoming(
            settings = settings,
            targets = saveDest,
            transfers = transfers,
            scope = appScope,
        )
        val incomingNotifier = IncomingNotifierImpl(this)
        incoming.notifier = incomingNotifier
        // Pairing requests (0.2.14). In the background, a notification with "Allow" / "Cancel" buttons
        val pairing = Pairing(settings, node, appScope)
        pairing.notifier = incomingNotifier
        graph = AppGraph(settings, node, transfers, incoming = incoming, pairing = pairing)
        notifier.transfers = transfers
        node.start()
    }

    /**
     * Uncaught exceptions (0.2.33). The in-app log is in memory and dies with the process, so the crash is also
     * written to a file and logged as ERROR on the next launch. The previous handler (the system's) still runs, so
     * the app crashes as before and logcat keeps the usual stack trace.
     */
    private fun recordCrashes() {
        val crashFile = File(filesDir, AppInfo.CRASH_FILE)
        if (crashFile.exists()) {
            PsLog.e(TAG, "The previous run crashed:\n${crashFile.readText()}")
            crashFile.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                PsLog.e(TAG, "Uncaught exception (${thread.name}): ${e.stackTraceToString()}")
                crashFile.writeText("${java.util.Date()} (${thread.name})\n${e.stackTraceToString()}")
            } finally {
                previous?.uncaughtException(thread, e)
            }
        }
    }

    companion object {
        private const val TAG = "${AppInfo.NAME}/App"

        fun from(context: android.content.Context): PeerSyncApp =
            context.applicationContext as PeerSyncApp

        /** Received files go to Download/PeerSync (created by this app, so no permission needed). Folder name from AppInfo (0.2.30). */
        fun downloadDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            AppInfo.DOWNLOAD_FOLDER,
        )
    }
}
