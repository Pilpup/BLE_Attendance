package com.college.feature.attendance.ble

import java.util.UUID
import android.Manifest
import android.os.Build

object BleConstants {
    // Service UUID advertised and filtered for classroom attendance discovery
    val TEST_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    // Runtime BLE, camera, and microphone permissions required based on Android API level
    val neededPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
