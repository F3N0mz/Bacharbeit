package com.example.dispenser.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.dispenser.screens.ConnectionStatus // Ensure this import is correct
import com.example.dispenser.screens.UiBluetoothDevice // Ensure this import is correct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "BluetoothLeManager"

class BluetoothLeManager(private val context: Context, private val coroutineScope: CoroutineScope) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    private val _foundDevices = MutableStateFlow<List<UiBluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<UiBluetoothDevice>> = _foundDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<UiBluetoothDevice?>(null)
    val connectedDevice: StateFlow<UiBluetoothDevice?> = _connectedDevice.asStateFlow()

    private var currentGatt: BluetoothGatt? = null

    // --- Permissions Check (Helper) ---
    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRequiredScanPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) // Still good practice for reliable discovery
        } else {
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN) && // BLUETOOTH_ADMIN implies BLUETOOTH
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    private fun hasRequiredConnectPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // On older versions, BLUETOOTH permission (implied by BLUETOOTH_ADMIN) is enough
            // for connection, once discovered.
            hasPermission(Manifest.permission.BLUETOOTH) || hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }


    // --- Scan Logic ---
    @SuppressLint("MissingPermission") // Permissions checked by hasRequiredScanPermissions
    fun startScan() {
        if (!hasRequiredScanPermissions()) {
            Log.e(TAG, "Scan initiation failed: Missing required scan permissions.")
            // Optionally, update a state flow to inform ViewModel/UI about permission issue
            _connectionStatus.value = ConnectionStatus.Error("Scan permissions not granted")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter not available or not enabled.")
            _connectionStatus.value = ConnectionStatus.Error("Bluetooth not enabled")
            return
        }
        if (_isScanning.value) {
            Log.d(TAG, "Scan already in progress.")
            return
        }

        Log.d(TAG, "Starting BLE Scan...")
        _isScanning.value = true
        _foundDevices.value = emptyList() // Clear previous results

        // For more targeted scans, add ScanFilters (e.g., by service UUID or device name)
        val scanFilters = mutableListOf<ScanFilter>()
        // Example: val filter = ScanFilter.Builder().setDeviceName("PillDispenser_XYZ").build()
        // scanFilters.add(filter)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Or other modes
            .build()

        scanJob?.cancel() // Cancel any previous scan job
        scanJob = coroutineScope.launch {
            try {
                bleScanner?.startScan(scanFilters, scanSettings, leScanCallback)
                // Stop scan after a timeout (e.g., 30 seconds)
                delay(30000)
                if (_isScanning.value) {
                    Log.d(TAG, "Scan timeout reached. Stopping scan.")
                    stopScan()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scan: ${e.message}", e)
                _isScanning.value = false
                _connectionStatus.value = ConnectionStatus.Error("Scan failed: ${e.localizedMessage}")
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions checked by hasRequiredScanPermissions
    fun stopScan() {
        if (!hasRequiredScanPermissions() && _isScanning.value) { // Check only if trying to stop an active scan without perms
            Log.w(TAG, "Attempting to stop scan but scan permissions might be missing (should not happen if startScan checked).")
        }
        if (!_isScanning.value) {
            Log.d(TAG, "Scan not in progress.")
            return
        }
        Log.d(TAG, "Stopping BLE Scan...")
        scanJob?.cancel()
        bleScanner?.stopScan(leScanCallback)
        _isScanning.value = false
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT for device.name, BLUETOOTH_SCAN for result
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                // Permission check for device name and address access
                val deviceName = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    device.name
                } else {
                    // Cannot get name without BLUETOOTH_CONNECT on API 31+
                    null // Or "Name N/A"
                }
                val uiDevice = UiBluetoothDevice(address = device.address, name = deviceName)

                // Add to list if not already present
                if (!_foundDevices.value.any { it.address == uiDevice.address }) {
                    _foundDevices.value = _foundDevices.value + uiDevice
                    Log.d(TAG, "Found device: Name: ${uiDevice.name ?: "N/A"}, Address: ${uiDevice.address}")
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult?>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan Failed with error code: $errorCode")
            _isScanning.value = false
            _connectionStatus.value = ConnectionStatus.Error("Scan failed, code: $errorCode")
        }
    }

    // --- Connection Logic ---
    @SuppressLint("MissingPermission") // Permissions checked by hasRequiredConnectPermissions
    fun connectToDevice(deviceAddress: String) {
        if (!hasRequiredConnectPermissions()) {
            Log.e(TAG, "Connection failed: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.value = ConnectionStatus.Error("Connect permission not granted")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter not available or not enabled for connect.")
            _connectionStatus.value = ConnectionStatus.Error("Bluetooth not enabled")
            return
        }

        val deviceToConnect = bluetoothAdapter.getRemoteDevice(deviceAddress)
        if (deviceToConnect == null) {
            Log.e(TAG, "Device not found with address: $deviceAddress")
            _connectionStatus.value = ConnectionStatus.Error("Device not found")
            return
        }

        // Update connecting device state
        val uiDevice = _foundDevices.value.find { it.address == deviceAddress }
            ?: UiBluetoothDevice(deviceAddress, "Connecting...") // Fallback if not in found list
        _connectedDevice.value = uiDevice
        _connectionStatus.value = ConnectionStatus.Connecting

        Log.d(TAG, "Attempting to connect to ${uiDevice.name ?: uiDevice.address}")

        connectJob?.cancel() // Cancel any previous connection attempt
        connectJob = coroutineScope.launch(Dispatchers.IO) { // GATT operations should be on a background thread
            // Close previous GATT connection if any
            currentGatt?.close()
            currentGatt = null

            // Android M (API 23) and above:
            // For direct connection (autoConnect = false), use TRANSPORT_LE
            currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                deviceToConnect.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                deviceToConnect.connectGatt(context, false, gattCallback)
            }

            if (currentGatt == null) {
                Log.e(TAG, "connectGatt failed to return a BluetoothGatt instance.")
                _connectionStatus.value = ConnectionStatus.Error("Failed to initiate connection")
                _connectedDevice.value = null
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions checked by hasRequiredConnectPermissions
    fun disconnect() {
        if (!hasRequiredConnectPermissions() && currentGatt != null) {
            Log.w(TAG, "Attempting to disconnect but connect permissions might be missing.")
        }
        Log.d(TAG, "Disconnecting from device...")
        connectJob?.cancel()
        currentGatt?.disconnect()
        // gatt.close() will be called in onConnectionStateChange when disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") // For BLUETOOTH_CONNECT
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address
            val deviceName = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                gatt?.device?.name
            } else null

            Log.d(TAG, "onConnectionStateChange: Address: $deviceAddress, Status: $status, NewState: $newState")

            coroutineScope.launch { // Ensure updates are on a scope that can update StateFlow
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "Connected to GATT server. Attempting to discover services.")
                            _connectionStatus.value = ConnectionStatus.Connected
                            _connectedDevice.value = UiBluetoothDevice(deviceAddress ?: "Unknown", deviceName ?: "Unknown Device")
                            // Discover services
                            gatt?.discoverServices()
                        } else {
                            Log.e(TAG, "Connection failed with status: $status")
                            _connectionStatus.value = ConnectionStatus.Error("Connection failed, status: $status")
                            _connectedDevice.value = null
                            gatt?.close()
                            currentGatt = null
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server.")
                        _connectionStatus.value = ConnectionStatus.Disconnected
                        _connectedDevice.value = null
                        gatt?.close() // Important: close GATT client
                        currentGatt = null
                        // If status is not GATT_SUCCESS, it might be an unexpected disconnect
                        if (status != BluetoothGatt.GATT_SUCCESS && status != 0) { // status 0 can be normal disconnect
                            // status 19 is common for "disconnected by peer"
                            if (status != 19) { // GATT_CONN_TERMINATE_PEER_USER
                                Log.w(TAG, "Disconnected with status: $status")
                                // Optionally set an error if it was unexpected
                                // _connectionStatus.value = ConnectionStatus.Error("Disconnected, status: $status")
                            }
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionStatus.value = ConnectionStatus.Connecting
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> {
                        // Usually not explicitly handled, as it transitions to DISCONNECTED
                        Log.d(TAG, "GATT Disconnecting...")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered successfully.")
                // TODO: Here you would typically iterate gatt.services
                // and prepare for characteristic reads/writes/notifications
                // For Phase 1, just knowing services are discovered is enough.
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
                // Handle error in service discovery if needed
                _connectionStatus.value = ConnectionStatus.Error("Service discovery failed, status: $status")
            }
        }

        // Other BluetoothGattCallback methods (onCharacteristicRead, onCharacteristicWrite, etc.)
        // will be implemented in later phases.
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up BluetoothLeManager")
        stopScan()
        disconnect() // This will also close gatt
        scanJob?.cancel()
        connectJob?.cancel()
    }
}