package com.example.peersync.log

import com.example.peersync.platform.nowLogTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    val line: String get() = "$timestamp [${level.label}] $tag: $message"
}

/** Platform output sink (Logcat / file / print). */
fun interface LogSink {
    fun write(entry: LogEntry)
}

/**
 * In-app log. Keeps the latest 3000 entries in memory (for the Logs screen)
 * and also forwards to the platform LogSink (Logcat, file) (spec 3: debug logging is mandatory).
 * On iOS the Swift service layer also writes here via the framework.
 */
object PsLog {
    private const val MEMORY_LIMIT = 3000

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private var sinks: List<LogSink> = emptyList()

    fun addSink(sink: LogSink) {
        sinks = sinks + sink
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(nowLogTimestamp(), level, tag, message)
        // update is atomic via CAS (safe from multiple threads)
        _entries.update { cur ->
            if (cur.size >= MEMORY_LIMIT) cur.drop(cur.size - MEMORY_LIMIT + 1) + entry else cur + entry
        }
        sinks.forEach { it.write(entry) }
    }

    /** For Share / Copy on the Logs screen. */
    fun snapshot(minLevel: LogLevel = LogLevel.DEBUG): String =
        _entries.value.filter { it.level.ordinal >= minLevel.ordinal }
            .joinToString("\n") { it.line }

    fun clear() {
        _entries.value = emptyList()
    }
}
