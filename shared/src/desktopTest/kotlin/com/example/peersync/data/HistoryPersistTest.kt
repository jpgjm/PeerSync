package com.example.peersync.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** History persistence (0.2.21): survives a restart (re-creation) and is removed by clearing. */
class HistoryPersistTest {
    private class MemStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = map[key]?.toBoolean() ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
    }

    @Test
    fun pairingHistorySurvivesRecreate() {
        val store = MemStore()
        val h1 = PairingHistory(store)
        val id = h1.add("peerA".repeat(10), "Gerät-Ä", PairingLogKind.UnpairedByMe, "Notifying the other device…")
        h1.add("peerB".repeat(10), "Gerät-B", PairingLogKind.RequestReceived)
        h1.updateDetail(id, "Notified the other device.")

        val h2 = PairingHistory(store)
        assertEquals(2, h2.entries.value.size)
        assertEquals("Gerät-B", h2.entries.value.first().peerName) // newest first
        assertEquals("Notified the other device.", h2.entries.value.last().detail)
        // IDs continue from where they left off
        val next = h2.add("peerC", "Gerät-C", PairingLogKind.Added)
        assertTrue(next > id)

        h2.clear()
        assertEquals(0, PairingHistory(store).entries.value.size)
    }

    @Test
    fun transferItemRoundTripsWithOldRowsMissingTimes() {
        val json = Json { ignoreUnknownKeys = true }
        val items = listOf(
            TransferItem(1001, "a.txt", "Gerät-Ä", TransferKind.Send, 5, 10, false, TransferState.Completed, startedAt = 123),
        )
        val s = json.encodeToString(ListSerializer(TransferItem.serializer()), items)
        assertEquals(items, json.decodeFromString(ListSerializer(TransferItem.serializer()), s))
        // entries without startedAt / finishedAt (removed fields or hand-written JSON) still load
        val old = """[{"id":7,"fileName":"b","peerName":"p"}]"""
        val r = json.decodeFromString(ListSerializer(TransferItem.serializer()), old).single()
        assertEquals(0L, r.startedAt)
        assertEquals(TransferState.Running, r.state)
    }
}
