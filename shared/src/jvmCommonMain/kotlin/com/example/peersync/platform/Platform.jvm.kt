package com.example.peersync.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun nowTimeString(): String =
    SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())

actual fun nowLogTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.JAPAN).format(Date())

actual fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))

actual fun epochMillis(): Long = System.currentTimeMillis()
