package com.example.peersync

import com.example.peersync.data.PairingCode
import com.example.peersync.proto.FileServer
import com.example.peersync.text.PsText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the values gathered into AppInfo / PsText in 0.2.30 (unchanged from 0.2.29).
 * Changing the save folder name, part-file suffix, pairing code or iOS notification IDs would break
 * existing received files, partial files, notification removal and QR compatibility.
 */
class AppInfoTest {
    @Test
    fun valuesStayAsBefore() {
        assertEquals("PeerSync", AppInfo.NAME)
        assertEquals("PeerSync", AppInfo.DOWNLOAD_FOLDER)
        assertEquals(".peersync-part", AppInfo.PART_SUFFIX)
        assertEquals(".peersync-part", FileServer.PART_SUFFIX)
        assertEquals("logs", AppInfo.LOG_DIR)
        assertEquals("peersync", AppInfo.LOG_FILE_BASE)
        assertEquals("settings.json", AppInfo.SETTINGS_FILE)
        assertEquals("peersync", AppInfo.PREFS_NAME)
        assertEquals("PeerSyncOutbox", AppInfo.OUTBOX_FOLDER)
        assertEquals("PeerSync/Main", AppInfo.tag("Main"))
        assertEquals("PeerSync.incoming.accept", AppInfo.identifier("incoming.accept"))
        assertEquals("PeerSync.transfer.3", AppInfo.identifier("transfer.3"))
    }

    @Test
    fun pairingCodeFormatUnchanged() {
        val id = "ab".repeat(32)
        assertEquals("peersync:$id?name=Pixel", PairingCode.encode(id, "Pixel"))
        assertEquals(id to "Pixel", PairingCode.decode("PEERSYNC:$id?name=Pixel"))
    }

    @Test
    fun textsUnchanged() {
        assertEquals(120L, PsText.consentTimeoutSeconds)
        assertEquals("PeerSync is listening", PsText.listeningTitle)
        assertEquals("Scan the QR code shown in PeerSync on the other device", PsText.scanQrPrompt)
        assertEquals("Copy", PsText.offerAcceptLabel(true))
        assertEquals("Accept", PsText.offerAcceptLabel(false))
        assertEquals("Close", PsText.offerRejectLabel(true))
        assertEquals("Decline", PsText.offerRejectLabel(false))
    }
}
