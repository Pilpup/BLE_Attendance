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
import com.attendance.ble.shell.AppNavigation
import com.attendance.ble.ui.theme.P3VoidBlack
import com.attendance.ble.ui.theme.PersonaTheme
import androidx.core.view.WindowInsetsControllerCompat
import com.college.feature.attendance.AttendanceScreenCallbacks
import com.college.feature.attendance.ble.BleAdvertiser
import com.college.feature.attendance.ble.BleConstants
import com.college.feature.attendance.ble.BleScanner
import com.college.feature.attendance.viewmodel.AttendanceViewModel

/**
 * Thin host Activity. After the Step 4 migration the whole attendance engine
 * lives in :feature:attendance; this class only wires what an Activity owns:
 * runtime BLE permission gating, the hardware advertising/scanning bridge, and
 * the CSV share intent — everything else is resolved through the modules.
 */
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

        // Initialize persistent session manager (keeps user signed in across app closures)
        com.college.core.network.SessionManager.default.init(applicationContext)

        // Deep Midnight / Obsidian status & navigation bars
        val obsidianDark = android.graphics.Color.parseColor("#0B0F17")
        window.statusBarColor = obsidianDark
        window.navigationBarColor = obsidianDark
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

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

            // :app-owned hooks handed to the feature screen (permission gating,
            // BLE radio start/stop, CSV export). All other behavior is internal
            // to :feature:attendance via the ViewModel + SessionManager.
            val shellCallbacks = AttendanceScreenCallbacks(
                onStartTeacherSession = {
                    checkAndExecute {
                        viewModel.startTeacherSession(durationSeconds = 60)
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

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0B0F17)
            ) {
                AppNavigation(viewModel = viewModel, callbacks = shellCallbacks)
            }
        }
    }

    private fun shareAttendanceCsv(csvContent: String, sessionId: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, csvContent)
            putExtra(Intent.EXTRA_SUBJECT, "BLE Attendance - Session $sessionId")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Export Attendance CSV")
        startActivity(shareIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleAdvertiser.stopBroadcasting()
        bleScanner.stopScanning()
    }
}
