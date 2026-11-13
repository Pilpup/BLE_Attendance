package com.college.feature.attendance.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class BleScanner(
    private val context: Context,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onPacketReceived: (rssi: Int, latencyMs: Double, messageText: String) -> Unit
) {
    companion object {
        private const val TAG = "BleScanner"

        // R11 + H5: payload shapes the VM consumes — teacher beacon (legacy
        // S:/SESSION: and H5 compact '/'+token+wire[+sem]), air verdict (legacy
        // V: and H5 compact '\'+id), and 10-char peer probe "<session4><peer6>".
        // VM further constrains probes to the latched session; length==10 stays
        // the looser superset here. Legacy prefixes kept for mixed-version safety.
        fun looksLikeAttendanceMessage(messageText: String): Boolean {
            val msg = messageText.trim()
            if (msg.isEmpty()) return false
            return msg.startsWith("/") ||      // H5 beacon: /TTTTssss[sem]
                    msg.startsWith("\\") ||    // H5 verdict: \IIII
                    msg.startsWith("S:") ||
                    msg.startsWith("SESSION:") ||
                    msg.startsWith("V:") ||
                    msg.length == 10
        }
    }

    // Bluetooth system service and scanner handles
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bleScanner get() = bluetoothManager.adapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())

    // Scan-density state only: the caller decides when to (re)start the scan —
    // setScanLatency no longer restarts it. A density change takes effect on the
    // next startScanning(), which keeps the number of Android scan starts per
    // 30 s inside the 5-scan throttle budget (start latency swaps used to cost
    // one restart each).
    @Volatile
    private var lowLatencyScanning = false

    fun setScanLatency(lowLatency: Boolean) {
        lowLatencyScanning = lowLatency
    }

    // Starts a BLE scan filtered by classroom service UUID, at the density
    // currently selected for the discovery phase (BALANCED by default)
    fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceData(BleConstants.PARCEL_UUID, null)
            .build()

        val scanMode = if (lowLatencyScanning) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        val scanner = bleScanner
        if (scanner == null) {
            Log.w(TAG, "Bluetooth adapter or scanner is null (Bluetooth disabled or permissions missing)")
            onScanningChanged(false)
            return
        }

        try {
            scanner.stopScan(scanCallback)
        } catch (_: Exception) {}

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            onScanningChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan: ${e.message}", e)
            onScanningChanged(false)
        }
    }

    // Stops active BLE scanning
    fun stopScanning() {
        // Teardown also resets density: the next listening phase starts
        // BALANCED until a discovery window asks for LOW_LATENCY again.
        lowLatencyScanning = false
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan: ${e.message}")
        }
        onScanningChanged(false)
    }

    // Callback processing incoming BLE scan results
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            // R11 + K4 + H5: binder-thread gates BEFORE allocating a payload
            // String or posting to the main looper. Weak signals die first
            // (heap/GC cut), then raw-byte shape precheck rejects non-
            // attendance payloads without String alloc. Accepted shapes:
            // beacon '/' (H5) or S:/SESSION: (legacy), verdict '\' (H5) or V:
            // (legacy), and 10-char peer probe (length==10 superset, 4-char session + 6-char key).
            val rssi = result.rssi
            if (rssi < -95) return

            val antennaTime = result.timestampNanos
            val osTime = SystemClock.elapsedRealtimeNanos()
            val latencyMs = (osTime - antennaTime) / 1_000_000.0

            // Extract service data payload for the classroom UUID using singleton ParcelUuid
            val scanRecord = result.scanRecord ?: return
            val serviceDataBytes = scanRecord.getServiceData(BleConstants.PARCEL_UUID)
                ?: scanRecord.serviceData?.entries?.firstOrNull {
                    it.key.uuid == BleConstants.TEST_UUID
                }?.value

            if (serviceDataBytes == null || serviceDataBytes.isEmpty()) return

            // Raw-byte shape precheck (allocation-free): pass beacons, verdicts,
            // or exact 10-byte probe candidates.
            val len = serviceDataBytes.size
            val b0 = serviceDataBytes[0]
            val isBeacon = b0 == 0x2F.toByte() || // '/' H5 beacon
                (b0 == 0x53.toByte() && len >= 2 && serviceDataBytes[1] == 0x3A.toByte()) // 'S:'
            val isVerdict = b0 == 0x5C.toByte() || // '\' H5 verdict
                (b0 == 0x56.toByte() && len >= 2 && serviceDataBytes[1] == 0x3A.toByte()) // 'V:'
            if (!isBeacon && !isVerdict && len != 10) return

            val messageText = String(serviceDataBytes, Charsets.UTF_8)
            // Second gate: canonical shape check (trim-aware, SESSION: prefix).
            if (!looksLikeAttendanceMessage(messageText)) return

            // Dispatch received packet to the main thread
            mainHandler.post {
                onPacketReceived(rssi, latencyMs, messageText)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            mainHandler.post {
                onScanningChanged(false)
            }
        }
    }
}
