package com.example.peersync.app

import android.app.Activity
import android.util.Log
import com.example.peersync.AppInfo
import com.example.peersync.text.PsText
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private const val TAG = "${AppInfo.NAME}/QR"

/**
 * QR scanning with Google Code Scanner (0.2.38, replaces zxing-android-embedded).
 * The camera screen belongs to Google Play services, so this app needs no CAMERA permission.
 * Same flow as QRScan 1.0.1: if the scanner module isn't on the device yet, download it with
 * ModuleInstallClient first, then open the scanner.
 */
class CodeScanner(private val activity: Activity) {
    private val scanner: GmsBarcodeScanner = GmsBarcodeScanning.getClient(
        activity,
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build(),
    )
    private val modules = ModuleInstall.getClient(activity)

    /** True while the scanner is open or its module is downloading (further taps are ignored). */
    private var busy = false

    /**
     * @param onResult QR text, or null when cancelled.
     * @param onMessage Status / error text for a snackbar.
     */
    fun scan(onResult: (String?) -> Unit, onMessage: (String) -> Unit) {
        if (busy) {
            Log.d(TAG, "scan: busy, ignored")
            return
        }
        busy = true
        modules.areModulesAvailable(scanner)
            .addOnSuccessListener { r ->
                Log.d(TAG, "scan: module available=${r.areModulesAvailable()}")
                if (r.areModulesAvailable()) open(onResult, onMessage) else install(onResult, onMessage)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "scan: module check failed", e)
                fail(onMessage, e)
            }
    }

    private fun install(onResult: (String?) -> Unit, onMessage: (String) -> Unit) {
        onMessage(PsText.qrScannerDownloading)
        lateinit var listener: InstallStatusListener
        listener = InstallStatusListener { update ->
            update.progressInfo?.let { p ->
                Log.d(TAG, "install: ${p.bytesDownloaded}/${p.totalBytesToDownload} bytes")
            }
            Log.d(TAG, "install: state=${update.installState}")
            when (update.installState) {
                InstallState.STATE_COMPLETED -> {
                    modules.unregisterListener(listener)
                    open(onResult, onMessage)
                }
                InstallState.STATE_CANCELED, InstallState.STATE_FAILED -> {
                    modules.unregisterListener(listener)
                    fail(onMessage, IllegalStateException("module install state=${update.installState}"))
                }
                else -> Unit
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build()
        Log.i(TAG, "install: requesting the scanner module")
        modules.installModules(request)
            .addOnSuccessListener { resp ->
                Log.d(TAG, "install: accepted, alreadyInstalled=${resp.areModulesAlreadyInstalled()}")
                if (resp.areModulesAlreadyInstalled()) {
                    modules.unregisterListener(listener)
                    open(onResult, onMessage)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "install: request failed", e)
                modules.unregisterListener(listener)
                fail(onMessage, e)
            }
    }

    private fun open(onResult: (String?) -> Unit, onMessage: (String) -> Unit) {
        Log.d(TAG, "scan: opening the scanner")
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                busy = false
                val text = barcode.rawValue
                // the content is the pairing code (ID + name); log only its length
                Log.i(TAG, "scan: format=${barcode.format} length=${text?.length}")
                onResult(text)
            }
            .addOnCanceledListener {
                Log.d(TAG, "scan: cancelled")
                busy = false
                onResult(null)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "scan: failed", e)
                fail(onMessage, e)
            }
    }

    private fun fail(onMessage: (String) -> Unit, e: Exception) {
        busy = false
        onMessage(PsText.qrScannerUnavailable(e.message ?: e.javaClass.simpleName))
    }
}
