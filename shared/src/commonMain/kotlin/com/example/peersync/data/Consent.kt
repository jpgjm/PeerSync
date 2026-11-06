package com.example.peersync.data

import com.example.peersync.platform.epochMillis
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Shared parts for exchanges that wait for the other device's decision (Send = OFFER, pairing = PAIR) (0.2.25).
 *
 * Up to 0.2.24 Incoming, Pairing and Transfers each had duplicate copies of:
 * token generation (Incoming, Pairing), polling for the decision (Transfers.send, Pairing.requestTo),
 * how long to keep the result after the decision (Incoming.REJECTED_KEEP_MS, Pairing.RESULT_KEEP_MS, both 5 min).
 * Timeout and poll interval remain Transfers.CONSENT_TIMEOUT_MS / POLL_INTERVAL_MS.
 */
internal object Consent {
    /** How long to keep the result after the decision so the other device's polling can read it. */
    const val DECISION_KEEP_MS = 5 * 60_000L

    /** Token identifying an offer or request (24 lowercase letters and digits). */
    fun newToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(24) { append(chars[Random.nextInt(chars.length)]) } }
    }

    /**
     * Calls [poll] every POLL_INTERVAL_MS until the other device decides.
     * After CONSENT_TIMEOUT_MS it fails with "The other device didn't respond (120 s)."
     */
    suspend fun <T> awaitDecision(first: T, isPending: (T) -> Boolean, poll: suspend (T) -> T): T {
        var resp = first
        if (!isPending(resp)) return resp
        val deadline = epochMillis() + Transfers.CONSENT_TIMEOUT_MS
        while (isPending(resp)) {
            if (epochMillis() > deadline) {
                throw IllegalStateException("The other device didn't respond (${Transfers.CONSENT_TIMEOUT_MS / 1000} s).")
            }
            delay(Transfers.POLL_INTERVAL_MS)
            resp = poll(resp)
        }
        return resp
    }
}

/** Peer name with characters invalid in folder names replaced by "_" (shared by download and receive: <save to>/<peer name>/). */
internal fun String.safePeerDirName(): String =
    map { c -> if (c in "\\/:*?\"<>|") '_' else c }.joinToString("").ifBlank { "peer" }
