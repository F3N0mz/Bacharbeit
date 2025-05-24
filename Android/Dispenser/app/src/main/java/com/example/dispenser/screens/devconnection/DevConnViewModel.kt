package com.example.dispenser.screens.devconnection

import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dispenser.ble.BluetoothLeManager
import com.example.dispenser.ble.GattAttributes
import com.example.dispenser.screens.ConnectionStatus
import com.example.dispenser.screens.UiBluetoothDevice
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DevConnViewModel(
    application: Application,
    private val bluetoothLeManager: BluetoothLeManager
) : AndroidViewModel(application) {
    /* ---------- State flows linked to BluetoothLeManager ---------- */
    // State for scan status (now reflects manager's scanning state)
    val scanStatus: StateFlow<ConnectionStatus> = bluetoothLeManager.isScanning
        .map { isScanning ->
            if (isScanning) ConnectionStatus.Scanning else ConnectionStatus.Disconnected
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, ConnectionStatus.Disconnected)

    // State for found devices (directly from manager)
    val foundDevices: StateFlow<List<UiBluetoothDevice>> = bluetoothLeManager.foundDevices

    // State for device connection status (directly from manager)
    val connectionStatus: StateFlow<ConnectionStatus> = bluetoothLeManager.connectionStatus

    // State for the device currently being connected to/connected (directly from manager)
    val connectingDevice: StateFlow<UiBluetoothDevice?> = bluetoothLeManager.connectedDevice

    /* ---------- Error handling ---------- */
    private val _lastError = MutableStateFlow<String?>(null) // For other types of errors if needed
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Combine scan errors and connection errors into a displayable error
    val displayError: StateFlow<String?> = combine(
        bluetoothLeManager.connectionStatus, // For connection/scan errors from manager
        _lastError // For other general errors set by ViewModel
    ) { connStatus, generalError ->
        if (connStatus is ConnectionStatus.Error) {
            connStatus.message
        } else {
            generalError
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)




    /* ---------- High-level control ---------- */
    fun startScan() {
        _lastError.value = null // Clear previous general errors
        // Permission check should happen in Activity/Fragment before calling this
        // Or BluetoothLeManager handles its own permission checks internally
        bluetoothLeManager.startScan()
    }

    fun stopScan() {
        bluetoothLeManager.stopScan()
    }

    fun connectToDevice(device: UiBluetoothDevice) {
        _lastError.value = null
        // Stop scan before connecting (BluetoothLeManager might also do this)
        if (bluetoothLeManager.isScanning.value) {
            bluetoothLeManager.stopScan()
        }
        bluetoothLeManager.connectToDevice(device.address)
    }

    fun disconnect() {
        bluetoothLeManager.disconnect()
    }

    // Example of setting a general error if needed
    fun setGeneralError(message: String?) {
        _lastError.value = message
    }
    /* ---------- Characteristic-backed UI state ---------- */
    private val _deviceTime = MutableStateFlow<String?>("N/A")
    val deviceTime: StateFlow<String?> = _deviceTime.asStateFlow()

    private val _dispenseSchedule = MutableStateFlow<String?>("N/A")
    val dispenseSchedule: StateFlow<String?> = _dispenseSchedule.asStateFlow()

    private val _lastDispenseInfo = MutableStateFlow<String?>("N/A")
    val lastDispenseInfo: StateFlow<String?> = _lastDispenseInfo.asStateFlow()

    private val _timeUntilNextDispense = MutableStateFlow<String?>("N/A")
    val timeUntilNextDispense: StateFlow<String?> = _timeUntilNextDispense.asStateFlow()

    private val _dispenseLog = MutableStateFlow<String?>("N/A")
    val dispenseLog: StateFlow<String?> = _dispenseLog.asStateFlow()
    init {
        viewModelScope.launch {
            bluetoothLeManager.connectionStatus.collect { status ->
                if (status != ConnectionStatus.Connected) {
                    // Clear characteristic data if not connected
                    _deviceTime.value = "N/A (Disconnected)"
                    _dispenseSchedule.value = "N/A (Disconnected)"
                    _lastDispenseInfo.value = "N/A (Disconnected)"
                    _timeUntilNextDispense.value = "N/A (Disconnected)"
                    _dispenseLog.value = "N/A (Disconnected)"
                }
                // Optionally, if status becomes Connected, trigger initial reads
                // Or rely on notifications being enabled in onServicesDiscovered.
            }
        }

        viewModelScope.launch {
            bluetoothLeManager.characteristicUpdate.collect { (uuid, data) ->
                val stringValue = data.toString(Charsets.UTF_8)
                Log.d("DevConnViewModel", "Char update: UUID: $uuid, Value: $stringValue")
                when (uuid) {
                    GattAttributes.CHAR_GET_DEVICE_TIME_UUID -> _deviceTime.value = stringValue
                    GattAttributes.CHAR_GET_DISPENSE_SCHEDULE_UUID -> _dispenseSchedule.value = stringValue
                    GattAttributes.CHAR_GET_LAST_DISPENSE_INFO_UUID -> _lastDispenseInfo.value = stringValue
                    GattAttributes.CHAR_GET_TIME_UNTIL_NEXT_DISPENSE_UUID -> _timeUntilNextDispense.value = stringValue
                    GattAttributes.CHAR_GET_DISPENSE_LOG_UUID -> _dispenseLog.value = stringValue
                    // Add more cases if needed
                }
            }
        }
    }
    /* ---------- Characteristic write helpers ---------- */
    fun triggerManualDispense() {
        if (connectionStatus.value == ConnectionStatus.Connected) {
            // The ESP32 code for CHAR_TRIGGER_MANUAL_DISPENSE_UUID doesn't care about the value, just that a write occurred.
            // Sending a single byte "1" is common.
            bluetoothLeManager.writeCharacteristic(
                GattAttributes.CHAR_TRIGGER_MANUAL_DISPENSE_UUID,
                byteArrayOf(1), // Or "D".toByteArray(Charsets.UTF_8)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Acknowledged write
            )
        } else {
            _lastError.value = "Cannot dispense: Not connected."
        }
    }

    fun setDeviceTime(timeString: String) {
        if (connectionStatus.value == ConnectionStatus.Connected) {
            bluetoothLeManager.writeCharacteristic(
                GattAttributes.CHAR_SET_DEVICE_TIME_UUID,
                timeString.toByteArray(Charsets.UTF_8),
                // CHAR_SET_DEVICE_TIME_UUID on ESP32 has WRITE_NR property.
                // However, Android might default to WRITE_TYPE_DEFAULT.
                // Explicitly set based on ESP32's characteristic properties for reliability.
                // For now, let's assume default is fine, or if ESP32 supports both.
                // If ESP32 truly ONLY supports WRITE_NR, you must use WRITE_TYPE_NO_RESPONSE.
                // Your ESP32 code has: PROPERTY_WRITE | PROPERTY_WRITE_NR. So default should be fine.
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            _lastError.value = "Cannot set time: Not connected."
        }
    }

    fun setDispenseSchedule(scheduleString: String) {
        if (connectionStatus.value == ConnectionStatus.Connected) {
            bluetoothLeManager.writeCharacteristic(
                GattAttributes.CHAR_SET_DISPENSE_SCHEDULE_UUID,
                scheduleString.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // ESP32 has PROPERTY_WRITE
            )
        } else {
            _lastError.value = "Cannot set schedule: Not connected."
        }
    }

    // --- Functions to request reads (useful if notifications aren't setup or for initial fetch) ---
    fun requestDeviceTime() {
        if (connectionStatus.value == ConnectionStatus.Connected) {
            bluetoothLeManager.readCharacteristic(GattAttributes.CHAR_GET_DEVICE_TIME_UUID)
        }
    }
    fun requestDispenseSchedule() {
        if (connectionStatus.value == ConnectionStatus.Connected) {
            bluetoothLeManager.readCharacteristic(GattAttributes.CHAR_GET_DISPENSE_SCHEDULE_UUID)
        }
    }
    fun requestLastDispenseInfo() {
        if (connectionStatus.value == ConnectionStatus.Connected) {
            bluetoothLeManager.readCharacteristic(GattAttributes.CHAR_GET_LAST_DISPENSE_INFO_UUID)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // It's good practice to clean up resources if the ViewModel is cleared
        // and the manager is tied to this ViewModel's scope.
        // If BluetoothLeManager is an application-scoped singleton, this might not be needed here.
        // bluetoothLeManager.cleanup() // Depends on BluetoothLeManager lifecycle
    }
}