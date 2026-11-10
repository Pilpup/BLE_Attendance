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

        // R11 (review §2.1): the full set of payload shapes AttendanceViewModel
        // actually consumes (trim + startsWith checks at :1315/:1341/:1368) —
        // teacher beacon "S:"/"SESSION:", air verdict "V:", and the 8-char peer
        // probe "<4-char session><4-char peer id>". The VM additionally
        // constrains probes to the latched session id, which the scanner cannot
        // know — so length==8 here must stay the looser superset. Everything
        // else (foreign advertisers, garbled fragments) previously crossed the
        // thread bridge only to be throttled-logged; now it never allocates or
        // posts at all.
        fun looksLikeAttendanceMessage(messageText: String): Boolean {
            val msg = messageText.trim()
            if (msg.isEmpty()) return false
            return msg.startsWith("S:") ||
                    msg.startsWith("SESSION:") ||
                    msg.startsWith("V:") ||
                    msg.length == 8
        }
    }

    // Bluetooth system service and scanner handles
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bleScanner get() = bluetoothManager.adapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())

    // Starts low-latency BLE scan filtered by classroom service UUID
    fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceData(BleConstants.PARCEL_UUID, null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
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

            // R11 (review §2.1): binder-thread gate BEFORE allocating a payload
            // String or posting to the main looper. A 100-phone room on
            // LOW_LATENCY previously meant thousands of posts/sec on weak
            // devices (jank + allocation churn). -95 dBm keeps
            // classroom-relevant traffic; weaker advertisements could only
            // ever become a throttled neglect log or false adjacency noise.
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

            if (serviceDataBytes == null || serviceDataBytes.isEmpty()) {
                return
            }

            val messageText = String(serviceDataBytes, Charsets.UTF_8)
            // Second gate: only payloads the ViewModel actually speaks
            // (teacher beacon / air verdict / 8-char peer probe) bridge to the
            // main thread. Foreign app instances and truncated fragments used
            // to cross the bridge only to be throttled-logged there.
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
