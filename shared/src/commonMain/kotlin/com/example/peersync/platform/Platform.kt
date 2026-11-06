package com.example.peersync.platform

/** Current time as "HH:mm:ss" (for event rows on the home screen). */
expect fun nowTimeString(): String

/** Current time as "yyyy-MM-dd HH:mm:ss.SSS" (for logs). */
expect fun nowLogTimestamp(): String

/** Unix epoch millis -> "yyyy/MM/dd HH:mm" (history screen, device local time). */
expect fun formatDateTime(epochMillis: Long): String

/** Current Unix epoch millis. */
expect fun epochMillis(): Long
