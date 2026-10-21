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
    // grabs main bluetooth controller
    // by lazy - only run this code and wake up hardware when I ask for it
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // get() will now ask android for fresh connection every time
    // Broadcaster
    private val bleAdvertiser get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // the listener, used to tell us if the radio wave fired successfully
    // object : one time use, nameless object on spot that inherits from AdvertiseCallback()
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

    fun startBroadcasting(selectedAdvMode: Int, selectedTxPower: Int, broadcastToken: String) {
        // config radio power
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(selectedAdvMode)
            .setTxPowerLevel(selectedTxPower)
            .setConnectable(false)
            .build()

        // config payload (31 bytes MAX)
        // Note: Using addServiceData with 16-bit UUID only needs 4 bytes header, leaving 24 bytes for token.
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

        // BLE legacy advertisement payload has a strict 31-byte limit.
        val tokenBytes = broadcastToken.toByteArray(Charsets.UTF_8)
        if (tokenBytes.size > 24) {
            println("Warning: broadcastToken size (${tokenBytes.size} bytes) may exceed BLE payload limit")
        }

        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    fun stopBroadcasting() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            println("Error stopping BLE advertisement: ${e.message}")
        }
        onBroadcastingChanged(false)
    }
}
