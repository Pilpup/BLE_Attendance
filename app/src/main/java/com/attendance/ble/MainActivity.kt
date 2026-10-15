package com.attendance.ble

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.attendance.ble.ble.BleAdvertiser
import com.attendance.ble.ble.BleConstants
import com.attendance.ble.ble.BleScanner
import com.attendance.ble.ui.screens.BroadcasterScreen
import com.attendance.ble.ui.screens.ScannerScreen
import com.attendance.ble.ui.theme.*
import com.attendance.ble.viewmodel.AttendanceViewModel

// Power Child class of ComponentActivity
class MainActivity : ComponentActivity(){

    private val viewModel: AttendanceViewModel by viewModels()

    private val bleAdvertiser by lazy {
        BleAdvertiser(this) { isBroadcasting ->
            viewModel.isBroadcasting = isBroadcasting
        }
    }

    private val bleScanner by lazy {
        BleScanner(
            context = this,
            onScanningChanged = { isScanning ->
                viewModel.isScanning = isScanning
            },
            onPacketReceived = { rssi, latencyMs, messageText ->
                viewModel.onPacketReceived(rssi, latencyMs, messageText)
            }
        )
    }

    // parameter defintion is reverse order (name: type)
    // the ? tells compiler that this parameter is allowed to be null
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)

        // AUTO-FLIP: When Teacher elects this phone, automatically become CHILD beacon
        viewModel.onBecomeChild = {
            bleScanner.stopScanning()
            viewModel.broadcastTokken = "CHILD"
            viewModel.isBroadcasterMode = true
            bleAdvertiser.startBroadcasting(
                viewModel.selectedAdvMode,
                viewModel.selectedTxPower,
                viewModel.broadcastTokken
            )
        }

        // AUTO-RESPONSE: When student hears Teacher's ping, wait for teacher to enter scan mode, then report
        viewModel.onPingReceived = { measuredRssi ->
            viewModel.sessionStatus = "PING RCVD: PREPARING REPORT..."
            // Wait 1.4s so Teacher is in scan mode (Phase 2), then broadcast REPORT for 2.5s
            Handler(Looper.getMainLooper()).postDelayed({
                bleScanner.stopScanning()
                val reportToken = "REPORT:${viewModel.myDeviceID}:$measuredRssi"
                viewModel.sessionStatus = "SENDING REPORT TO TEACHER..."
                bleAdvertiser.startBroadcasting(
                    viewModel.selectedAdvMode,
                    viewModel.selectedTxPower,
                    reportToken
                )
                Handler(Looper.getMainLooper()).postDelayed({
                    bleAdvertiser.stopBroadcasting()
                    viewModel.sessionStatus = "REPORT SENT: AWAITING ELECTION"
                    bleScanner.startScanning()
                }, 2500)
            }, 1400)
        }

        setContent {
            // screen context - physical address of the current screen
            val context = androidx.compose.ui.platform.LocalContext.current

            // permission block
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ){permission ->
                val allGranted = permission.values.all {it == true}
                if(allGranted){
                    println("User granted all permissions")
                }
            }

            // launcher that will ask OS to turn on bluetooth
            val enableBluetoothLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ){ }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = cyberBg
            ){
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ){
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ){
                        Text(
                            text = ">> BLE_NET_RADAR [v1.0]",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = cyberGreen
                        )
                        Text(
                            text = if(viewModel.isBroadcasterMode) "[MODE: TX]" else "[MODE: RX]",
                            fontFamily = FontFamily.Monospace,
                            color = cyberTextDim
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ){
                        Button(
                            onClick = {
                                if(!viewModel.isBroadcasterMode){
                                    bleScanner.stopScanning()
                                    viewModel.isBroadcasterMode = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if(viewModel.isBroadcasterMode) cyberGreen else cyberSurface,
                                contentColor = if(viewModel.isBroadcasterMode) cyberBg else cyberGreen
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if(viewModel.isBroadcasterMode) cyberGreen else cyberBorder)
                        ){
                            Text(
                                text = "BROADCASTER",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                if(viewModel.isBroadcasterMode){
                                    stopAutoAttendanceSession()
                                    viewModel.isBroadcasterMode = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if(!viewModel.isBroadcasterMode) cyberGreen else cyberSurface,
                                contentColor = if(!viewModel.isBroadcasterMode) cyberBg else cyberGreen
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if(!viewModel.isBroadcasterMode) cyberGreen else cyberBorder)
                        ){
                            Text(
                                text = "SCANNER",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if(viewModel.isBroadcasterMode){
                        BroadcasterScreen(
                            isBroadcasting = viewModel.isBroadcasting,
                            selectedTxPower = viewModel.selectedTxPower,
                            selectedAdvMode = viewModel.selectedAdvMode,
                            broadcastTokken = viewModel.broadcastTokken,
                            sessionStatus = viewModel.sessionStatus,
                            isChildBeacon = viewModel.isChildBeacon,
                            electedChildId = viewModel.electedChildId,
                            onToggleBroadcast = {
                                val hasPermissions = BleConstants.neededPermissions.all {perm ->
                                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                                }

                                if(!hasPermissions){
                                    android.widget.Toast.makeText(
                                        context,
                                        "Click Permission Request",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@BroadcasterScreen
                                }

                                try {
                                    if(bluetoothManager.adapter?.isEnabled == false){
                                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                        enableBluetoothLauncher.launch(enableBtIntent)
                                        return@BroadcasterScreen
                                    }
                                    if(!viewModel.isBroadcasting && !viewModel.isScanning){
                                        startAutoAttendanceSession()
                                    } else{
                                        stopAutoAttendanceSession()
                                    }
                                } catch (e : SecurityException){
                                    android.widget.Toast.makeText(
                                        context,
                                        "Click Request Permissions first",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onUpdateTxPower = { newPower ->
                                viewModel.selectedTxPower = newPower
                                if(viewModel.isBroadcasting){
                                    bleAdvertiser.stopBroadcasting()
                                    bleAdvertiser.startBroadcasting(
                                        viewModel.selectedAdvMode,
                                        viewModel.selectedTxPower,
                                        viewModel.broadcastTokken
                                    )
                                }
                            },
                            onUpdateAdvMode = { newMode ->
                                viewModel.selectedAdvMode = newMode
                                if(viewModel.isBroadcasting){
                                    bleAdvertiser.stopBroadcasting()
                                    bleAdvertiser.startBroadcasting(
                                        viewModel.selectedAdvMode,
                                        viewModel.selectedTxPower,
                                        viewModel.broadcastTokken
                                    )
                                }
                            },
                            onCheckPermissions = { permissionLauncher.launch(BleConstants.neededPermissions) }
                        )
                    } else {
                        ScannerScreen(
                            isScanning = viewModel.isScanning,
                            myDeviceID = viewModel.myDeviceID,
                            teacherRssi = viewModel.teacherRssi,
                            teacherAvgRssi = viewModel.teacherAvgRssi,
                            teacherPacketCount = viewModel.teacherPacketCount,
                            childRssi = viewModel.childRssi,
                            childAvgRssi = viewModel.childAvgRssi,
                            childPacketCount = viewModel.childPacketCount,
                            deltaRssi = viewModel.deltaRssi,
                            avgLatencyMs = viewModel.avgLatencyMs,
                            scanLogs = viewModel.scanLogs,
                            sessionStatus = viewModel.sessionStatus,
                            isChildBeacon = viewModel.isChildBeacon,
                            electedChildId = viewModel.electedChildId,
                            onToggleScan = {
                                val hasPermissions = BleConstants.neededPermissions.all { perm ->
                                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                                }

                                if (!hasPermissions) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Click Request Permissions first",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@ScannerScreen
                                }

                                try {
                                    if (bluetoothManager.adapter?.isEnabled == false) {
                                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                        enableBluetoothLauncher.launch(enableBtIntent)
                                        return@ScannerScreen
                                    }

                                    if (!viewModel.isScanning) {
                                        viewModel.resetLogs()
                                        bleScanner.startScanning()
                                    } else {
                                        bleScanner.stopScanning()
                                    }
                                } catch (e: SecurityException) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Bluetooth Permission error",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    viewModel.isScanning = false
                                }
                            },
                            onResetLogs = { viewModel.resetLogs() },
                            onCheckPermissions = { permissionLauncher.launch(BleConstants.neededPermissions) }
                        )
                    }
                }
            }
        }
    }

    private val sessionHandler = Handler(Looper.getMainLooper())

    private fun startAutoAttendanceSession(){
        sessionHandler.removeCallbacksAndMessages(null)
        viewModel.collectedReports.clear()
        viewModel.electedChildId = null
        viewModel.sessionStatus = "PHASE 1: PINGING CLASS..."

        // Phase 1: Broadcast TEACHER_PING for 1.5 seconds
        bleAdvertiser.startBroadcasting(
            viewModel.selectedAdvMode,
            viewModel.selectedTxPower,
            "TEACHER_PING"
        )

        // Phase 2: After 1.5s, switch to scanning for 3.5s to collect student reports
        sessionHandler.postDelayed({
            bleAdvertiser.stopBroadcasting()
            viewModel.sessionStatus = "PHASE 2: GATHERING REPORTS..."
            bleScanner.startScanning()

            // Phase 3: After 3.5s of scanning (at T=5.0s), pick the farthest student
            sessionHandler.postDelayed({
                bleScanner.stopScanning()

                val farthest = viewModel.collectedReports.minByOrNull { it.rssi }
                if(farthest != null){
                    viewModel.electedChildId = farthest.id
                    viewModel.sessionStatus = "PHASE 3: ELECTED [${farthest.id}]"
                    // Broadcast election packet for 2.0 seconds
                    bleAdvertiser.startBroadcasting(
                        viewModel.selectedAdvMode,
                        viewModel.selectedTxPower,
                        "Child:${farthest.id}"
                    )

                    // Phase 4: After 2s of electing (at T=7.0s), switch to continuous TEACHER broadcast
                    sessionHandler.postDelayed({
                        bleAdvertiser.stopBroadcasting()
                        viewModel.broadcastTokken = "TEACHER"
                        viewModel.sessionStatus = "LIVE: TEACHER ACTIVE"
                        bleAdvertiser.startBroadcasting(
                            viewModel.selectedAdvMode,
                            viewModel.selectedTxPower,
                            viewModel.broadcastTokken
                        )
                    }, 2000)
                } else {
                    // Fallback if no student was detected: broadcast TEACHER continuously
                    viewModel.broadcastTokken = "TEACHER"
                    viewModel.sessionStatus = "FALLBACK: TEACHER ALONE"
                    bleAdvertiser.startBroadcasting(
                        viewModel.selectedAdvMode,
                        viewModel.selectedTxPower,
                        viewModel.broadcastTokken
                    )
                }
            }, 3500)
        }, 1500)
    }

    private fun stopAutoAttendanceSession(){
        sessionHandler.removeCallbacksAndMessages(null)
        bleAdvertiser.stopBroadcasting()
        bleScanner.stopScanning()
        viewModel.sessionStatus = "STANDBY"
    }
}
