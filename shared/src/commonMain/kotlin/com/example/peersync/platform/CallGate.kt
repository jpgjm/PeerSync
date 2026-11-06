package com.example.peersync.platform

import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Counts calls that are using a native handle so it is freed only after they all return (0.2.37).
 *
 * Used by the iOS FFI wrappers (node and connection handles of the Rust peersync-iroh library):
 * stop the handle (calls blocked in native code return), wait until no call is inside, then free it.
 * Up to 0.2.36 the node was closed and freed in one step while accept / wait_online were still
 * running on other threads, which corrupted memory (iOS crash on repeated "Restart").
 *
 * Common code (no platform API) so the desktop tests can check it.
 */
@OptIn(ExperimentalAtomicApi::class)
class CallGate {
    private val active = AtomicInt(0)
    private val closed = AtomicBoolean(false)

    /** True once [close] has been called. */
    val isClosed: Boolean get() = closed.load()

    /** Calls currently inside [enter] (for tests and logs). */
    val activeCalls: Int get() = active.load()

    /**
     * Runs [block] unless the gate is closed (then returns null without running it).
     * Count first, then check [closed]: a call that sees "open" is always counted, so [awaitIdle] waits for it.
     */
    fun <R : Any> enter(block: () -> R?): R? {
        active.incrementAndFetch()
        try {
            if (closed.load()) return null
            return block()
        } finally {
            active.decrementAndFetch()
        }
    }

    /** Closes the gate. False if it was already closed (the caller must not stop / free twice). */
    fun close(): Boolean = closed.compareAndSet(false, true)

    /** Waits until every call that entered before [close] has returned. */
    suspend fun awaitIdle(pollMs: Long = 10) {
        while (active.load() > 0) delay(pollMs)
    }
}
