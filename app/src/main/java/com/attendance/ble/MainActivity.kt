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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.widget.Toast
import com.attendance.ble.shell.AppNavigation
import com.college.core.ui.theme.CollegeAppTheme
import com.college.core.ui.theme.AppTheme
import com.college.feature.attendance.AttendanceScreenCallbacks
import com.college.feature.attendance.ble.BleAdvertiser
import com.college.feature.attendance.ble.BleConstants
import com.college.feature.attendance.ble.BleScanner
import com.college.feature.attendance.viewmodel.AttendanceViewModel

// Host activity managing window configuration, BLE radio bridging, and runtime permissions
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

        // Initialize persistent session manager and server configuration
        com.college.core.network.SessionManager.default.init(applicationContext)
        com.college.core.network.ServerConfigManager.default.init(applicationContext)



        // Connect the feature ViewModel's radio callbacks to the hardware bridge
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

            val bluetoothManager = remember(context) {
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            }

            // R8 (review §1.7.5): the launcher callbacks used to be literal
            // `{}` — granted/denied permission results and Bluetooth-enable
            // results were discarded, so denied BLE/camera/mic access failed
            // with zero feedback. The pending action is retained and replayed
            // on success; a denial shows an explicit rationale instead of
            // silently doing nothing.
            val permissionRationale =
                "Bluetooth, location and camera permissions are required for attendance. Enable them in Settings."
            val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }

            val enableBluetoothLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val action = pendingAction.value
                pendingAction.value = null
                // Permissions were granted before this launch, so replaying the
                // action after a successful enable is safe
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    action?.invoke()
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val action = pendingAction.value
                pendingAction.value = null
                if (grants.values.all { it }) {
                    // Replay through the SAME adapter gate as the normal path —
                    // invoking directly would let an adapter-off device attempt
                    // a scan/broadcast that can never succeed. A SecurityException
                    // cannot legitimately occur right after a fresh grant; we
                    // toast instead of re-launching the same request (loop guard).
                    try {
                        if (bluetoothManager.adapter?.isEnabled == false) {
                            pendingAction.value = action
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                        } else {
                            action?.invoke()
                        }
                    } catch (_: SecurityException) {
                        Toast.makeText(context, permissionRationale, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, permissionRationale, Toast.LENGTH_LONG).show()
                }
            }

            // Checks runtime permissions and bluetooth state before executing action
            val checkAndExecute: (() -> Unit) -> Unit = remember(context, bluetoothManager, permissionLauncher, enableBluetoothLauncher) {
                { action ->
                    val hasPermissions = BleConstants.neededPermissions.all { perm ->
                        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                    }
                    if (!hasPermissions) {
                        pendingAction.value = action
                        permissionLauncher.launch(BleConstants.neededPermissions)
                    } else {
                        try {
                            if (bluetoothManager.adapter?.isEnabled == false) {
                                pendingAction.value = action
                                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                enableBluetoothLauncher.launch(enableBtIntent)
                            } else {
                                action()
                            }
                        } catch (_: SecurityException) {
                            pendingAction.value = action
                            permissionLauncher.launch(BleConstants.neededPermissions)
                        }
                    }
                }
            }

            // Attendance feature callbacks for BLE radio, session lifecycle, and CSV export
            val shellCallbacks = remember(viewModel, checkAndExecute) {
                AttendanceScreenCallbacks(
                    onStartTeacherSession = {
                        checkAndExecute {
                            viewModel.startTeacherSession(durationSeconds = 30)
                        }
                    },
                    onStopTeacherSession = {
                        viewModel.finalizeSessionAndAudit()
                    },
                    onShareAttendance = {
                        shareAttendanceCsv(viewModel.generateCsvReport(), viewModel.sessionId)
                    },
                    onResetSession = {
                        viewModel.resetSession()
                    },
                    onStartStudentScanning = {
                        checkAndExecute {
                            viewModel.startStudentScanning()
                        }
                    },
                    onStopStudentScanning = {
                        viewModel.stopStudentScanning()
                    },
                    onUpdateRollNumber = { roll ->
                        viewModel.updateRollNumber(roll)
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(BleConstants.neededPermissions)
                    }
                )
            }

            CollegeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppTheme.colors.background
                ) {
                    AppNavigation(viewModel = viewModel, callbacks = shellCallbacks)
                }
            }
        }
    }

    // Exports attendance records and session details as a .csv file
    private fun shareAttendanceCsv(csvContent: String, sessionId: String) {
        try {
            val fileName = "attendance_${sessionId.lowercase().ifBlank { "session" }}.csv"
            val file = java.io.File(cacheDir, fileName)
            file.writeText(csvContent)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, csvContent)
                putExtra(Intent.EXTRA_SUBJECT, "Attendance - Session $sessionId")
                type = "text/csv"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val shareIntent = Intent.createChooser(sendIntent, "Export Attendance CSV")
            startActivity(shareIntent)
        } catch (_: Exception) {
            val fallbackIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, csvContent)
                putExtra(Intent.EXTRA_SUBJECT, "Attendance - Session $sessionId")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(fallbackIntent, "Export Attendance"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleAdvertiser.stopBroadcasting()
        bleScanner.stopScanning()
    }
}
