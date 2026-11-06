package com.example.peersync

import com.example.peersync.data.Incoming
import com.example.peersync.data.Pairing
import com.example.peersync.data.PairingErrorHandler
import com.example.peersync.data.Peer
import com.example.peersync.data.Presence
import com.example.peersync.data.Settings
import com.example.peersync.data.Transfers
import com.example.peersync.net.IrohNode

/**
 * The app-wide core, one instance. Built by each platform's entry point
 * (Android: PeerSyncApp, desktop: Main.kt, iOS: IosApp.start).
 */
class AppGraph(
    val settings: Settings,
    val node: IrohNode,
    val transfers: Transfers,
    /** Receiving side of "Send" (consent, saving). */
    val incoming: Incoming,
    /** Pairing requests (receiver consent and requester sending, 0.2.14). */
    val pairing: Pairing,
) {
    /**
     * Online status of paired devices, checked while Device Hub is shown (0.2.36).
     * The device name lives in [Settings.deviceName] since 0.2.36 (the user can change it).
     */
    val presence = Presence(settings, node, pairing)

    init {
        // Route FileServer OFFER / PUT to Incoming and PAIR / UNPAIR / PING to Pairing
        node.pushHandlerProvider = { peerId -> incoming.handlerFor(peerId) }
        node.pairHandlerProvider = { peerId -> pairing.handlerFor(peerId) }
        // a send refused for pairing reasons: request pairing / remove the peer (0.2.36, was the browse screen's job)
        transfers.pairingErrors = object : PairingErrorHandler {
            override fun onNotPaired(peer: Peer) = pairing.requestTo(peer)
            override fun onUnpaired(peer: Peer) = pairing.onUnpairedError(peer)
        }
    }
}
