package com.example.peersync.net

import com.example.peersync.proto.FrameIn
import com.example.peersync.proto.FrameOut

/**
 * Minimal abstraction over iroh Endpoint / Connection.
 *  - Android / desktop: official Kotlin bindings computer.iroh (iroh 1.2.0)
 *  - iOS: our own Rust FFI peersync-iroh (iroh 1.x, C ABI) via cinterop
 * Both are wire-compatible (relay/handshake match across iroh 1.x).
 */
enum class PathKind { Direct, Relay, Other }

data class PathInfo(
    val kind: PathKind,
    val rttMs: Long,
    val selected: Boolean,
)

interface PsConnection {
    /** Peer's device ID (64 hex chars). */
    val remoteId: String

    /** Opens a new bidirectional stream from this side. */
    suspend fun openBi(): Pair<FrameOut, FrameIn>

    /** Accepts a stream opened by the peer. null once the connection is closed. */
    suspend fun acceptBi(): Pair<FrameIn, FrameOut>?

    fun isClosed(): Boolean

    /** Snapshot of paths, or null if unavailable. */
    fun paths(): List<PathInfo>?

    suspend fun close(code: Long, reason: String)
}

interface PsEndpoint {
    /** Waits for the home relay connection. true if connected. */
    suspend fun waitOnline(timeoutMs: Long): Boolean

    /** URL of the connected home relay (null if unknown). */
    fun homeRelayUrl(): String?

    /**
     * Accept before pairing. Returns once a connection with a matching ALPN is established.
     * null after shutdown.
     */
    suspend fun accept(): PsConnection?

    suspend fun connect(peerId: String): PsConnection

    suspend fun shutdown()
}

/** Opens an Endpoint. Empty/null relayUrl means the iroh public relay. */
expect suspend fun bindPsEndpoint(secretKey: ByteArray, relayUrl: String?): PsEndpoint

/** Generates a secret key (32 bytes). */
expect fun generateSecretKey(): ByteArray

/** Computes the device ID (public key as 64 hex chars) from the secret key. */
expect fun endpointIdFromSecret(secretKey: ByteArray): String

/** Validates device ID format (an iroh device ID is a 32-byte public key as 64 hex chars). */
fun looksLikeEndpointId(s: String): Boolean =
    s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
