package com.example.peersync.proto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pairing code tests. Up to 0.2.34 this file also tested browsing and fetching the shared folder (LIST / GET)
 * over in-memory pipes; those ops were removed in 0.2.36 (PairProtocolTest checks they are now unknown ops).
 */
class ProtocolTest {
    @Test
    fun pairingCodeRoundTrip() {
        val id = "a".repeat(64)
        val code = com.example.peersync.data.PairingCode.encode(id, "REDMAGIC 9 Pro")
        val (gotId, gotName) = com.example.peersync.data.PairingCode.decode(code)!!
        assertEquals(id, gotId)
        assertEquals("REDMAGIC 9 Pro", gotName)
        // bare ID pasted
        assertEquals(id to null, com.example.peersync.data.PairingCode.decode("  $id "))
        // invalid
        assertEquals(null, com.example.peersync.data.PairingCode.decode("peersync:xyz"))
    }
}
