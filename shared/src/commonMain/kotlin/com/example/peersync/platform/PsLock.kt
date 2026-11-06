package com.example.peersync.platform

/**
 * Non-reentrant lock guarding short sections (for non-suspending code).
 * JVM uses synchronized, iOS pthread_mutex. Coroutine Mutex can't be used from non-suspend functions.
 */
expect class PsLock() {
    fun <T> withLock(block: () -> T): T
}
