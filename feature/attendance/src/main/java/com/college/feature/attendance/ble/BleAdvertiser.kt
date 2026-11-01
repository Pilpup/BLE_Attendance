package com.college.feature.attendance.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log

class BleAdvertiser(
    private val context: Context,
    private val onBroadcastingChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "BleAdvertiser"
    }

    // Bluetooth system service and advertiser handles
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bleAdvertiser get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // State listener for active BLE advertising
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Radio broadcasting started successfully")
            onBroadcastingChanged(true)
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Broadcasting failed with error code: $errorCode")
            onBroadcastingChanged(false)
        }
    }

    // Broadcasts token using legacy BLE advertising packet (31 bytes max)
    fun startBroadcasting(selectedAdvMode: Int, selectedTxPower: Int, broadcastToken: String) {
        val advertiser = bleAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser not available or permissions missing")
            onBroadcastingChanged(false)
            return
        }

        // Convert token string once to avoid duplicate allocations
        val tokenBytes = broadcastToken.toByteArray(Charsets.UTF_8)
        if (tokenBytes.size > BleConstants.MAX_LEGACY_PAYLOAD_BYTES) {
            Log.w(TAG, "broadcastToken size (${tokenBytes.size}B) exceeds legacy limit (${BleConstants.MAX_LEGACY_PAYLOAD_BYTES}B)")
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(selectedAdvMode)
            .setTxPowerLevel(selectedTxPower)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(BleConstants.PARCEL_UUID, tokenBytes)
            .build()

        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising: ${e.message}", e)
            onBroadcastingChanged(false)
        }
    }

    // Stops active BLE advertisement
    fun stopBroadcasting() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertisement: ${e.message}")
        }
        onBroadcastingChanged(false)
    }
}
