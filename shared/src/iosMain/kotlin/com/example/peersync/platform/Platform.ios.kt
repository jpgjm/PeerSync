package com.example.peersync.platform

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

private fun formatter(pattern: String): NSDateFormatter = NSDateFormatter().apply {
    locale = NSLocale("ja_JP")
    dateFormat = pattern
}

actual fun nowTimeString(): String = formatter("HH:mm:ss").stringFromDate(NSDate())

actual fun nowLogTimestamp(): String = formatter("yyyy-MM-dd HH:mm:ss.SSS").stringFromDate(NSDate())

actual fun formatDateTime(epochMillis: Long): String =
    formatter("yyyy/MM/dd HH:mm").stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))

@OptIn(ExperimentalTime::class)
actual fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
