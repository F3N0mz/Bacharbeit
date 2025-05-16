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
                _connectionStatus.value =
                    ConnectionStatus.Error("Scan failed: ${e.localizedMessage}")
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions checked by hasRequiredScanPermissions
    fun stopScan() {
        if (!hasRequiredScanPermissions() && _isScanning.value) { // Check only if trying to stop an active scan without perms
            Log.w(
                TAG,
                "Attempting to stop scan but scan permissions might be missing (should not happen if startScan checked)."
            )
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
                val deviceName =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        device.name
                    } else {
                        // Cannot get name without BLUETOOTH_CONNECT on API 31+
                        null // Or "Name N/A"
                    }
                val uiDevice = UiBluetoothDevice(address = device.address, name = deviceName)

                // Add to list if not already present
                if (!_foundDevices.value.any { it.address == uiDevice.address }) {
                    _foundDevices.value = _foundDevices.value + uiDevice
                    Log.d(
                        TAG,
                        "Found device: Name: ${uiDevice.name ?: "N/A"}, Address: ${uiDevice.address}"
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
        connectJob =
            coroutineScope.launch(Dispatchers.IO) { // GATT operations should be on a background thread
                // Close previous GATT connection if any
                currentGatt?.close()
                currentGatt = null

                // Android M (API 23) and above:
                // For direct connection (autoConnect = false), use TRANSPORT_LE
                currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    deviceToConnect.connectGatt(
                        context,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    deviceToConnect.connectGatt(context, false, gattCallback)
                }

                if (currentGatt == null) {
                    Log.e(TAG, "connectGatt failed to return a BluetoothGatt instance.")
                    _connectionStatus.value =
                        ConnectionStatus.Error("Failed to initiate connection")
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
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            // characteristic.setValue(value) // This is deprecated and often not needed as writeCharacteristic takes value
            // characteristic.writeType = writeType // Set write type
            // gatt.writeCharacteristic(characteristic, value, writeType) // New API
            val status = gatt.writeCharacteristic(characteristic, value, writeType)
            Log.d(TAG, "Writing to $characteristicUUID (API 33+): ${value.contentToString()}. Status: $status")
            return status == BluetoothStatusCodes.SUCCESS // New way to check success for this API
        } else {
            characteristic.value = value
            characteristic.writeType = writeType
            val success = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "Writing to $characteristicUUID: ${value.contentToString()}. Success: $success")
            return success
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
            return false
        }

        val success = gatt.readCharacteristic(characteristic)
        Log.d(TAG, "Attempting to read from $characteristicUUID. Success: $success")
        return success
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") // For BLUETOOTH_CONNECT
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address
            val deviceName =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    gatt?.device?.name
                } else null

            Log.d(
                TAG,
                "onConnectionStateChange: Address: $deviceAddress, Status: $status, NewState: $newState"
            )

            coroutineScope.launch { // Ensure updates are on a scope that can update StateFlow
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "Connected to GATT server. Attempting to discover services.")
                            _connectionStatus.value = ConnectionStatus.Connected
                            _connectedDevice.value = UiBluetoothDevice(
                                deviceAddress ?: "Unknown",
                                deviceName ?: "Unknown Device"
                            )
                            // Discover services
                            gatt?.discoverServices()
                        } else {
                            Log.e(TAG, "Connection failed with status: $status")
                            _connectionStatus.value =
                                ConnectionStatus.Error("Connection failed, status: $status")
                            _connectedDevice.value = null
                            gatt?.close()
                            currentGatt = null
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server.")
                        _connectionStatus.value = ConnectionStatus.Disconnected
                        _connectedDevice.value = null
                        discoveredCharacteristics.clear() // <<< ADD THIS
                        gatt?.close()
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
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(characteristic.uuid, value, status)
            }
            // For older versions, the other onCharacteristicRead (deprecated) will be called.
        }

        // For Android versions below 13 (API 33) - This is deprecated but needed for compatibility.
        @Deprecated("Used for older Android versions")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
            }
        }

        private fun handleCharacteristicRead(uuid: UUID, value: ByteArray, status: Int) {
            val stringValue = value.toString(Charsets.UTF_8)
            Log.i(TAG, "onCharacteristicRead UUID: $uuid, Status: $status, Value: $stringValue")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                coroutineScope.launch {
                    _characteristicUpdate.emit(Pair(uuid, value))
                }
            }
        }


        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.i(TAG, "onCharacteristicWrite UUID: ${characteristic?.uuid}, Status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write successful for ${characteristic?.uuid}")
            } else {
                Log.e(TAG, "Write FAILED for ${characteristic?.uuid}, status: $status")
                coroutineScope.launch {
                    _connectionStatus.value = ConnectionStatus.Error("Write failed for ${characteristic?.uuid}, status $status")
                }
            }
        }

        // For Android 13 (API 33) and above:
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicChanged(characteristic.uuid, value)
            }
        }

        // For versions below Android 13 (API 33)
        @Deprecated("Used for older Android versions")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicChanged(characteristic.uuid, characteristic.value ?: byteArrayOf())
            }
        }

        private fun handleCharacteristicChanged(uuid: UUID, value: ByteArray) {
            val stringValue = value.toString(Charsets.UTF_8)
            Log.i(TAG, "onCharacteristicChanged (Notification/Indication) UUID: $uuid, Value: $stringValue")
            coroutineScope.launch {
                _characteristicUpdate.emit(Pair(uuid, value))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            Log.i(TAG, "onDescriptorWrite for char ${descriptor?.characteristic?.uuid}, " +
                    "Descriptor UUID: ${descriptor?.uuid}, Status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor?.uuid == GattAttributes.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    Log.d(TAG, "CCCD descriptor write success for ${descriptor.characteristic.uuid}")
                }
            } else {
                Log.e(TAG, "Descriptor write FAILED for ${descriptor?.characteristic?.uuid}, status: $status")
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotificationsFor(characteristic: BluetoothGattCharacteristic) {
            if (!hasRequiredConnectPermissions()) return

            val gatt = currentGatt ?: return
            val cccdUuid = GattAttributes.CLIENT_CHARACTERISTIC_CONFIG_UUID // 00002902-...

            // Check if characteristic supports NOTIFY or INDICATE
            val supportsNotify =
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val supportsIndicate =
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

            if (!supportsNotify && !supportsIndicate) {
                Log.d(
                    TAG,
                    "Characteristic ${characteristic.uuid} does not support notifications/indications."
                )
                return
            }

            gatt.setCharacteristicNotification(characteristic, true) // Enable locally

            // Write to the CCCD descriptor to enable on peripheral
            characteristic.getDescriptor(cccdUuid)?.let { descriptor ->
                val value = if (supportsNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
                    gatt.writeDescriptor(descriptor, value) // Use new API
                } else {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor) // Use old API
                }
                Log.d(TAG, "Enabling notifications for ${characteristic.uuid}")
            } ?: Log.w(
                TAG,
                "CCCD not found for ${characteristic.uuid}, notifications might not work."
            )
        }

        // Public method if you need to toggle notifications manually (optional for this phase)
        @SuppressLint("MissingPermission")
        fun setCharacteristicNotificationEnabled(
            characteristicUUID: UUID,
            enable: Boolean
        ): Boolean {
            // ... (Similar logic to enableNotificationsFor but more generic)
            // This can be implemented if you need fine-grained control later.
            // For now, automatic enabling in onServicesDiscovered is often sufficient.
            val characteristic = discoveredCharacteristics[characteristicUUID] ?: return false
            // ... then call gatt.setCharacteristicNotification and write to descriptor ...
            Log.d(
                TAG,
                "${if (enable) "Enabling" else "Disabling"} notifications for $characteristicUUID"
            )
            // For simplicity, we'll rely on the automatic enabling in onServicesDiscovered for now.
            if (enable) enableNotificationsFor(characteristic)
            else {
                // Add logic to disable if needed (setCharacteristicNotification to false, write DISABLE_NOTIFICATION_VALUE to descriptor)
            }
            return true
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            coroutineScope.launch { // Ensure UI-related updates are on the main thread or appropriate dispatcher
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered successfully.")
                    discoveredCharacteristics.clear() // Clear previous characteristics if re-discovering

                    gatt?.getService(GattAttributes.SERVICE_UUID)?.let { service ->
                        Log.d(TAG, "Found service: ${service.uuid}")

                        // Store all relevant characteristics from GattAttributes.kt
                        // Writable Characteristics
                        GattAttributes.CHAR_SET_DEVICE_TIME_UUID.let { uuid ->
                            service.getCharacteristic(uuid)
                                ?.also { discoveredCharacteristics[uuid] = it }
                        }
                        GattAttributes.CHAR_SET_DISPENSE_SCHEDULE_UUID.let { uuid ->
                            service.getCharacteristic(uuid)
                                ?.also { discoveredCharacteristics[uuid] = it }
                        }
                        GattAttributes.CHAR_TRIGGER_MANUAL_DISPENSE_UUID.let { uuid ->
                            service.getCharacteristic(uuid)
                                ?.also { discoveredCharacteristics[uuid] = it }
                        }

                        // Readable/Notifiable Characteristics
                        GattAttributes.CHAR_GET_DEVICE_TIME_UUID.let { uuid ->
                            service.getCharacteristic(uuid)?.also { char ->
                                discoveredCharacteristics[uuid] = char
                                enableNotificationsFor(char) // Helper to enable notifications
                            }
                        }
                        GattAttributes.CHAR_GET_DISPENSE_SCHEDULE_UUID.let { uuid ->
                            service.getCharacteristic(uuid)?.also { char ->
                                discoveredCharacteristics[uuid] = char
                                enableNotificationsFor(char)
                            }
                        }
                        GattAttributes.CHAR_GET_LAST_DISPENSE_INFO_UUID.let { uuid ->
                            service.getCharacteristic(uuid)?.also { char ->
                                discoveredCharacteristics[uuid] = char
                                enableNotificationsFor(char)
                            }
                        }
                        GattAttributes.CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID.let { uuid ->
                            service.getCharacteristic(uuid)?.also { char ->
                                discoveredCharacteristics[uuid] = char
                                enableNotificationsFor(char)
                            }
                        }
                        GattAttributes.CHAR_GET_DISPENSE_LOG_UUID.let { uuid ->
                            service.getCharacteristic(uuid)?.also { char ->
                                discoveredCharacteristics[uuid] = char
                                enableNotificationsFor(char)
                            }
                        }
                        Log.d(TAG, "Discovered characteristics: ${discoveredCharacteristics.keys}")
                        _connectionStatus.value =
                            ConnectionStatus.Connected // Ensure status is fully connected after discovery
                    } ?: run {
                        Log.w(TAG, "Service ${GattAttributes.SERVICE_UUID} not found.")
                        _connectionStatus.value =
                            ConnectionStatus.Error("Required service not found")
                        disconnect()
                    }
                } else {
                    Log.w(TAG, "onServicesDiscovered received error: $status")
                    _connectionStatus.value =
                        ConnectionStatus.Error("Service discovery failed: $status")
                }
            }
        }

        fun cleanup() {
            Log.d(TAG, "Cleaning up BluetoothLeManager")
            stopScan()
            disconnect() // This will also close gatt
            scanJob?.cancel()
            connectJob?.cancel()
        }
    }
}