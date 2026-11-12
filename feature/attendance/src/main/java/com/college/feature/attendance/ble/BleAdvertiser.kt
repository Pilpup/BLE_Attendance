package com.college.feature.attendance.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
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

    // Active AdvertisingSet (API 26+): payload updates swap the advertised bytes
    // WITHOUT a stop/start cycle — the legacy API can only change data by
    // restarting, and rapid restart cycles silently killed beacons in the field.
    private var activeSet: AdvertisingSet? = null
    private var activeSetCallback: AdvertisingSetCallback? = null

    // Legacy-API fallback remembers the last settings so a payload update can
    // be expressed as one stop/start with identical parameters.
    private var lastAdvMode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    private var lastTxPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    // State listener for active BLE advertising (legacy path)
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

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(BleConstants.PARCEL_UUID, tokenBytes)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopAnyActiveSet(advertiser)
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                .setInterval(advModeToInterval(selectedAdvMode))
                .setTxPowerLevel(txPowerToLevel(selectedTxPower))
                .build()
            val callback = object : AdvertisingSetCallback() {
                override fun onAdvertisingEnabled(set: AdvertisingSet?, enabled: Boolean, status: Int) {
                    if (enabled && status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        activeSet = set
                        Log.i(TAG, "Radio broadcasting started successfully (AdvertisingSet)")
                        onBroadcastingChanged(true)
                    } else {
                        Log.e(TAG, "AdvertisingSet enable failed: status=$status")
                        onBroadcastingChanged(false)
                    }
                }

                override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
                    if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        // The previous payload stays on the air — consumers
                        // (countdown) tolerate a stale value; the next update retries.
                        Log.e(TAG, "Advertising data update failed: status=$status")
                    }
                }
            }
            activeSetCallback = callback
            lastAdvMode = selectedAdvMode
            lastTxPower = selectedTxPower
            try {
                advertiser.startAdvertisingSet(params, data, null, null, null, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting AdvertisingSet: ${e.message}", e)
                onBroadcastingChanged(false)
            }
        } else {
            lastAdvMode = selectedAdvMode
            lastTxPower = selectedTxPower
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(selectedAdvMode)
                .setTxPowerLevel(selectedTxPower)
                .setConnectable(false)
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
    }

    // Swaps the advertised payload WITHOUT stopping the transmission (API 26+).
    // Used for the synchronized game-start countdown: each second the beacon
    // gains a new G<k> value while the advertisement keeps running. On the
    // legacy API this degenerates to one stop/start with identical settings.
    fun updatePayload(broadcastToken: String) {
        val tokenBytes = broadcastToken.toByteArray(Charsets.UTF_8)
        if (tokenBytes.size > BleConstants.MAX_LEGACY_PAYLOAD_BYTES) {
            Log.w(TAG, "broadcastToken size (${tokenBytes.size}B) exceeds legacy limit (${BleConstants.MAX_LEGACY_PAYLOAD_BYTES}B)")
        }
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(BleConstants.PARCEL_UUID, tokenBytes)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val set = activeSet ?: return
            try {
                set.setAdvertisingData(data)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating advertising payload: ${e.message}", e)
            }
        } else {
            startBroadcasting(lastAdvMode, lastTxPower, broadcastToken)
        }
    }

    // Stops active BLE advertisement
    fun stopBroadcasting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bleAdvertiser?.let { stopAnyActiveSet(it) }
        } else {
            try {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping BLE advertisement: ${e.message}")
            }
        }
        onBroadcastingChanged(false)
    }

    private fun stopAnyActiveSet(advertiser: android.bluetooth.le.BluetoothLeAdvertiser) {
        val callback = activeSetCallback
        if (callback != null) {
            try {
                advertiser.stopAdvertisingSet(callback)
            } catch (_: Exception) {}
        }
        activeSet = null
        activeSetCallback = null
    }

    private fun advModeToInterval(advMode: Int): Int = when (advMode) {
        AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY -> AdvertisingSetParameters.INTERVAL_MIN
        AdvertiseSettings.ADVERTISE_MODE_BALANCED -> AdvertisingSetParameters.INTERVAL_LOW
        else -> AdvertisingSetParameters.INTERVAL_HIGH
    }

    private fun txPowerToLevel(txPower: Int): Int = when (txPower) {
        AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> AdvertisingSetParameters.TX_POWER_ULTRA_LOW
        AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> AdvertisingSetParameters.TX_POWER_LOW
        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> AdvertisingSetParameters.TX_POWER_MEDIUM
        else -> AdvertisingSetParameters.TX_POWER_HIGH
    }
}
