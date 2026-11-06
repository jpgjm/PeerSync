package com.example.peersync.app

import com.example.peersync.text.PsText
import com.example.peersync.AppInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.peersync.R

/**
 * Service that only keeps the process alive so paired devices can send to this one after the UI closes.
 * Started each time MainActivity launches (always listening).
 * The iroh node itself is owned by PeerSyncApp (AppGraph).
 *
 * 0.2.29: type changed from dataSync to specialUse. On Android 15+, dataSync
 *  - can't start from BOOT_COMPLETED (ForegroundServiceStartNotAllowedException)
 *  - can run only 6 hours in total per 24 hours (stopped via onTimeout)
 * which didn't fit "Always listen" (keep listening after restart). specialUse has neither limit.
 */
class NodeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's "Stop" turns "Always listen" off. Not started on next launch or restart either
            Log.d(TAG, "stop requested from notification; alwaysListen -> off")
            PeerSyncApp.from(this).graph.settings.setAlwaysListen(false)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        PeerSyncApp.from(this).graph.node.start()
        running = true
        Log.d(TAG, "foreground service started (startId=$startId)")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        running = false
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)
            .ensureChannel(CHANNEL_ID, PsText.listening, NotificationManager.IMPORTANCE_LOW)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(PsText.listeningTitle)
            .setContentText("Paired devices can send you files and text.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "${AppInfo.NAME}/Service"
        private const val CHANNEL_ID = "node"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"

        @Volatile
        var running = false
            private set

        /**
         * Starts listening. Where starting from the background isn't allowed (OEM restrictions etc.),
         * this throws ForegroundServiceStartNotAllowedException; log it and return false instead of crashing.
         */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, NodeService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is a subclass of IllegalStateException
            Log.w(TAG, "cannot start foreground service: $e")
            false
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NodeService::class.java).setAction(ACTION_STOP))
        }
    }
}
