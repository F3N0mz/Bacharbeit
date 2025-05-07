package com.example.dispenser.screens.devconnection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dispenser.screens.ConnectionStatus
import com.example.dispenser.screens.UiBluetoothDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// This is a simplified placeholder ViewModel.
// The real ViewModel would interact with Bluetooth APIs or a Repository.
class DevConnViewModel : ViewModel() {

    // State for scan status
    private val _scanStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val scanStatus: StateFlow<ConnectionStatus> = _scanStatus.asStateFlow()

    // State for found devices
    private val _foundDevices = MutableStateFlow<List<UiBluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<UiBluetoothDevice>> = _foundDevices.asStateFlow()

    // State for device connection status
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // State for the device currently being connected to/connected
    private val _connectingDevice = MutableStateFlow<UiBluetoothDevice?>(null)
    val connectingDevice: StateFlow<UiBluetoothDevice?> = _connectingDevice.asStateFlow()

    // State for any last error message
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()


    // --- Placeholder Functions (will contain real Bluetooth logic later) ---

    fun startScan() {
        _scanStatus.value = ConnectionStatus.Scanning
        _foundDevices.value = emptyList() // Clear previous list
        _lastError.value = null // Clear previous errors
        println("ViewModel: Starting scan...")

        // Simulate finding devices after a delay
        viewModelScope.launch {
            delay(2000) // Wait 2 seconds
            _foundDevices.value = listOf(
                UiBluetoothDevice("AA:BB:CC:DD:EE:FF", "PillDispenser_001"),
                UiBluetoothDevice("11:22:33:44:55:66", "SomeOtherDevice"),
                UiBluetoothDevice("0A:1B:2C:3D:4E:5F", null)
            )
            _scanStatus.value = ConnectionStatus.Disconnected // Scan finished (in simulation)
            println("ViewModel: Scan finished.")
        }

        // In a real app, this would call your BleManager to start scanning.
    }

    fun stopScan() {
        if (_scanStatus.value == ConnectionStatus.Scanning) {
            _scanStatus.value = ConnectionStatus.Disconnected
            println("ViewModel: Stopping scan.")
        }
        // In a real app, this would call your BleManager to stop scanning.
    }

    fun connectToDevice(device: UiBluetoothDevice) {
        if (_connectionStatus.value != ConnectionStatus.Disconnected) {
            println("ViewModel: Already connecting or connected, ignoring connect request.")
            return // Don't try to connect if already busy
        }
        stopScan() // Stop scan before connecting (common practice)

        _connectingDevice.value = device
        _connectionStatus.value = ConnectionStatus.Connecting
        _lastError.value = null // Clear previous errors
        println("ViewModel: Attempting to connect to ${device.name ?: device.address}...")

        // Simulate connection process
        viewModelScope.launch {
            delay(3000) // Simulate connection time
            // Simulate success or failure
            val success = (0..1).random() == 0 // 50% chance of success

            if (success) {
                _connectionStatus.value = ConnectionStatus.Connected
                println("ViewModel: Connected successfully.")
            } else {
                _connectionStatus.value = ConnectionStatus.Error("Simulated connection failed")
                _connectingDevice.value = null // Clear connecting device on failure
                println("ViewModel: Connection failed.")
            }
        }

        // In a real app, this would call your BleManager to establish the GATT connection.
    }

    fun disconnect() {
        if (_connectionStatus.value == ConnectionStatus.Connected || _connectionStatus.value is ConnectionStatus.Error) {
            _connectionStatus.value = ConnectionStatus.Disconnected
            _connectingDevice.value = null
            _lastError.value = null // Clear errors on disconnect
            println("ViewModel: Disconnected.")
        }
        // In a real app, this would call your BleManager to close the GATT connection.
    }

    // Implement pairing logic here when needed
    // fun pairDevice(device: UiBluetoothDevice) { ... }
}