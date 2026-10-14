package com.attendance.ble.ble

import java.util.UUID
import android.Manifest
import android.os.Build

object BleConstants {
    val TEST_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    // check phone's andoid version to select rules to follow
    val neededPermissions = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}
