package com.example.peersync.data

import com.example.peersync.proto.FileNames
import com.example.peersync.proto.Wire
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of parts shared since 0.2.25 (numbering, progress throttling, awaiting a decision, settings, replacing stale tokens). */
class CommonPartsTest {
    private class MemStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = map[key]?.toBoolean() ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
    }

    @Test
    fun numberedNames() {
        assertEquals("a (1).txt", FileNames.numbered("a.txt", 1))
        assertEquals("a.tar (2).gz", FileNames.numbered("a.tar.gz", 2))
        assertEquals("README (1)", FileNames.numbered("README", 1))
        // the 0.2.24 JVM version produced " (1).bashrc"
        assertEquals(".bashrc (1)", FileNames.numbered(".bashrc", 1))
        val used = setOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", FileNames.firstFree("a.txt") { it in used })
        assertEquals("b.txt", FileNames.firstFree("b.txt") { it in used })
    }

    @Test
    fun progressThrottleLetsLastThrough() {
        val t = ProgressThrottle(intervalMs = 60_000)
        assertTrue(t.pass(1, 100))   // the first one passes
        assertFalse(t.pass(2, 100))  // throttled within the interval
        assertFalse(t.pass(99, 100))
        assertTrue(t.pass(100, 100)) // the final one always passes
    }

    @Test
    fun awaitDecisionPollsUntilDecided() = runBlocking {
        assertEquals(5, Consent.awaitDecision(5, { it < 5 }) { error("not called") })
        var calls = 0
        val r = Consent.awaitDecision(0, { it == 0 }) { calls++; if (calls < 2) 0 else 7 }
        assertEquals(7, r)
        assertEquals(2, calls)
        assertEquals(24, Consent.newToken().length)
        assertEquals("a_b_c", "a/b:c".safePeerDirName())
        assertEquals("peer", "  ".safePeerDirName())
    }

    @Test
    fun boolSettingsKeepKeysAndDefaults() {
        val store = MemStore()
        val s1 = Settings(store, defaultDeviceName = "me")
        // "Always listen" and "Keep screen on" start off on every platform (0.2.32 / 0.2.33)
        assertFalse(s1.alwaysListen.value)
        assertFalse(s1.keepScreenOn.value)
        assertFalse(s1.keepAliveAudio.value)
        assertFalse(s1.autoAcceptIncoming.value)
        assertEquals(Settings.SAVE_DEST_APP, s1.saveDestKind.value)
        s1.setAlwaysListen(true)
        s1.setAlwaysListen(false)
        s1.setAutoAcceptIncoming(true)
        s1.setSaveDestToken("/tmp/custom")
        s1.setSaveDestKind(Settings.SAVE_DEST_CUSTOM)
        assertFalse(s1.alwaysListen.value)
        // survives re-creation (storage keys same as 0.2.24)
        val s2 = Settings(store, defaultDeviceName = "me")
        assertFalse(s2.alwaysListen.value)
        assertTrue(s2.autoAcceptIncoming.value)
        assertEquals(Settings.SAVE_DEST_CUSTOM, s2.saveDestKind.value)
        assertEquals("/tmp/custom", s2.saveDestToken.value)
    }

    @Test
    fun deviceNameDefaultsToModelAndPersists() {
        val store = MemStore()
        val s1 = Settings(store, defaultDeviceName = "F-51F")
        assertEquals("F-51F", s1.deviceName)
        s1.setDeviceName("  Living room  ")
        assertEquals("Living room", s1.deviceName)
        assertEquals("Living room", Settings(store, defaultDeviceName = "F-51F").deviceName)
        // blank goes back to the model name; long names are cut to the wire limit
        s1.setDeviceName(" ")
        assertEquals("F-51F", s1.deviceName)
        s1.setDeviceName("x".repeat(Wire.MAX_NAME_CHARS + 5))
        assertEquals(Wire.MAX_NAME_CHARS, s1.deviceName.length)
    }

    @Test
    fun renamePeerOnlyChangesPairedPeers() {
        val store = MemStore()
        val s = Settings(store, defaultDeviceName = "me")
        s.addPeer(Peer("a".repeat(64), "Old"))
        assertTrue(s.renamePeer("a".repeat(64), "New"))
        assertFalse(s.renamePeer("a".repeat(64), "New")) // unchanged
        assertFalse(s.renamePeer("a".repeat(64), "  ")) // blank is ignored
        assertFalse(s.renamePeer("b".repeat(64), "Other")) // not paired
        assertEquals(listOf("New"), Settings(store, defaultDeviceName = "me").peers.value.map { it.name })
    }

    @Test
    fun resetIdentityGivesNewIdAndClearsPairs() {
        val store = MemStore()
        val s = Settings(store, defaultDeviceName = "me")
        s.setDeviceName("Desk")
        s.setRelayUrl("https://relay.example.com")
        val oldId = s.endpointId
        s.addPeer(Peer("a".repeat(64), "A"))
        s.removePeer("a".repeat(64), byMe = true)
        s.addPeer(Peer("c".repeat(64), "C"))
        assertTrue(s.isUnpairedByMe("a".repeat(64)))
        s.resetIdentity()
        assertNotEquals(oldId, s.endpointId)
        assertEquals(s.endpointId, s.endpointIdFlow.value)
        assertTrue(s.peers.value.isEmpty())
        assertFalse(s.isUnpairedByMe("a".repeat(64)))
        // name and relay stay; the new key survives re-creation
        val again = Settings(store, defaultDeviceName = "me")
        assertEquals(s.endpointId, again.endpointId)
        assertEquals("Desk", again.deviceName)
        assertEquals("https://relay.example.com", again.relayUrl.value)
        assertTrue(again.peers.value.isEmpty())
    }
}
