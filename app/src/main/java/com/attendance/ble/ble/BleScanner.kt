package com.attendance.ble.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
        // ignore all the other peripherals, wake up only why it finds out TEST_UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.TEST_UUID))
            .build()

        // continous scanning
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanner = bleScanner
        if (scanner == null) {
            throw SecurityException()
        }

        scanner.startScan(listOf(filter), settings, scanCallback)
        onScanningChanged(true)
    }

    fun stopScanning(){
        bleScanner?.stopScan(scanCallback)
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

            // basic message extraction:
            // search packet for labeled TEST_UUID
            val serviceDataBytes = result.scanRecord?.serviceData?.get(ParcelUuid(BleConstants.TEST_UUID))

            // val messageText: String
            // if(serviceDataBytes != null){
            //     messageText = String(serviceDataBytes, Charsets.UTF_8)
            // } else {
            //     messageText = "No Data"
            // }
            // kotlin shortcut way of doing the above if-else block:
            // ?.let - take the variable as "it" and let me do something with it in {...}
            // ?: - just an else block, if something goes null of left side
            val messageText = serviceDataBytes?.let {String(it, Charsets.UTF_8)} ?: "No data"

            onPacketReceived(rssi, latencyMs, messageText)
        }

        override fun onScanFailed(errorCode: Int){
            println("Scan failed with error code: $errorCode")
            onScanningChanged(false)
        }
    }
}
