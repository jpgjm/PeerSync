package com.example.peersync.app

import com.example.peersync.AppInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resumes listening after the device restarts.
 * BOOT_COMPLETED is one of the exemptions that may still start foreground services on Android 12+.
 * On Android 15+, however, types such as dataSync can't be started (switched to specialUse in 0.2.29).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val always = PeerSyncApp.from(context).graph.settings.alwaysListen.value
        Log.d(TAG, "boot completed; alwaysListen=$always")
        if (always) {
            val ok = NodeService.start(context)
            Log.d(TAG, "start NodeService after boot: ${if (ok) "ok" else "refused"}")
        }
    }

    private companion object {
        const val TAG = "${AppInfo.NAME}/Boot"
    }
}
