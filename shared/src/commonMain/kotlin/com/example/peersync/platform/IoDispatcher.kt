package com.example.peersync.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking I/O.
 * `Dispatchers.IO` is not public in the kotlinx-coroutines common API
 * (compiling for iOS fails with "it is internal in Dispatchers"), so it is
 * wrapped in expect/actual. JVM returns Dispatchers.IO, iOS returns the native
 * Dispatchers.IO extension (import kotlinx.coroutines.IO).
 */
expect val ioDispatcher: CoroutineDispatcher
