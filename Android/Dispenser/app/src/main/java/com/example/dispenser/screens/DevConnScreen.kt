package com.example.dispenser.screens

import android.bluetooth.BluetoothDevice // Using the standard Android BluetoothDevice for now
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dispenser.ui.theme.DispenserTheme // Import your theme for previews

// --- Data Classes & Enums for UI State ---

/**
 * Represents a found Bluetooth device simplified for UI display.
 */
data class UiBluetoothDevice(
    val address: String,
    val name: String? // Name can be null
)

/**
 * Represents the current Bluetooth connection status for UI display.
 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Scanning : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

// --- Screen Composable ---

/**
 * Composable screen for managing Bluetooth device connection.
 *
 * This Composable is responsible for displaying the UI based on the state it receives
 * and triggering actions (like scanning or connecting) via callbacks.
 * The actual logic should be handled in a ViewModel and a Bluetooth Manager class.
 */
@Composable
fun DevConnScreen(
    // Parameters representing the state received from a ViewModel
    scanStatus: ConnectionStatus, // Indicates if currently scanning, etc.
    foundDevices: List<UiBluetoothDevice>, // List of devices found during scan
    connectionStatus: ConnectionStatus, // Current connection status to the selected device
    connectingDevice: UiBluetoothDevice?, // The device currently being connected to
    lastError: String?, // Any recent error message

    // Callbacks to trigger actions in a ViewModel
    onStartScanClick: () -> Unit,
    onStopScanClick: () -> Unit, // May be needed to manually stop scan
    onDeviceClick: (UiBluetoothDevice) -> Unit, // Called when a device in the list is clicked
    onDisconnectClick: () -> Unit // Optional: Button to manually disconnect
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp) // Apply padding here
    ) {
        Text(
            text = "Bluetooth Device Connection",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // --- Scan Button ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (scanStatus == ConnectionStatus.Scanning) {
                        onStopScanClick()
                    } else {
                        onStartScanClick()
                    }
                },
                // Disable button if already connecting or connected
                enabled = connectionStatus !is ConnectionStatus.Connecting && connectionStatus !is ConnectionStatus.Connected
            ) {
                Text(if (scanStatus == ConnectionStatus.Scanning) "Stop Scan" else "Start Scan")
            }

            // Display scan status
            when (scanStatus) {
                ConnectionStatus.Scanning -> Text("Scanning...", color = Color.Blue)
                ConnectionStatus.Disconnected -> Text("Scan stopped.") // Assuming Disconnected for scan status means stopped
                is ConnectionStatus.Error -> Text("Scan Error: ${scanStatus.message}", color = Color.Red)
                else -> Unit // Don't show anything for other connection statuses in the scan status area
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        // --- Found Devices List ---
        Text("Found Devices:", fontWeight = FontWeight.SemiBold)

        // Show message if no devices found or scanning hasn't started
        if (foundDevices.isEmpty() && scanStatus != ConnectionStatus.Scanning) {
            Text("Start scan to find devices.")
        } else if (foundDevices.isEmpty() && scanStatus == ConnectionStatus.Scanning) {
            Text("Searching...") // Or show a spinner
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Make the list fill available space
            ) {
                items(foundDevices, key = { it.address }) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = { onDeviceClick(device) }
                    )
                    Divider() // Separator between items
                }
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        // --- Connection Status Display ---
        Text("Connection Status:", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))

        val statusText = when (connectionStatus) {
            ConnectionStatus.Disconnected -> "Disconnected"
            ConnectionStatus.Scanning -> "Scanning (Scan status)" // Should not happen here
            ConnectionStatus.Connecting -> "Connecting to ${connectingDevice?.name ?: connectingDevice?.address ?: "..."}..."
            ConnectionStatus.Connected -> "Connected to ${connectingDevice?.name ?: connectingDevice?.address ?: "Device"}"
            is ConnectionStatus.Error -> "Connection Error: ${connectionStatus.message}"
        }

        val statusColor = when (connectionStatus) {
            ConnectionStatus.Connected -> Color.Green
            ConnectionStatus.Disconnected -> Color.Red
            ConnectionStatus.Scanning -> Color.Gray // Should not happen here
            ConnectionStatus.Connecting -> Color.Blue
            is ConnectionStatus.Error -> Color.Red
        }

        Text(
            text = statusText,
            color = statusColor,
            fontWeight = FontWeight.Medium
        )

        // In DevConnScreen, if using DevConnViewModel.displayError passed as lastError:
        if (lastError != null) {
            Text("Error: $lastError", color = Color.Red) // Or use a more generic "Status Message"
        }
        // And the main status text already handles connectionStatus.Error.
        // The DevConnViewModel.displayError now handles this logic, so the screen might just display `lastError` if it's not null.


        // Optional: Disconnect Button (only shown when connected)
        if (connectionStatus is ConnectionStatus.Connected) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDisconnectClick) {
                Text("Disconnect")
            }
        }
    }
}

// --- Helper Composable for Device List Item ---

/**
 * Displays a single item in the list of found Bluetooth devices.
 */
@Composable
fun DeviceListItem(device: UiBluetoothDevice, onClick: () -> Unit) {
    Card( // Using Card for a slightly nicer look
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick) // Make the whole item clickable
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
        ) {
            Text(
                text = device.name ?: "Unknown Device", // Show "Unknown Device" if name is null
                fontWeight = FontWeight.Bold
            )
            Text(
                text = device.address,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

// --- Previews ---

@Preview(showBackground = true)
@Composable
fun DevConnScreenPreview_Disconnected() {
    DispenserTheme {
        DevConnScreen(
            scanStatus = ConnectionStatus.Disconnected,
            foundDevices = listOf(),
            connectionStatus = ConnectionStatus.Disconnected,
            connectingDevice = null,
            lastError = null,
            onStartScanClick = {},
            onStopScanClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DevConnScreenPreview_ScanningWithDevices() {
    DispenserTheme {
        DevConnScreen(
            scanStatus = ConnectionStatus.Scanning,
            foundDevices = listOf(
                UiBluetoothDevice("AA:BB:CC:DD:EE:FF", "PillDispenser_001"),
                UiBluetoothDevice("11:22:33:44:55:66", "SomeOtherDevice"),
                UiBluetoothDevice("0A:1B:2C:3D:4E:5F", null)
            ),
            connectionStatus = ConnectionStatus.Disconnected,
            connectingDevice = null,
            lastError = null,
            onStartScanClick = {},
            onStopScanClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DevConnScreenPreview_Connecting() {
    val device = UiBluetoothDevice("AA:BB:CC:DD:EE:FF", "PillDispenser_001")
    DispenserTheme {
        DevConnScreen(
            scanStatus = ConnectionStatus.Disconnected, // Scan usually stops when connecting
            foundDevices = listOf(device), // Show the device that was clicked
            connectionStatus = ConnectionStatus.Connecting,
            connectingDevice = device,
            lastError = null,
            onStartScanClick = {},
            onStopScanClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}


@Preview(showBackground = true)
@Composable
fun DevConnScreenPreview_Connected() {
    val device = UiBluetoothDevice("AA:BB:CC:DD:EE:FF", "PillDispenser_001")
    DispenserTheme {
        DevConnScreen(
            scanStatus = ConnectionStatus.Disconnected,
            foundDevices = listOf(device),
            connectionStatus = ConnectionStatus.Connected,
            connectingDevice = device,
            lastError = null,
            onStartScanClick = {},
            onStopScanClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}


@Preview(showBackground = true)
@Composable
fun DevConnScreenPreview_Error() {
    DispenserTheme {
        DevConnScreen(
            scanStatus = ConnectionStatus.Disconnected,
            foundDevices = listOf(),
            connectionStatus = ConnectionStatus.Error("Failed to connect: GATT error 133"),
            connectingDevice = null,
            lastError = "Bluetooth adapter off", // Example of a last scan/action error
            onStartScanClick = {},
            onStopScanClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}