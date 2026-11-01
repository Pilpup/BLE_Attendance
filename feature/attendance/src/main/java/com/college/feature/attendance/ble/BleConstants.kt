package com.college.feature.attendance.ble

import android.Manifest
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

object BleConstants {
    // 128-bit Service UUID advertised and filtered for classroom attendance discovery
    val TEST_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    // Singleton ParcelUuid to eliminate repeated heap allocations in scanner and advertiser
    val PARCEL_UUID: ParcelUuid = ParcelUuid(TEST_UUID)

    // Legacy BLE 4.0/4.2 advertising packet payload limit (31 bytes minus headers)
    const val MAX_LEGACY_PAYLOAD_BYTES = 24

    // Modular permission rosters grouped by hardware subsystem
    val bluetoothPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    val cameraPermissions: Array<String> = arrayOf(Manifest.permission.CAMERA)
    val audioPermissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    // Comprehensive runtime permission list required by host activity and security lab
    val neededPermissions: Array<String> = bluetoothPermissions + cameraPermissions + audioPermissions
}
