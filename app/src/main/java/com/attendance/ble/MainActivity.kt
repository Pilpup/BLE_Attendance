package com.attendance.ble

import java.util.UUID
import android.Manifest
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.os.ParcelUuid
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// Power Child class of ComponentActivity
class MainActivity : ComponentActivity(){

    // grabs main bluetooth controller
    // by lazy - only run this code and wake up hardware when I ask for it
    private val bluetoothManager get() = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // get() will now ask android for fresh connection every time
    // Broadcaster
    private val bleAdvertiser get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // Scanner
    private val bleScanner get() = bluetoothManager.adapter?.bluetoothLeScanner

    // moved outside of onCreate so advertiseCallback can handle these
    // remember - dont' forget the variable if screen rotates - <removed from this block of code>
    // mutableStateOf - magic variable (when var changed, reframe UI)
    private var isBroadcasting by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)

    // mutableStateListOf - list of mutableStateOf
    private val scanLogs = androidx.compose.runtime.mutableStateListOf<String>()

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

            // self explainatory UI
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ){
                Text(text = "isBroadcasting: $isBroadcasting")
                Text(text = "isScanning: $isScanning")

                Spacer(modifier = Modifier.height(32.dp)) //empty space

                // lambda - {...}
                Button(onClick = {
                    // check permission without touching blutetooth hardware [FIREWALL]
                    val hasPermissions = neededPermissions.all {perm ->
                        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                    }

                    if(!hasPermissions){
                        android.widget.Toast.makeText(
                            context,
                            "Click Permission Request",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }

                    try {
                        // bluetooth check
                        if(bluetoothManager.adapter?.isEnabled == false){
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)

                            // labeled return - specify what you're trying to return from
                            // this works as a staller, this will stall the app until the user turn on Bluetooth
                            return@Button
                        }
                        if(!isBroadcasting){
                            startBroadcasting()
                        } else{
                            stopBroadcasting()
                        }
                    } catch (e : SecurityException){
                        android.widget.Toast.makeText(
                            context,
                            "Click Request Permissions first",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }){
                    if(isBroadcasting){
                        Text(text = "Stop Broadcast")
                    } else {
                        Text(text = "Start Broadcast")
                    }
                }

                Spacer(modifier = Modifier.height(32.dp)) //empty space

                // lambda - {...}
                Button(onClick = {
                    val hasPermissions = neededPermissions.all { perm ->
                        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                    }

                    if (!hasPermissions) {
                        android.widget.Toast.makeText(
                            context,
                            "Click Request Permissions first",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }

                    try {
                        if (bluetoothManager.adapter?.isEnabled == false) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                            return@Button
                        }

                        if (!isScanning) {
                            startScanning()
                        } else {
                            stopScanning()
                        }
                    } catch (e: SecurityException) {
                        android.widget.Toast.makeText(
                            context,
                            "Bluetooth Permission error",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        isScanning = false
                    }
                }) {
                    if (isScanning) {
                        Text(text = "Stop Scanning")
                    } else {
                        Text(text = "Start Scanning")
                    }
                }

                Spacer(modifier = Modifier.height(32.dp)) //empty space

                // lambda - {...}
                Button(onClick = {permissionLauncher.launch(neededPermissions)}){
                    Text(text = "Request Permissions")
                }

                Spacer(modifier = Modifier.height(32.dp)) //empty space

                // basic logger
                // lazy column will recycles memory automatically
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ){
                    items(scanLogs.reversed()){ log ->
                        Text(
                            text = log,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }

    private val TEST_UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    // the listener, used to tell us if the radio wave fired successfully
    // object : one time use, nameless object on spot that inherits from AdvertiseCallback()
    private val advertiseCallback = object : AdvertiseCallback(){
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?){
            println("Success: Radio broadcasting")
            isBroadcasting = true
        }
        override fun onStartFailure(errorCode: Int){
            println("Fail: Error Code: $errorCode")
            isBroadcasting = false
        }
    }

    private fun startBroadcasting(){
        // config radio power
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // 10 times/sec
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)     // max power
            .setConnectable(false)
            .build()

        // config payload (31 bytes MAX)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(TEST_UUID))
            .addServiceData(ParcelUuid(TEST_UUID), "SECRET".toByteArray(Charsets.UTF_8))
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

    private fun stopBroadcasting(){
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        isBroadcasting = false
    }

    private fun startScanning(){
        scanLogs.clear()

        // ignore all the other peripherals, wake up only why it finds out TEST_UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(TEST_UUID))
            .build()

        // continous scanning
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanner = bleScanner
        if (scanner == null) {
            throw SecurityException()
        }

        scanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    private fun stopScanning(){
        bleScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private val scanCallback = object : ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult){
            super.onScanResult(callbackType, result)

            // signal strength (dbm)
            val rssi = result.rssi

            // calculate latency: 
            // time when antenna recieved the packets
            val antennaTime = result.timestampNanos

            // current OS clock time
            val osTime = SystemClock.elapsedRealtimeNanos()

            // 1ms = 1,000,000ns
            // .0 at the end - do floating division
            val latencyMs = (osTime - antennaTime) / 1_000_000.0

            // basic message extraction:
            // search packet for labeled TEST_UUID
            val serviceDataBytes = result.scanRecord?.serviceData?.get(ParcelUuid(TEST_UUID))

            // val messageText: String
            // if(serviceDataBytes != null){
            //     messageText = String(serviceDataBytes, Charsets.UTF_8)
            // } else {
            //     messageText = "No Data"
            // }
            // kotlin shortcut way of doing the above if-else block:
            // ?.let - take the variable as "it" and let me do something with it in {...}
            // ?: - just an else block, if something goes null of left side
            val messageText = serviceDataBytes?.let {String(it, Charsets.UTF_8)} ?: "No data"

            val logEntry = "Signal: ${rssi} dBm | Latency: ${"%.2f".format(latencyMs)} ms\nData: $messageText"

            // keep only 5 readings
            if(scanLogs.size >= 5){
                scanLogs.removeAt(0)
            }
            scanLogs.add(logEntry)
        }

        override fun onScanFailed(errorCode: Int){
            println("Scan failed with error code: $errorCode")
            isScanning = false
        }
    }
}
