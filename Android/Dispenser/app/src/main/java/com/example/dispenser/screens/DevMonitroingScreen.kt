package com.example.dispenser.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dispenser.screens.devconnection.DevConnViewModel

@Composable
fun DeviceMonitoringScreen(devConnViewModel: DevConnViewModel) {
    val connectionStatus by devConnViewModel.connectionStatus.collectAsState()
    val isConnected = connectionStatus == ConnectionStatus.Connected

    // Observe data from ViewModel
    val deviceTime by devConnViewModel.deviceTime.collectAsState()
    val dispenseSchedule by devConnViewModel.dispenseSchedule.collectAsState()
    val lastDispenseInfo by devConnViewModel.lastDispenseInfo.collectAsState()
    val timeUntilNextDispense by devConnViewModel.timeUntilNextDispense.collectAsState()
    val dispenseLog by devConnViewModel.dispenseLog.collectAsState()

    // Optionally, trigger reads if needed, though notifications should handle most updates
    LaunchedEffect(isConnected) {
        if (isConnected) {
            // These might already be updating via notifications if enabled in BluetoothLeManager
            devConnViewModel.requestDeviceTime()
            devConnViewModel.requestDispenseSchedule()
            devConnViewModel.requestLastDispenseInfo()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Monitoring & Status", style = MaterialTheme.typography.headlineSmall)

        if (!isConnected) {
            Text("Device not connected. Please connect first.")
            return@Column
        }

        StatusItem("Device Time:", deviceTime)
        StatusItem("Dispense Schedule:", dispenseSchedule)
        StatusItem("Last Dispense Info:", lastDispenseInfo)
        StatusItem("Time Until Next Dispense:", timeUntilNextDispense)
        StatusItem("Dispense Log (Raw):", dispenseLog) // May need better formatting

        // Example: Button to manually refresh a specific piece of info
        Button(onClick = { devConnViewModel.requestLastDispenseInfo() }) {
            Text("Refresh Last Dispense")
        }
    }
}

@Composable
fun StatusItem(label: String, value: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value ?: "N/A", style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(top = 4.dp))
    }
}