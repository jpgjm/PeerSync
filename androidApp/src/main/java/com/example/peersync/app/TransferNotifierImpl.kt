package com.example.peersync.app

import com.example.peersync.AppInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.peersync.R
import com.example.peersync.data.TransferKind
import com.example.peersync.data.TransferNotifier
import com.example.peersync.data.Transfers
import com.example.peersync.ui.formatMb
import com.example.peersync.text.PsText

private const val TAG = "${AppInfo.NAME}/Notify"

/**
 * System notifications for transfer progress (same as ObtainX's download notification:
 * title + "x MB / y MB" + progress bar + Cancel). Notification part of the old Android data/Transfers.kt.
 *  - One notification per file. Removed when done; replaced by a failure notification on failure.
 *  - Cancel keeps the partial file (.peersync-part), so the next attempt resumes where it stopped.
 */
class TransferNotifierImpl(private val context: Context) : TransferNotifier {
    internal val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Referenced from CancelReceiver (set by PeerSyncApp). */
    var transfers: Transfers? = null

    init {
        nm.ensureChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW)
    }

    private fun verb(kind: TransferKind) = PsText.transferVerb(kind)

    override fun onStart(id: Int, fileName: String, kind: TransferKind) {
        notify(id, progress(fileName, 0, 0, indeterminate = true, cancelId = id, kind = kind))
    }

    override fun onProgress(id: Int, fileName: String, done: Long, total: Long, kind: TransferKind) {
        notify(id, progress(fileName, done, total, indeterminate = false, cancelId = id, kind = kind))
    }

    override fun onCompleted(id: Int, fileName: String, savedPath: String, kind: TransferKind) {
        nm.cancel(id)
        if (kind != TransferKind.Download) {
            // Users often aren't watching sends/receives, so leave one completion notification (dismissed on tap)
            notify(
                id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(PsText.transferDoneTitle(kind))
                    .setContentText(fileName)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    override fun onCancelled(id: Int, fileName: String, kind: TransferKind) {
        nm.cancel(id)
    }

    override fun onFailed(id: Int, fileName: String, reason: String, kind: TransferKind) {
        notify(id, failed(fileName, reason, kind))
    }

    private fun notify(id: Int, n: Notification) = nm.notifyLogged(TAG, id, n)

    private fun progress(
        name: String, done: Long, total: Long, indeterminate: Boolean, cancelId: Int, kind: TransferKind,
    ): Notification {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val cancel = PendingIntent.getBroadcast(
            context, cancelId,
            Intent(context, CancelReceiver::class.java).setAction(context.appAction(ACTION_CANCEL)).putExtra(EXTRA_ID, cancelId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 0.2.29: build Android 16's ProgressStyle with NotificationCompat.ProgressStyle
        // (up to 0.2.28 we branched on SDK 36 and re-applied it via Notification.Builder.recoverBuilder).
        // Looks the same: determinate fills a one-segment bar with the progress color; indeterminate animates.
        val style = NotificationCompat.ProgressStyle().setProgressIndeterminate(indeterminate)
        if (!indeterminate) {
            style.setStyledByProgress(true)
                .setProgress(percent)
                .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100)))
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("${verb(kind)}: $name")
            .setContentText(
                if (!indeterminate) "${formatMb(done)} / ${formatMb(total)}" else PsText.waitingText(kind),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, PsText.cancel, cancel)
            .setStyle(style)
            .build()
    }

    private fun failed(name: String, reason: String, kind: TransferKind): Notification {
        val hint = PsText.resumeHint(kind)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("${verb(kind)} failed: $name")
            .setContentText("$reason ($hint)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason ($hint)"))
            .setAutoCancel(true)
            .build()
    }

    /** Handles the notification's "Cancel" action. */
    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != context.appAction(ACTION_CANCEL)) return
            val id = intent.getIntExtra(EXTRA_ID, -1)
            if (id < 0) return
            PeerSyncApp.from(context).graph.transfers.cancel(id)
            PeerSyncApp.from(context).notifier.nm.cancel(id)
        }
    }

    companion object {
        private const val CHANNEL_ID = "transfer"
        private const val ACTION_CANCEL = "CANCEL_TRANSFER" // Prefixed with the package name (appAction, 0.2.30)
        private const val EXTRA_ID = "id"
    }
}
