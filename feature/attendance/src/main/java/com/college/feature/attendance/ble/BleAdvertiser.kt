package com.college.feature.attendance.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid

class BleAdvertiser(
    private val context: Context,
    private val onBroadcastingChanged: (Boolean) -> Unit
) {
    // Bluetooth system service and advertiser handles
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bleAdvertiser get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // State listener for active BLE advertising
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            println("Success: Radio broadcasting")
            onBroadcastingChanged(true)
        }
        override fun onStartFailure(errorCode: Int) {
            println("Fail: Error Code: $errorCode")
            onBroadcastingChanged(false)
        }
    }

    // Broadcasts token using legacy BLE advertising packet (31 bytes max)
    fun startBroadcasting(selectedAdvMode: Int, selectedTxPower: Int, broadcastToken: String) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(selectedAdvMode)
            .setTxPowerLevel(selectedTxPower)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleConstants.TEST_UUID), broadcastToken.toByteArray(Charsets.UTF_8))
            .build()

        val advertiser = bleAdvertiser
        if (advertiser == null) {
            println("BleAdvertiser: BluetoothLeAdvertiser not available or permissions missing")
            onBroadcastingChanged(false)
            return
        }

        val tokenBytes = broadcastToken.toByteArray(Charsets.UTF_8)
        if (tokenBytes.size > 24) {
            println("Warning: broadcastToken size (${tokenBytes.size} bytes) may exceed BLE payload limit")
        }

        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    // Stops active BLE advertisement
    fun stopBroadcasting() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            println("Error stopping BLE advertisement: ${e.message}")
        }
        onBroadcastingChanged(false)
    }
}
