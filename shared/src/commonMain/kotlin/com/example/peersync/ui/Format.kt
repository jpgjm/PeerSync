package com.example.peersync.ui

import kotlin.math.roundToLong

/** One decimal place, locale-independent (like "%.1f"). */
internal fun dec1(v: Double): String {
    val scaled = (v * 10).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = if (scaled < 0) -scaled else scaled
    return "$sign${abs / 10}.${abs % 10}"
}

/** Two decimal places (like "%.2f"). */
internal fun dec2(v: Double): String {
    val scaled = (v * 100).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = if (scaled < 0) -scaled else scaled
    val frac = (abs % 100).toString().padStart(2, '0')
    return "$sign${abs / 100}.$frac"
}

/** Size for list display (offer dialog, history). */
fun sizeText(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${dec1(b / 1024.0)} KiB"
    b < 1024L * 1024 * 1024 -> "${dec1(b / 1024.0 / 1024)} MiB"
    else -> "${dec2(b / 1024.0 / 1024 / 1024)} GiB"
}

/** "x.x MB" for progress (base 1000, as in the old notifications and transfer list). */
fun formatMb(b: Long): String = "${dec1(b / 1000.0 / 1000.0)} MB"
