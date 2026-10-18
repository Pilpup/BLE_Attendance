package com.attendance.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.attendance.ble.ble.BleAdvertiser
import com.attendance.ble.ble.BleConstants
import com.attendance.ble.ble.BleScanner
import com.attendance.ble.ui.screens.MainScreen
import com.attendance.ble.viewmodel.AttendanceViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AttendanceViewModel by viewModels()

    private val bleAdvertiser by lazy {
        BleAdvertiser(this) { _ -> }
    }

    private val bleScanner by lazy {
        BleScanner(
            context = this,
            onScanningChanged = { _ -> },
            onPacketReceived = { rssi, _, messageText ->
                viewModel.onPacketReceived(rssi, messageText)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Connect ViewModel hardware callbacks
        viewModel.onStartBroadcasting = { token, txPower ->
            bleAdvertiser.startBroadcasting(
                selectedAdvMode = viewModel.teacherAdvMode,
                selectedTxPower = txPower,
                broadcastToken = token
            )
        }

        viewModel.onStopBroadcasting = {
            bleAdvertiser.stopBroadcasting()
        }

        viewModel.onStartScanning = {
            bleScanner.startScanning()
        }

        viewModel.onStopScanning = {
            bleScanner.stopScanning()
        }

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            val enableBluetoothLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val checkAndExecute = { action: () -> Unit ->
                val hasPermissions = BleConstants.neededPermissions.all { perm ->
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                }
                if (!hasPermissions) {
                    permissionLauncher.launch(BleConstants.neededPermissions)
                } else {
                    try {
                        if (bluetoothManager.adapter?.isEnabled == false) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                        } else {
                            action()
                        }
                    } catch (_: SecurityException) {
                        permissionLauncher.launch(BleConstants.neededPermissions)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.White
            ) {
                MainScreen(
                    isTeacherMode = viewModel.isTeacherMode,
                    onToggleMode = { isTeacher ->
                        bleAdvertiser.stopBroadcasting()
                        bleScanner.stopScanning()
                        viewModel.stopTeacherSession()
                        viewModel.stopStudentScanning()
                        viewModel.isTeacherMode = isTeacher
                    },
                    // Teacher Props
                    isSessionActive = viewModel.isSessionActive,
                    sessionId = viewModel.sessionId,
                    sessionSalt = viewModel.sessionSalt,
                    remainingSeconds = viewModel.remainingSeconds,
                    broadcastPayload = viewModel.currentBroadcastToken,
                    onStartTeacherSession = {
                        checkAndExecute {
                            viewModel.startTeacherSession(durationSeconds = 60)
                        }
                    },
                    onStopTeacherSession = {
                        viewModel.stopTeacherSession()
                    },
                    // Student Props
                    studentId = viewModel.studentId,
                    studentPhase = viewModel.studentPhase,
                    detectedSessionId = viewModel.detectedSessionId,
                    detectedSessionSalt = viewModel.detectedSessionSalt,
                    deskNeighbours = viewModel.topDeskNeighbours,
                    onStartStudentScanning = {
                        checkAndExecute {
                            viewModel.startStudentScanning()
                        }
                    },
                    onStopStudentScanning = {
                        viewModel.stopStudentScanning()
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(BleConstants.neededPermissions)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleAdvertiser.stopBroadcasting()
        bleScanner.stopScanning()
    }
}
