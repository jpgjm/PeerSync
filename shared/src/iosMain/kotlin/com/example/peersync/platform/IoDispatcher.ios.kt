package com.example.peersync.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO // native Dispatchers.IO extension (not in common)

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
