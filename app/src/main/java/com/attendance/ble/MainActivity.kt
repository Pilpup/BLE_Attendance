package com.attendance.ble

import java.util.UUID
import android.Manifest
import android.os.Bundle
import android.os.Build
import android.os.ParcelUuid
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// Power Child class of ComponentActivity
class MainActivity : ComponentActivity(){

    // grabs main bluetooth controller
    // by lazy - only run this code and wake up hardware when I ask for it
    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    // Broadcaster
    private val bleAdvertiser by lazy {
        bluetoothManager.adapter?.bluetoothLeAdvertiser
    }

    // Scanner
    private val bleScanner by lazy {
        bluetoothManager.adapter?.bluetoothLeScanner
    }

    // parameter defintion is reverse order (name: type)
    // the ? tells compiler that this parameter is allowed to be null
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)

        setContent {
            // screen context - physical address of the current screen
            val context = androidx.compose.ui.platform.LocalContext.current

            // remember - dont' forget the variable if screen rotates
            // mutableStateOf - magic variable (when var changed, reframe UI)
            var isBroadcasting by remember {mutableStateOf(false)}
            var isScanning by remember {mutableStateOf(false)}

            // permission block
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ){permission ->
                val allGranted = permission.values.all {it == true}
                if(allGranted){
                    println("User granted all permissions")
                }
            }

            // launcher that will as OS to turn on bluetooth
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
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
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
                    try {
                        // bluetooth check
                        if(bluetoothManager.adapter?.isEnabled == false){
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)

                            // labeled return - specify what you're trying to return from
                            // this works as a staller, this will stall the app until the user turn on Bluetooth
                            return@Button
                        }

                        isBroadcasting = !isBroadcasting
                        if(isBroadcasting){
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

                        isBroadcasting = false
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
                Button(onClick = {isScanning = !isScanning}){
                    Text(text = "Start Scanning")
                }

                Spacer(modifier = Modifier.height(32.dp)) //empty space

                // lambda - {...}
                Button(onClick = {permissionLauncher.launch(neededPermissions)}){
                    Text(text = "Request Permissions")
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
        }
        override fun onStartFailure(errorCode: Int){
            println("Fail: Error Code: $errorCode")
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

        // removed try/catch so the button can catch it and reset the UI
        // now it won't say startBroadcasting = true, while it's failing in bg
        // fails when bluetooth is ON, but pemissions are revoked
        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopBroadcasting(){
        bleAdvertiser?.stopAdvertising(advertiseCallback)
    }
}
