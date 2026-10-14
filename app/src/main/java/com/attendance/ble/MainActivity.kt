package com.attendance.ble

import android.os.Bundle
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
                                    bleAdvertiser.stopBroadcasting()
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
                                    if(!viewModel.isBroadcasting){
                                        bleAdvertiser.startBroadcasting(
                                            viewModel.selectedAdvMode,
                                            viewModel.selectedTxPower,
                                            viewModel.broadcastTokken
                                        )
                                    } else{
                                        bleAdvertiser.stopBroadcasting()
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
                            currentRssi = viewModel.currentRssi,
                            avgRssi = viewModel.avgRssi,
                            minRssi = viewModel.minRssi,
                            maxRssi = viewModel.maxRssi,
                            packetCount = viewModel.packetCount,
                            avgLatencyMs = viewModel.avgLatencyMs,
                            latestMessage = viewModel.latestMessage,
                            scanLogs = viewModel.scanLogs,
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
}
