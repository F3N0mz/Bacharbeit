package com.example.dispenser.screens

import android.bluetooth.BluetoothDevice // Using the standard Android BluetoothDevice for now
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
// In DevConnScreen.kt (or a more general model file if you create one)
data class UiBluetoothDevice(
    val address: String,
    val name: String?,
    val isPillDispenser: Boolean = false // New flag
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
    scanStatus: ConnectionStatus,
    foundDevices: List<UiBluetoothDevice>,
    connectionStatus: ConnectionStatus,
    connectingDevice: UiBluetoothDevice?,
    lastError: String?,
    onStartScanClick: () -> Unit,
    onStopScanClick: () -> Unit,
    onDeviceClick: (UiBluetoothDevice) -> Unit,
    onDisconnectClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ... (Scan Button and Status - no changes here) ...
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
                enabled = connectionStatus !is ConnectionStatus.Connecting && connectionStatus !is ConnectionStatus.Connected
            ) {
                Text(if (scanStatus == ConnectionStatus.Scanning) "Stop Scan" else "Start Scan")
            }

            when (scanStatus) {
                ConnectionStatus.Scanning -> Text("Scanning...", color = Color.Blue)
                ConnectionStatus.Disconnected -> Text("Scan stopped.")
                is ConnectionStatus.Error -> Text("Scan Error: ${scanStatus.message}", color = Color.Red)
                else -> Unit
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        // --- Found Devices List ---
        Text("Found Devices:", fontWeight = FontWeight.SemiBold)

        if (foundDevices.isEmpty() && scanStatus != ConnectionStatus.Scanning) {
            Text("Start scan to find devices.")
        } else if (foundDevices.isEmpty() && scanStatus == ConnectionStatus.Scanning) {
            Text("Searching...")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(foundDevices, key = { it.address }) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = { onDeviceClick(device) }, // ViewModel handles logic if it can connect
                        connectionStatus = connectionStatus // Pass connection status
                    )
                    Divider()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Connection Status Display ---
        // ... (no changes here) ...
        Text("Connection Status:", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))

        val statusText = when (connectionStatus) {
            ConnectionStatus.Disconnected -> "Disconnected"
            ConnectionStatus.Scanning -> "Status: Idle (Scan is separate)"
            ConnectionStatus.Connecting -> "Connecting to ${connectingDevice?.name ?: connectingDevice?.address ?: "..."}..."
            ConnectionStatus.Connected -> "Connected to ${connectingDevice?.name ?: connectingDevice?.address ?: "Device"}"
            is ConnectionStatus.Error -> "Connection Error: ${connectionStatus.message}"
        }

        val statusColor = when (connectionStatus) {
            ConnectionStatus.Connected -> Color.Green
            ConnectionStatus.Disconnected -> Color.Red
            ConnectionStatus.Scanning -> Color.Gray
            ConnectionStatus.Connecting -> Color.Blue
            is ConnectionStatus.Error -> Color.Red
        }

        Text(
            text = statusText,
            color = statusColor,
            fontWeight = FontWeight.Medium
        )

        if (lastError != null && connectionStatus !is ConnectionStatus.Error) { // Show general error if not already a conn error
            Text("Last Action Error: $lastError", color = Color.Red)
        }

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
fun DeviceListItem(
    device: UiBluetoothDevice,
    onClick: (UiBluetoothDevice) -> Unit, // Pass the whole device object
    connectionStatus: ConnectionStatus // Pass current connection status to disable clicking connected items
) {
    val isConnectable = device.isPillDispenser && // Must be our dispenser
            connectionStatus != ConnectionStatus.Connecting && // Not already connecting
            connectionStatus != ConnectionStatus.Connected    // Not already connected to *any* device

    // Determine background color
    val backgroundColor = when {
        device.isPillDispenser && isConnectable -> Color(0xFFE6FFED) // Light green for connectable dispenser
        device.isPillDispenser && !isConnectable -> Color(0xFFD3D3D3) // Grey for non-connectable dispenser (e.g. already connected)
        else -> Color.Transparent // Default for other devices
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                enabled = isConnectable, // Only clickable if it's a dispenser and we're not busy
                onClick = { if (device.isPillDispenser) onClick(device) }
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor) // Apply background color
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Unknown Device",
                    fontWeight = FontWeight.Bold,
                    color = if (device.isPillDispenser) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
                Text(
                    text = device.address,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            if (device.isPillDispenser) {
                Text(
                    text = "Dispenser",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
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