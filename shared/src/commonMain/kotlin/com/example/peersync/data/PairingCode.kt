package com.example.peersync.data

import com.example.peersync.AppInfo
import com.example.peersync.net.looksLikeEndpointId

/**
 * Pairing code: peersync:<device ID>?name=<device name>.
 * Compatible with the old Android ui/Qr.kt, Windows Services/PairingCode.cs and the iOS version.
 */
object PairingCode {
    /** URI prefix `peersync:` (built from AppInfo.PAIRING_SCHEME since 0.2.30). */
    private const val PREFIX = "${AppInfo.PAIRING_SCHEME}:"

    fun encode(id: String, name: String): String =
        "$PREFIX$id?name=" + percentEncode(name)

    /** Extracts (id, name) from a QR string or a pasted ID. null if invalid. */
    fun decode(text: String): Pair<String, String?>? {
        val t = text.trim()
        val body = if (t.startsWith(PREFIX, ignoreCase = true)) {
            t.substring(PREFIX.length)
        } else {
            t
        }
        var id = body
        var name: String? = null
        val q = body.indexOf('?')
        if (q >= 0) {
            id = body.substring(0, q)
            for (kv in body.substring(q + 1).split('&')) {
                val eq = kv.indexOf('=')
                if (eq > 0 && kv.substring(0, eq) == "name") {
                    name = percentDecode(kv.substring(eq + 1)).takeIf { it.isNotEmpty() }
                }
            }
        }
        id = id.trim()
        return if (looksLikeEndpointId(id)) id to name else null
    }

    // ---- RFC 3986 percent-encoding (UTF-8). Compatible with Android's Uri.encode/decode ----

    private val unreserved = buildSet {
        ('A'..'Z').forEach { add(it) }
        ('a'..'z').forEach { add(it) }
        ('0'..'9').forEach { add(it) }
        addAll(listOf('-', '_', '.', '~'))
    }

    private fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (b >= 0 && c in unreserved) {
                sb.append(c)
            } else {
                val v = b.toInt() and 0xFF
                sb.append('%')
                sb.append("0123456789ABCDEF"[v ushr 4])
                sb.append("0123456789ABCDEF"[v and 0xF])
            }
        }
        return sb.toString()
    }

    private fun percentDecode(s: String): String {
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length + 1 && s.length - i >= 3 -> {
                    val hex = s.substring(i + 1, i + 3)
                    val v = hex.toIntOrNull(16)
                    if (v != null) {
                        bytes.add(v.toByte())
                        i += 3
                    } else {
                        bytes.add(c.code.toByte())
                        i++
                    }
                }
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                else -> {
                    for (b in c.toString().encodeToByteArray()) bytes.add(b)
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
