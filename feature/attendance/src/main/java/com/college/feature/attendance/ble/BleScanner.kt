package com.college.feature.attendance.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock

class BleScanner(
    private val context: Context,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onPacketReceived: (rssi: Int, latencyMs: Double, messageText: String) -> Unit
) {
    // Bluetooth system service and scanner handles
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bleScanner get() = bluetoothManager.adapter?.bluetoothLeScanner

    // Starts low-latency BLE scan filtered by classroom service UUID
    fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(BleConstants.TEST_UUID), null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanner = bleScanner
        if (scanner == null) {
            println("BleScanner: Bluetooth adapter or scanner is null (Bluetooth disabled or permissions missing)")
            onScanningChanged(false)
            return
        }

        try {
            scanner.stopScan(scanCallback)
        } catch (_: Exception) {}

        scanner.startScan(listOf(filter), settings, scanCallback)
        onScanningChanged(true)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Stops active BLE scanning
    fun stopScanning() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            println("Error stopping BLE scan: ${e.message}")
        }
        onScanningChanged(false)
    }

    // Callback processing incoming BLE scan results
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val rssi = result.rssi
            val antennaTime = result.timestampNanos
            val osTime = SystemClock.elapsedRealtimeNanos()
            val latencyMs = (osTime - antennaTime) / 1_000_000.0

            // Extract service data payload for the classroom UUID
            val scanRecord = result.scanRecord ?: return
            val serviceDataBytes = scanRecord.getServiceData(ParcelUuid(BleConstants.TEST_UUID))
                ?: scanRecord.serviceData?.entries?.firstOrNull {
                    it.key.uuid == BleConstants.TEST_UUID
                }?.value

            if (serviceDataBytes == null || serviceDataBytes.isEmpty()) {
                return
            }

            val messageText = String(serviceDataBytes, Charsets.UTF_8)

            // Dispatch received packet to the main thread
            mainHandler.post {
                onPacketReceived(rssi, latencyMs, messageText)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            println("Scan failed with error code: $errorCode")
            mainHandler.post {
                onScanningChanged(false)
            }
        }
    }
}
