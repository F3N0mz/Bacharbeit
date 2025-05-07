package com.example.dispenser.screens.devconnection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dispenser.ble.BluetoothLeManager
import com.example.dispenser.screens.ConnectionStatus
import com.example.dispenser.screens.UiBluetoothDevice
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DevConnViewModel(
    application: Application,
    private val bluetoothLeManager: BluetoothLeManager
) : AndroidViewModel(application) {

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

    // State for any last error message (can be a combination or specific errors)
    // For now, let's use the connectionStatus error or a general one.
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

    override fun onCleared() {
        super.onCleared()
        // It's good practice to clean up resources if the ViewModel is cleared
        // and the manager is tied to this ViewModel's scope.
        // If BluetoothLeManager is an application-scoped singleton, this might not be needed here.
        // bluetoothLeManager.cleanup() // Depends on BluetoothLeManager lifecycle
    }
}