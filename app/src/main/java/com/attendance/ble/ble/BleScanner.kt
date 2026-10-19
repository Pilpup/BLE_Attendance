package com.attendance.ble.ble

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
){
    // grabs main bluetooth controller
    // by lazy - only run this code and wake up hardware when I ask for it
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // get() will now ask android for fresh connection every time
    // Scanner
    private val bleScanner get() = bluetoothManager.adapter?.bluetoothLeScanner

    fun startScanning(){
        // ignore all the other peripherals, wake up only when it finds our TEST_UUID in service data
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(BleConstants.TEST_UUID), null)
            .build()

        // continuous scanning
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

    fun stopScanning(){
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            println("Error stopping BLE scan: ${e.message}")
        }
        onScanningChanged(false)
    }

    private val scanCallback = object : ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult){
            super.onScanResult(callbackType, result)

            // signal strength (dbm)
            val rssi = result.rssi

            // calculate latency: 
            // time when antenna recieved the packets
            val antennaTime = result.timestampNanos

            // current OS clock time
            val osTime = SystemClock.elapsedRealtimeNanos()

            // 1ms = 1,000,000ns
            // .0 at the end - do floating division
            val latencyMs = (osTime - antennaTime) / 1_000_000.0

            // Search packet for labeled TEST_UUID
            val scanRecord = result.scanRecord ?: return
            val serviceDataBytes = scanRecord.getServiceData(ParcelUuid(BleConstants.TEST_UUID))
                ?: scanRecord.serviceData?.entries?.firstOrNull { 
                    it.key.uuid == BleConstants.TEST_UUID 
                }?.value

            // If the packet has no matching service data, it is ambient BLE noise / hardware filter leakage.
            // Drop it immediately instead of passing "No data" to the app.
            if (serviceDataBytes == null || serviceDataBytes.isEmpty()) {
                return
            }

            val messageText = String(serviceDataBytes, Charsets.UTF_8)

            mainHandler.post {
                onPacketReceived(rssi, latencyMs, messageText)
            }
        }

        override fun onScanFailed(errorCode: Int){
            println("Scan failed with error code: $errorCode")
            mainHandler.post {
                onScanningChanged(false)
            }
        }
    }
}
