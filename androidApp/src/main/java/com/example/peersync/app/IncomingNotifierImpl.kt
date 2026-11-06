package com.example.peersync.app

import com.example.peersync.AppInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.peersync.R
import com.example.peersync.data.IncomingNotifier
import com.example.peersync.data.IncomingOffer
import com.example.peersync.data.PairingNotifier
import com.example.peersync.data.PairingRequest
import com.example.peersync.data.ReceivedText
import com.example.peersync.data.UnpairNotice
import com.example.peersync.text.PsText

private const val TAG = "${AppInfo.NAME}/IncomingNotify"

/**
 * System notification for an incoming "Send" awaiting consent (like QSProbe's consent notification).
 * Has "Accept" / "Decline" buttons (only paired devices can send, so there's no confirmation code).
 * In the foreground an in-app dialog also shows, but the notification is always posted and removed once decided.
 * 0.2.23: text comes from PsText (shared with dialogs, iOS, Windows); posting moved to NotificationHelpers.
 */
class IncomingNotifierImpl(private val context: Context) : IncomingNotifier, PairingNotifier {
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        nm.ensureChannel(CHANNEL_ID, "Incoming requests", NotificationManager.IMPORTANCE_HIGH)
    }

    /** Common shape for decision notifications (Send, pairing request): high priority, ongoing, 120 s timeout, two buttons. */
    private fun decisionNotification(
        title: String, text: String, bigText: String, openRequestCode: Int,
        acceptLabel: String, accept: PendingIntent, rejectLabel: String, reject: PendingIntent,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        .setContentIntent(context.openAppIntent(openRequestCode))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setOngoing(true)
        .setTimeoutAfter(com.example.peersync.data.Transfers.CONSENT_TIMEOUT_MS)
        .addAction(0, acceptLabel, accept)
        .addAction(0, rejectLabel, reject)
        .build()

    override fun onOffer(offer: IncomingOffer) {
        val id = notificationId(offer.token)
        val body = if (offer.isTextOnly) {
            PsText.offerTextQuestion(offer.texts.sumOf { it.length })
        } else {
            PsText.offerFilesQuestion(offer.count, offer.totalBytes)
        }
        val detail = if (offer.isTextOnly) {
            offer.texts.joinToString("\n\n").take(300)
        } else {
            PsText.itemListSummary(offer.items.map { it.relPath }, max = 5)
        }
        val n = decisionNotification(
            title = PsText.offerTitle(offer.peerName, offer.isTextOnly),
            text = body,
            bigText = "$body\n$detail",
            openRequestCode = id + 2,
            acceptLabel = PsText.offerAcceptLabel(offer.isTextOnly),
            accept = decisionIntent(id, offer.token, context.appAction(ACTION_ACCEPT)),
            rejectLabel = PsText.offerRejectLabel(offer.isTextOnly),
            reject = decisionIntent(id + 1, offer.token, context.appAction(ACTION_REJECT)),
        )
        nm.notifyLogged(TAG, id, n, "offer notification posted: ${offer.peerName} ${offer.count} items")
    }

    override fun onOfferResolved(token: String) {
        nm.cancel(notificationId(token))
    }

    // ---- Pairing requests (0.2.14). Like "Send" consent, the notification has "Allow" / "Cancel" buttons ----

    override fun onRequest(request: PairingRequest) {
        val id = notificationId(request.token)
        val n = decisionNotification(
            title = PsText.pairRequestTitle,
            text = "Pairing request from ${request.peerName}",
            bigText = PsText.pairRequestBody(request.peerName, request.shortId),
            openRequestCode = id + 2,
            acceptLabel = PsText.allow,
            accept = decisionIntent(id, request.token, context.appAction(ACTION_PAIR_ACCEPT)),
            rejectLabel = PsText.cancel,
            reject = decisionIntent(id + 1, request.token, context.appAction(ACTION_PAIR_REJECT)),
        )
        nm.notifyLogged(TAG, id, n, "pairing notification posted: ${request.peerName}")
    }

    override fun onRequestResolved(token: String) {
        nm.cancel(notificationId(token))
    }

    // ---- The other device unpaired (0.2.21; since 0.2.22 just a notice that it was removed automatically, no buttons) ----

    override fun onUnpaired(notice: UnpairNotice) {
        val id = unpairNotificationId(notice.peerId)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(PsText.unpairedTitle)
            .setContentText("${notice.peerName} unpaired from this device.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(PsText.unpairedBody(notice.peerName, notice.shortId)))
            .setContentIntent(context.openAppIntent(id))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        nm.notifyLogged(TAG, id, n, "unpair notification posted: ${notice.peerName}")
    }

    override fun onUnpairResolved(peerId: String) {
        nm.cancel(unpairNotificationId(peerId))
    }

    /** ID range that doesn't overlap request/offer notifications (50_000+). */
    private fun unpairNotificationId(peerId: String): Int = 200_000 + (peerId.hashCode() and 0x7FFF) * 4

    /**
     * Text was accepted. Only the foreground app can write the clipboard (Android 10+), so the
     * foreground UI (App) does the copy. If the app isn't in front, post a notification asking to open it and copy.
     */
    override fun onTextAccepted(text: ReceivedText) {
        if (MainActivity.isInForeground) {
            Log.d(TAG, "text accepted while foreground; UI copies it")
            return
        }
        val id = notificationId(text.token) + 3
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(PsText.offerTitle(text.peerName, isText = true))
            .setContentText(PsText.textTapToCopy)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.text.take(300)))
            .setContentIntent(context.openAppIntent(id))
            .setAutoCancel(true)
            .build()
        nm.notifyLogged(TAG, id, n)
    }

    /** Notification buttons (Accept, Decline, Allow, Cancel), told apart by action. */
    private fun decisionIntent(requestCode: Int, token: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, OfferDecisionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notificationId(token: String): Int = 50_000 + (token.hashCode() and 0x7FFF) * 4

    /** Handles the notification's "Accept" / "Decline" (and pairing "Allow" / "Cancel"). */
    class OfferDecisionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val graph = PeerSyncApp.from(context).graph
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
            when (intent.action) {
                context.appAction(ACTION_ACCEPT) -> graph.incoming.decide(token, accept = true)
                context.appAction(ACTION_REJECT) -> graph.incoming.decide(token, accept = false)
                context.appAction(ACTION_PAIR_ACCEPT) -> graph.pairing.decide(token, accept = true)
                context.appAction(ACTION_PAIR_REJECT) -> graph.pairing.decide(token, accept = false)
                else -> return
            }
            Log.d(TAG, "decision from notification: ${intent.action}")
        }
    }

    companion object {
        private const val CHANNEL_ID = "incoming"
        // Action name suffix; the package-name prefix is added by Context.appAction (0.2.30)
        private const val ACTION_ACCEPT = "ACCEPT_OFFER"
        private const val ACTION_REJECT = "REJECT_OFFER"
        private const val ACTION_PAIR_ACCEPT = "ACCEPT_PAIR"
        private const val ACTION_PAIR_REJECT = "REJECT_PAIR"
        private const val EXTRA_TOKEN = "token"
    }
}
