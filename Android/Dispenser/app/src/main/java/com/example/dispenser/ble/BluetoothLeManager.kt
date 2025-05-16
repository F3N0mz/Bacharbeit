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
import com.example.dispenser.screens.ConnectionStatus
import com.example.dispenser.screens.UiBluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "BluetoothLeManager"

class BluetoothLeManager(private val context: Context, private val coroutineScope: CoroutineScope) {

    private val _characteristicUpdate = MutableSharedFlow<Pair<UUID, ByteArray>>()
    val characteristicUpdate: SharedFlow<Pair<UUID, ByteArray>> = _characteristicUpdate.asSharedFlow()

    private val discoveredCharacteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    private val _foundDevices = MutableStateFlow<List<UiBluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<UiBluetoothDevice>> = _foundDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionStatus =
        MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
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
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) // Recommended for robust discovery
        } else {
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN) && // BLUETOOTH_ADMIN implies BLUETOOTH
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRequiredConnectPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) || hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    // --- Scan Logic ---
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredScanPermissions()) {
            Log.e(TAG, "Scan initiation failed: Missing required scan permissions.")
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
        _foundDevices.value = emptyList()

        val scanFilters = mutableListOf<ScanFilter>()
        // Example filter by service UUID (uncomment and use your actual service UUID if needed)
        // val serviceUuid = ParcelUuid.fromString(GattAttributes.SERVICE_UUID.toString())
        // val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        // scanFilters.add(filter)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanJob?.cancel()
        scanJob = coroutineScope.launch {
            try {
                bleScanner?.startScan(scanFilters.ifEmpty { null }, scanSettings, leScanCallback) // Pass null if list is empty
                delay(30000) // Scan timeout
                if (_isScanning.value) {
                    Log.d(TAG, "Scan timeout reached. Stopping scan.")
                    stopScan()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException during startScan: ${e.message}", e)
                _isScanning.value = false
                _connectionStatus.value = ConnectionStatus.Error("Scan permission issue: ${e.localizedMessage}")
            }
            catch (e: Exception) {
                Log.e(TAG, "Error starting scan: ${e.message}", e)
                _isScanning.value = false
                _connectionStatus.value = ConnectionStatus.Error("Scan failed: ${e.localizedMessage}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) {
            // Log.d(TAG, "Scan not in progress.") // Can be a bit noisy
            return
        }
        if (!hasRequiredScanPermissions() && _isScanning.value) {
            Log.w(TAG, "Attempting to stop scan but scan permissions might be missing.")
            // Still attempt to stop, as the system might allow it or it might already be stopped.
        }
        Log.d(TAG, "Stopping BLE Scan...")
        try {
            bleScanner?.stopScan(leScanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during stopScan: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
        _isScanning.value = false
        scanJob?.cancel()
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val deviceName = try {
                    device.name // Requires BLUETOOTH_CONNECT on API 31+
                } catch (e: SecurityException) {
                    Log.w(TAG, "Missing BLUETOOTH_CONNECT for device name on API 31+. Address: ${device.address}")
                    null
                }

                // *** MODIFICATION START ***
                val isLikelyDispenser = deviceName?.startsWith("PillDispenserESP32", ignoreCase = true) == true
                // You could also check advertised service UUIDs here if available in scan record,
                // but name is often easier for initial filtering.
                // result.scanRecord?.serviceUuids?.any { it.uuid == GattAttributes.SERVICE_UUID } == true

                val uiDevice = UiBluetoothDevice(
                    address = device.address,
                    name = deviceName,
                    isPillDispenser = isLikelyDispenser // Set the flag here
                )
                // *** MODIFICATION END ***


                if (!_foundDevices.value.any { it.address == uiDevice.address }) {
                    _foundDevices.value = (_foundDevices.value + uiDevice).sortedWith(
                        compareByDescending<UiBluetoothDevice> { it.isPillDispenser } // Sort to show dispensers first
                            .thenBy { it.name ?: "" }
                    )
                    Log.d(
                        TAG,
                        "Found device: Name: ${uiDevice.name ?: "N/A"}, Address: ${uiDevice.address}, IsDispenser: ${uiDevice.isPillDispenser}"
                    )
                } else {
                    // Optional: Update existing device if its isPillDispenser status changes (e.g., due to updated advertisement)
                    _foundDevices.value = _foundDevices.value.map {
                        if (it.address == uiDevice.address && it.isPillDispenser != uiDevice.isPillDispenser) {
                            uiDevice // Update with new status
                        } else {
                            it
                        }
                    }.sortedWith( // Re-sort if an update happened
                        compareByDescending<UiBluetoothDevice> { it.isPillDispenser }
                            .thenBy { it.name ?: "" }
                    )
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
    @SuppressLint("MissingPermission")
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

        val deviceToConnect: BluetoothDevice? = try {
            bluetoothAdapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid Bluetooth address: $deviceAddress", e)
            _connectionStatus.value = ConnectionStatus.Error("Invalid device address")
            return
        }

        if (deviceToConnect == null) {
            Log.e(TAG, "Device not found with address: $deviceAddress")
            _connectionStatus.value = ConnectionStatus.Error("Device not found")
            return
        }

        val uiDevice = _foundDevices.value.find { it.address == deviceAddress }
            ?: UiBluetoothDevice(deviceAddress, try { deviceToConnect.name } catch (e: SecurityException) { null })
        _connectedDevice.value = uiDevice
        _connectionStatus.value = ConnectionStatus.Connecting

        Log.d(TAG, "Attempting to connect to ${uiDevice.name ?: uiDevice.address}")

        connectJob?.cancel()
        connectJob = coroutineScope.launch(Dispatchers.IO) {
            currentGatt?.close() // Close any existing GATT connection
            currentGatt = null

            currentGatt = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    deviceToConnect.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    deviceToConnect.connectGatt(context, false, gattCallback)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException during connectGatt: ${e.message}", e)
                _connectionStatus.value = ConnectionStatus.Error("Connect permission issue: ${e.localizedMessage}")
                _connectedDevice.value = null
                null // set currentGatt to null
            }

            if (currentGatt == null && _connectionStatus.value !is ConnectionStatus.Error) { // Check if already set to error
                Log.e(TAG, "connectGatt failed to return a BluetoothGatt instance or SecurityException occurred.")
                _connectionStatus.value = ConnectionStatus.Error("Failed to initiate connection")
                _connectedDevice.value = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (currentGatt == null) {
            // Log.d(TAG, "Not connected, no need to disconnect.")
            return
        }
        if (!hasRequiredConnectPermissions() && currentGatt != null) {
            Log.w(TAG, "Attempting to disconnect but connect permissions might be missing.")
            // Still attempt to disconnect
        }
        Log.d(TAG, "Disconnecting from device: ${currentGatt?.device?.address}")
        try {
            currentGatt?.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during disconnect: ${e.message}", e)
            // Fall through to close if possible
        }
        // gatt.close() will be called in onConnectionStateChange when disconnected
        // However, if disconnect() itself fails due to permissions, onConnectionStateChange might not trigger
        // It's safer to ensure gatt is closed if the intent is to fully release resources
        // But let's primarily rely on the callback.
        connectJob?.cancel()
    }

    // --- Characteristic Operations (public methods of BluetoothLeManager) ---
    @SuppressLint("MissingPermission")
    fun writeCharacteristic(characteristicUUID: UUID, value: ByteArray, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        if (!hasRequiredConnectPermissions()) {
            Log.e(TAG, "Write failed: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.value = ConnectionStatus.Error("Write permission denied")
            return false
        }
        val gatt = currentGatt ?: run {
            Log.e(TAG, "Write failed: GATT not connected.")
            _connectionStatus.value = ConnectionStatus.Error("Write failed: Not connected")
            return false
        }
        val characteristic = discoveredCharacteristics[characteristicUUID] ?: run {
            Log.e(TAG, "Write failed: Characteristic $characteristicUUID not found.")
            _connectionStatus.value = ConnectionStatus.Error("Write failed: Char not found")
            return false
        }

        // Check if characteristic supports the specified write type or any write type
        val supportsWrite = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val supportsWriteNoResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        if (!((writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT && supportsWrite) ||
                    (writeType == BluetoothGattCharacteristic.WRITE_TYPE_SIGNED && supportsWrite) || // WRITE_TYPE_SIGNED also needs PROPERTY_WRITE
                    (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && supportsWriteNoResponse))) {
            Log.e(TAG, "Write failed: Characteristic $characteristicUUID does not support specified write type ($writeType). Properties: ${characteristic.properties}")
            _connectionStatus.value = ConnectionStatus.Error("Write failed: Type not supported")
            return false
        }


        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(characteristic, value, writeType)
                Log.d(TAG, "Writing to $characteristicUUID (API 33+): ${value.contentToString()}. System Status: $status")
                status == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = value // Must set value before calling writeCharacteristic on older APIs
                characteristic.writeType = writeType
                val success = gatt.writeCharacteristic(characteristic)
                Log.d(TAG, "Writing to $characteristicUUID (Legacy): ${value.contentToString()}. Success: $success")
                success
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during writeCharacteristic for $characteristicUUID: ${e.message}", e)
            _connectionStatus.value = ConnectionStatus.Error("Write permission issue")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun readCharacteristic(characteristicUUID: UUID): Boolean {
        if (!hasRequiredConnectPermissions()) {
            Log.e(TAG, "Read failed: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.value = ConnectionStatus.Error("Read permission denied")
            return false
        }
        val gatt = currentGatt ?: run {
            Log.e(TAG, "Read failed: GATT not connected.")
            _connectionStatus.value = ConnectionStatus.Error("Read failed: Not connected")
            return false
        }
        val characteristic = discoveredCharacteristics[characteristicUUID] ?: run {
            Log.e(TAG, "Read failed: Characteristic $characteristicUUID not found.")
            _connectionStatus.value = ConnectionStatus.Error("Read failed: Char not found")
            return false
        }

        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            Log.e(TAG, "Read failed: Characteristic $characteristicUUID does not support read. Properties: ${characteristic.properties}")
            _connectionStatus.value = ConnectionStatus.Error("Read failed: Not readable")
            return false
        }

        return try {
            val success = gatt.readCharacteristic(characteristic)
            Log.d(TAG, "Attempting to read from $characteristicUUID. Success: $success")
            success
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during readCharacteristic for $characteristicUUID: ${e.message}", e)
            _connectionStatus.value = ConnectionStatus.Error("Read permission issue")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun setCharacteristicNotificationEnabled(characteristicUUID: UUID, enable: Boolean): Boolean {
        if (!hasRequiredConnectPermissions()) {
            Log.w(TAG, "Cannot toggle notifications: Missing connect permissions.")
            return false
        }
        val gatt = currentGatt ?: run {
            Log.w(TAG, "Cannot toggle notifications: GATT not connected.")
            return false
        }
        val characteristic = discoveredCharacteristics[characteristicUUID] ?: run {
            Log.w(TAG, "Cannot toggle notifications: Characteristic $characteristicUUID not found.")
            return false
        }

        val cccdUuid = GattAttributes.CLIENT_CHARACTERISTIC_CONFIG_UUID

        // 1. Enable/disable notification locally
        if (!gatt.setCharacteristicNotification(characteristic, enable)) {
            Log.e(TAG, "Failed to set characteristic notification locally for ${characteristic.uuid}")
            return false
        }

        // 2. Write to the CCCD descriptor on the peripheral
        val descriptor = characteristic.getDescriptor(cccdUuid) ?: run {
            Log.w(TAG, "CCCD not found for ${characteristic.uuid}. Notifications might only work locally if at all.")
            // If only enabling locally is desired, and peripheral doesn't need CCCD write, this might be "success"
            return enable // Or false, depending on strictness. Let's assume true if local set succeeded and no descriptor
        }

        val valueToSet = when {
            enable && (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            enable && (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            !enable ->
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            else -> {
                Log.w(TAG, "Characteristic ${characteristic.uuid} does not support notify/indicate, or trying to enable without support.")
                gatt.setCharacteristicNotification(characteristic, false) // Revert local setting if we can't proceed
                return false
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeDescriptor(descriptor, valueToSet)
                Log.d(TAG, "${if (enable) "Enabling" else "Disabling"} notifications (API 33+) for $characteristicUUID. System Status: $status")
                status == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = valueToSet
                val success = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "${if (enable) "Enabling" else "Disabling"} notifications (Legacy) for $characteristicUUID. Success: $success")
                success
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during writeDescriptor for $characteristicUUID: ${e.message}", e)
            gatt.setCharacteristicNotification(characteristic, false) // Revert local setting on error
            _connectionStatus.value = ConnectionStatus.Error("Notify permission issue")
            false
        }
    }


    // --- Private helper for BluetoothLeManager to enable notifications during service discovery ---
    @SuppressLint("MissingPermission")
    private fun enableNotificationsFor(characteristic: BluetoothGattCharacteristic) {
        // This method is called from onServicesDiscovered. Permissions and gatt connection are implicitly checked by call site.
        val gatt = currentGatt ?: return // Should not happen if called from onServicesDiscovered correctly
        val cccdUuid = GattAttributes.CLIENT_CHARACTERISTIC_CONFIG_UUID

        val supportsNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val supportsIndicate = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        if (!supportsNotify && !supportsIndicate) {
            // Log.d(TAG, "Characteristic ${characteristic.uuid} does not support notifications/indications.")
            return
        }

        // 1. Enable notification locally
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to set characteristic notification locally for ${characteristic.uuid} during auto-enable.")
            return
        }
        Log.d(TAG, "Local characteristic notification set for ${characteristic.uuid} during auto-enable.")

        // 2. Write to the CCCD descriptor to enable on peripheral
        characteristic.getDescriptor(cccdUuid)?.let { descriptor ->
            Log.d(TAG, "Found CCCD ${descriptor.uuid} for characteristic ${characteristic.uuid} during auto-enable.")
            val valueToSet = if (supportsNotify) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else { // Must be supportsIndicate
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            val writeSuccess: Boolean = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = gatt.writeDescriptor(descriptor, valueToSet)
                    Log.d(TAG, "writeDescriptor (API 33+) for ${characteristic.uuid} (auto-enable) status: $status")
                    status == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = valueToSet
                    val success = gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "writeDescriptor (legacy) for ${characteristic.uuid} (auto-enable) success: $success")
                    success
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException writing CCCD for ${characteristic.uuid} (auto-enable): ${e.message}", e)
                false
            }

            if (writeSuccess) {
                Log.i(TAG, "Successfully initiated enable notifications/indications for ${characteristic.uuid} (auto-enable)")
            } else {
                Log.e(TAG, "Failed to initiate write CCCD for ${characteristic.uuid} (auto-enable)")
                // Optionally, revert local notification setting:
                // gatt.setCharacteristicNotification(characteristic, false)
            }
        } ?: Log.w(TAG, "CCCD not found for ${characteristic.uuid} (auto-enable), notifications might not work fully on peripheral.")
    }


    // --- GATT Callback Object ---
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown Address"
            val deviceName = try { gatt?.device?.name } catch (e: SecurityException) { null } ?: "Unknown Device"

            Log.d(TAG, "onConnectionStateChange: Address: $deviceAddress, Status: $status, NewState: $newState")

            coroutineScope.launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "Connected to GATT server ($deviceAddress). Attempting to discover services.")
                            // connectionStatus and connectedDevice will be updated after service discovery
                            // or set to error if service discovery fails.
                            // For now, just log and proceed to discover services.
                            _connectedDevice.value = UiBluetoothDevice(deviceAddress, deviceName) // Tentative
                            _connectionStatus.value = ConnectionStatus.Connecting // Still connecting until services are good
                            try {
                                gatt?.discoverServices()
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException during discoverServices: ${e.message}", e)
                                _connectionStatus.value = ConnectionStatus.Error("Service discovery permission issue")
                                gatt?.close()
                                currentGatt = null
                            }
                        } else {
                            Log.e(TAG, "Connection failed for $deviceAddress with status: $status")
                            _connectionStatus.value = ConnectionStatus.Error("Connection failed, status: $status")
                            _connectedDevice.value = null
                            gatt?.close()
                            currentGatt = null
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server ($deviceAddress). Status: $status")
                        _connectionStatus.value = ConnectionStatus.Disconnected
                        _connectedDevice.value = null
                        discoveredCharacteristics.clear()
                        gatt?.close() // Crucial: Close GATT client
                        if (gatt == currentGatt) { // Ensure we are clearing the correct GATT instance
                            currentGatt = null
                        }
                        // Log unexpected disconnects
                        if (status != BluetoothGatt.GATT_SUCCESS && status != 0 && status != 19 /* GATT_CONN_TERMINATE_PEER_USER */) {
                            Log.w(TAG, "Disconnected from $deviceAddress unexpectedly with status: $status")
                            // Optionally update _connectionStatus to an error here if it was not a user-initiated disconnect.
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        Log.d(TAG, "GATT Connecting to $deviceAddress...")
                        _connectionStatus.value = ConnectionStatus.Connecting // Explicitly set while connecting
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> {
                        Log.d(TAG, "GATT Disconnecting from $deviceAddress...")
                        // No specific state update, will transition to DISCONNECTED
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown Address"
            Log.d(TAG, "onServicesDiscovered for $deviceAddress, Status: $status")
            coroutineScope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered successfully for $deviceAddress.")
                    discoveredCharacteristics.clear()

                    val service = gatt?.getService(GattAttributes.SERVICE_UUID)
                    if (service != null) {
                        Log.d(TAG, "Found target service: ${service.uuid}")

                        // Populate Writable Characteristics
                        listOf(
                            GattAttributes.CHAR_SET_DEVICE_TIME_UUID,
                            GattAttributes.CHAR_SET_DISPENSE_SCHEDULE_UUID,
                            GattAttributes.CHAR_TRIGGER_MANUAL_DISPENSE_UUID
                        ).forEach { uuid ->
                            service.getCharacteristic(uuid)?.also {
                                discoveredCharacteristics[uuid] = it
                                Log.d(TAG, "Discovered Writable Char: ${it.uuid} Properties: ${it.properties}")
                            } ?: Log.w(TAG, "Writable Char $uuid not found in service ${service.uuid}")
                        }

                        // Populate Readable/Notifiable Characteristics and Enable Notifications
                        listOf(
                            GattAttributes.CHAR_GET_DEVICE_TIME_UUID,
                            GattAttributes.CHAR_GET_DISPENSE_SCHEDULE_UUID,
                            GattAttributes.CHAR_GET_LAST_DISPENSE_INFO_UUID,
                            GattAttributes.CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID,
                            GattAttributes.CHAR_GET_DISPENSE_LOG_UUID
                        ).forEach { uuid ->
                            service.getCharacteristic(uuid)?.also { char ->
                                discoveredCharacteristics[uuid] = char
                                Log.d(TAG, "Discovered Readable/Notifiable Char: ${char.uuid} Properties: ${char.properties}")
                                // Call BluetoothLeManager's method to enable notifications
                                this@BluetoothLeManager.enableNotificationsFor(char)
                            } ?: Log.w(TAG, "Readable/Notifiable Char $uuid not found in service ${service.uuid}")
                        }

                        if (discoveredCharacteristics.any { it.key in listOf(
                                // Check if at least one critical characteristic is found. Adjust as needed.
                                GattAttributes.CHAR_TRIGGER_MANUAL_DISPENSE_UUID,
                                GattAttributes.CHAR_GET_LAST_DISPENSE_INFO_UUID
                            )}) {
                            Log.i(TAG, "Relevant characteristics found. Connection established to $deviceAddress.")
                            _connectionStatus.value = ConnectionStatus.Connected
                            // _connectedDevice was set tentatively in onConnectionStateChange, confirm it.
                            _connectedDevice.value = UiBluetoothDevice(
                                gatt.device.address,
                                try { gatt.device.name } catch (e: SecurityException) { null }
                            )
                        } else {
                            Log.e(TAG, "No relevant/expected characteristics found for service ${GattAttributes.SERVICE_UUID} on $deviceAddress.")
                            _connectionStatus.value = ConnectionStatus.Error("Device not supported (missing chars)")
                            this@BluetoothLeManager.disconnect() // Disconnect if not a compatible device
                        }
                    } else {
                        Log.w(TAG, "Target Service ${GattAttributes.SERVICE_UUID} not found on $deviceAddress.")
                        _connectionStatus.value = ConnectionStatus.Error("Required service not found")
                        this@BluetoothLeManager.disconnect()
                    }
                } else {
                    Log.w(TAG, "onServicesDiscovered for $deviceAddress received error: $status")
                    _connectionStatus.value = ConnectionStatus.Error("Service discovery failed: $status")
                    this@BluetoothLeManager.disconnect() // Disconnect on service discovery failure
                }
            }
        }

        // Characteristic Read Callbacks
        @Deprecated("Used for Android versions below 13 (API 33)")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(characteristic.uuid, characteristic.value ?: byteArrayOf(), status, gatt.device.address)
            }
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            // This is for API 33+
            handleCharacteristicRead(characteristic.uuid, value, status, gatt.device.address)
        }
        private fun handleCharacteristicRead(uuid: UUID, value: ByteArray, status: Int, deviceAddress: String) {
            val stringValue = value.toString(Charsets.UTF_8)
            Log.i(TAG, "onCharacteristicRead from $deviceAddress: UUID: $uuid, Status: $status, Value: \"$stringValue\"")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                coroutineScope.launch {
                    _characteristicUpdate.emit(Pair(uuid, value))
                }
            } else {
                Log.w(TAG, "Characteristic read failed for $uuid on $deviceAddress, status: $status")
            }
        }

        // Characteristic Write Callback
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown Address"
            Log.i(TAG, "onCharacteristicWrite to $deviceAddress: UUID: ${characteristic?.uuid}, Status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write successful for ${characteristic?.uuid} on $deviceAddress")
            } else {
                Log.e(TAG, "Write FAILED for ${characteristic?.uuid} on $deviceAddress, status: $status")
                coroutineScope.launch { // Post error to flow
                    _connectionStatus.value = ConnectionStatus.Error("Write fail: ${characteristic?.uuid}, status $status")
                }
            }
        }

        // Characteristic Changed (Notification/Indication) Callbacks
        @Deprecated("Used for Android versions below 13 (API 33)")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicChanged(characteristic.uuid, characteristic.value ?: byteArrayOf(), gatt.device.address)
            }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // This is for API 33+
            handleCharacteristicChanged(characteristic.uuid, value, gatt.device.address)
        }
        private fun handleCharacteristicChanged(uuid: UUID, value: ByteArray, deviceAddress: String) {
            val stringValue = value.toString(Charsets.UTF_8)
            Log.i(TAG, "onCharacteristicChanged (Notification/Indication) from $deviceAddress: UUID: $uuid, Value: \"$stringValue\"")
            coroutineScope.launch {
                _characteristicUpdate.emit(Pair(uuid, value))
            }
        }

        // Descriptor Write Callback
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown Address"
            val charUuid = descriptor?.characteristic?.uuid
            Log.i(TAG, "onDescriptorWrite for char $charUuid on $deviceAddress, Descriptor: ${descriptor?.uuid}, Status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor?.uuid == GattAttributes.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    Log.d(TAG, "CCCD descriptor write success for $charUuid on $deviceAddress.")
                }
            } else {
                Log.e(TAG, "Descriptor write FAILED for $charUuid on $deviceAddress, status: $status")
                // Optionally, if this was for enabling notifications and it failed,
                // you might want to update a state or try to disable them locally.
            }
        }
    } // End of gattCallback object

    // --- Cleanup (public method of BluetoothLeManager) ---
    fun cleanup() {
        Log.d(TAG, "Cleaning up BluetoothLeManager...")
        stopScan()
        disconnect() // This will attempt to disconnect and close GATT via callback
        // If currentGatt is not null after disconnect (e.g., disconnect call failed), try closing directly
        if (currentGatt != null) {
            Log.w(TAG, "GATT instance still present after disconnect call, attempting direct close.")
            try {
                currentGatt?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException during direct close in cleanup: ${e.message}")
            }
            currentGatt = null
        }
        scanJob?.cancel()
        connectJob?.cancel()
        Log.d(TAG, "BluetoothLeManager cleanup complete.")
    }
}