package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    // Target UUIDs according to prompt specs
    val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _gymDataFlow = MutableStateFlow<GymData?>(null)
    val gymDataFlow: StateFlow<GymData?> = _gymDataFlow

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices

    // Moshi Json parsing instance
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(GymData::class.java)

    // Simulation controls for previewing without physical hardware matching the specification
    private var isSimulating = false
    private var simulationJob: Job? = null
    private val simulationScope = CoroutineScope(Dispatchers.Default)

    fun startScan() {
        if (isSimulating) {
            _connectionState.value = BleConnectionState.SCANNING
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BleManager", "Bluetooth scanner is not available.")
            return
        }

        _scannedDevices.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name
                val serviceUuids = result.scanRecord?.serviceUuids
                val hasTargetService = serviceUuids?.any { it.uuid == SERVICE_UUID } == true

                Log.d("BleManager", "Scanned device: ${device.address}, name: $name, hasTargetService: $hasTargetService")

                val matchesDevice = (name != null && (
                    name.contains("GymCompanion", ignoreCase = true) ||
                    name.contains("KinetiCoach", ignoreCase = true) ||
                    name.contains("ESP32", ignoreCase = true)
                )) || hasTargetService

                if (matchesDevice) {
                    Log.d("BleManager", "Target BLE device found: $name [${device.address}]. Connecting...")
                    stopScan()
                    connectToDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BleManager", "Scan failed with error: $errorCode")
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }

        // Pass null for filters to execute a broad, robust scan that triggers on all signals
        scanner.startScan(null, settings, scanCallback)
        isScanning = true
    }

    fun stopScan() {
        if (isScanning) {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            scanner?.stopScan(scanCallback)
            isScanning = false
            if (connectionState.value == BleConnectionState.SCANNING) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = BleConnectionState.CONNECTING

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopSimulation()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        _gymDataFlow.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = BleConnectionState.CONNECTED
                    Log.d("BleManager", "Connected to GATT server. Requesting MTU 512...")
                    val success = gatt.requestMtu(512)
                    if (!success) {
                        Log.w("BleManager", "Failed to initiate MTU request, discovering services directly...")
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    _gymDataFlow.value = null
                    Log.d("BleManager", "Disconnected from GATT server.")
                }
            } else {
                Log.e("BleManager", "GATT state change error: status $status")
                _connectionState.value = BleConnectionState.DISCONNECTED
                gatt.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BleManager", "onMtuChanged: mtu=$mtu, status=$status. Initiating service discovery...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleManager", "Services discovered successfully.")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        Log.d("BleManager", "Target characteristic found. Registering notifications...")
                        enableNotifications(gatt, characteristic)
                    } else {
                        Log.e("BleManager", "Characteristic $CHARACTERISTIC_UUID not found.")
                    }
                } else {
                    Log.e("BleManager", "Service $SERVICE_UUID not found.")
                }
            } else {
                Log.e("BleManager", "GATT services discovery failed, status $status")
            }
        }

        @Deprecated("Deprecated in Java in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val rawData = characteristic.value
                val dataString = rawData?.let { String(it) } ?: ""
                Log.d("BleManager", "Received raw BLE data (Deprecated callback): $dataString")
                parseIncomingData(dataString)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val dataString = String(value)
                Log.d("BleManager", "Received raw BLE data: $dataString")
                parseIncomingData(dataString)
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            Log.d("BleManager", "Notifications configured on descriptor successfully.")
        } else {
            Log.e("BleManager", "CCC descriptor not found.")
        }
    }

    private fun parseIncomingData(dataString: String) {
        try {
            val gymData = jsonAdapter.fromJson(dataString)
            if (gymData != null) {
                _gymDataFlow.value = gymData
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Error parsing incoming JSON: ${e.message}")
        }
    }

    // --- High-Fidelity Simulation System to support local visual feedback & testing ---
    fun startSimulation(simulationPreset: SimulationPreset = SimulationPreset.NORMAL) {
        disconnect()
        isSimulating = true
        _connectionState.value = BleConnectionState.CONNECTED

        simulationJob = simulationScope.launch {
            var timePassed = 0f
            while (isSimulating) {
                delay(150) // Emulates a high-rate real-time feed
                timePassed += 0.15f

                val emg: Int
                val accel: Float
                val gyro: Float
                val hr: Int
                val angle: Double
                val reps: Int

                when (simulationPreset) {
                    SimulationPreset.NORMAL -> {
                        // Healthy curl or squat repetition trajectory
                        // A nice cyclic movement every 3 seconds
                        val speedOfRepFactor = 3.0f
                        val wave = Math.sin((timePassed * Math.PI * 2 / speedOfRepFactor))
                        
                        // Scale accel positive to represent upward G-forces
                        accel = Math.abs(wave).toFloat() * 2.2f 
                        // Smoothly varying gyro
                        gyro = Math.abs(Math.cos((timePassed * Math.PI * 2 / speedOfRepFactor))).toFloat() * 32.0f
                        // Good muscle engagement matching acceleration peaks
                        emg = if (accel > 0.8f) (420 + (accel * 160).toInt()) else 30
                        // Moderate exercising HR
                        hr = 110 + (timePassed * 0.15f).toInt().coerceAtMost(35)
                        angle = 10.0 + (wave + 1.0) / 2.0 * 120.0
                        reps = (timePassed / speedOfRepFactor).toInt()
                    }
                    SimulationPreset.TOO_FAST -> {
                        // Fast, jerky movement (every 1.0 second)
                        val speedOfRepFactor = 1.0f
                        val wave = Math.sin((timePassed * Math.PI * 2 / speedOfRepFactor))
                        
                        accel = Math.abs(wave).toFloat() * 3.8f
                        gyro = Math.abs(Math.cos((timePassed * Math.PI * 2 / speedOfRepFactor))).toFloat() * 95.0f // Exceeds speed warning limit (>80)
                        emg = if (accel > 0.8f) 350 else 25
                        hr = 125 + (timePassed * 0.3f).toInt().coerceAtMost(40)
                        angle = 15.0 + (wave + 1.0) / 2.0 * 115.0
                        reps = (timePassed / speedOfRepFactor).toInt()
                    }
                    SimulationPreset.LOW_EMG -> {
                        // Moving but user is barely firing muscles (low EMG)
                        val speedOfRepFactor = 3.2f
                        val wave = Math.sin((timePassed * Math.PI * 2 / speedOfRepFactor))
                        
                        accel = Math.abs(wave).toFloat() * 2.0f // Moving!
                        gyro = Math.abs(Math.cos((timePassed * Math.PI * 2 / speedOfRepFactor))).toFloat() * 25.0f
                        emg = 12 // Remains microvolts low (< 50) while accel is high -> triggers Muscle Not Activated
                        hr = 95
                        angle = 5.0 + (wave + 1.0) / 2.0 * 125.0
                        reps = (timePassed / speedOfRepFactor).toInt()
                    }
                    SimulationPreset.MUSCLE_FAILURE -> {
                        // Rep peaks declining over time to trigger muscle fatigue/failure warnings
                        val speedOfRepFactor = 3.5f
                        val wave = Math.sin((timePassed * Math.PI * 2 / speedOfRepFactor))
                        
                        accel = Math.abs(wave).toFloat() * 1.8f
                        gyro = Math.abs(Math.cos((timePassed * Math.PI * 2 / speedOfRepFactor))).toFloat() * 20.0f
                        
                        // Decay EMG peaks over time to represent muscle fatigue/failure
                        val emgDecay = (1.0f - (timePassed / 35.0f)).coerceAtLeast(0.05f)
                        emg = if (accel > 0.6f) (15 + (450 * emgDecay).toInt()) else 10
                        hr = 135
                        angle = 20.0 + (wave + 1.0) / 2.0 * (120.0 * emgDecay.toDouble().coerceAtLeast(0.3))
                        reps = (timePassed / speedOfRepFactor).toInt()
                    }
                    SimulationPreset.HIGH_HR -> {
                        // Trigger critical warning state (> 170 BPM)
                        val speedOfRepFactor = 4.0f
                        val wave = Math.sin((timePassed * Math.PI * 2 / speedOfRepFactor))
                        
                        accel = Math.abs(wave).toFloat() * 1.2f
                        gyro = Math.abs(Math.cos((timePassed * Math.PI * 2 / speedOfRepFactor))).toFloat() * 15.0f
                        emg = if (accel > 0.5f) 220 else 25
                        // Rapidly soaring heart rate
                        hr = 164 + (timePassed * 0.7f).toInt().coerceAtMost(30)
                        angle = 30.0 + (wave + 1.0) / 2.0 * 80.0
                        reps = (timePassed / speedOfRepFactor).toInt()
                    }
                }
 
                 _gymDataFlow.value = GymData(
                     emg = emg,
                     accel = accel,
                     gyro = gyro,
                     hr = hr,
                     angle = angle,
                     reps = reps
                 )
            }
        }
    }

    fun stopSimulation() {
        isSimulating = false
        simulationJob?.cancel()
        simulationJob = null
    }
}

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

enum class SimulationPreset {
    NORMAL,
    TOO_FAST,
    LOW_EMG,
    MUSCLE_FAILURE,
    HIGH_HR
}

@JsonClass(generateAdapter = true)
data class GymData(
    val emg: Int = 0,
    val accel: Float = 0f,
    val gyro: Float = 0f,
    val hr: Int = 0,
    val angle: Double = 0.0,
    val reps: Int = 0
)
