package com.attendance.ble.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid

class BleAdvertiser(
    private val context: Context,
    private val onBroadcastingChanged: (Boolean) -> Unit
){
    // grabs main bluetooth controller
    // by lazy - only run this code and wake up hardware when I ask for it
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // get() will now ask android for fresh connection every time
    // Broadcaster
    private val bleAdvertiser get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // the listener, used to tell us if the radio wave fired successfully
    // object : one time use, nameless object on spot that inherits from AdvertiseCallback()
    private val advertiseCallback = object : AdvertiseCallback(){
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?){
            println("Success: Radio broadcasting")
            onBroadcastingChanged(true)
        }
        override fun onStartFailure(errorCode: Int){
            println("Fail: Error Code: $errorCode")
            onBroadcastingChanged(false)
        }
    }

    fun startBroadcasting(selectedAdvMode: Int, selectedTxPower: Int, broadcastTokken: String){
        // config radio power
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(selectedAdvMode)
            .setTxPowerLevel(selectedTxPower)
            .setConnectable(false)
            .build()

        // config payload (31 bytes MAX)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.TEST_UUID))
            .addServiceData(ParcelUuid(BleConstants.TEST_UUID), broadcastTokken.toByteArray(Charsets.UTF_8))
            .build()

        val advertiser = bleAdvertiser
        if(advertiser == null){
            // missions permission
            throw SecurityException()
        }

        // removed try/catch so the button can catch it and reset the UI
        // now it won't say startBroadcasting = true, while it's failing in bg
        // fails when bluetooth is ON, but pemissions are revoked
        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stopBroadcasting(){
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        onBroadcastingChanged(false)
    }
}
