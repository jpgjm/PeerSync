package com.example.peersync.proto

/**
 * Server-side entry point for pairing requests, unpair notices and presence checks (per peer, 0.2.14).
 * Implemented in data/Pairing.kt (awaiting consent, adding to the allow list).
 */
interface PairHandler {
    /** Request. Returns ACCEPTED at once if the peer is already registered, otherwise a PENDING token. */
    suspend fun request(name: String): OfferResponse

    /** Polls for consent. ProtocolException if the token isn't this peer's. */
    suspend fun status(token: String): OfferResponse

    /** Unpair notification (0.2.21). name is the unpairing device's name. */
    suspend fun unpair(name: String) {}

    /**
     * Presence check from a paired device (0.2.36). [name] is the sender's current device name
     * (updates the name kept for it). Returns this device's current name.
     */
    suspend fun ping(name: String): String
}
