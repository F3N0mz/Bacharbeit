package com.example.dispenser.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dispenser.screens.devconnection.DevConnViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigurationScreen(devConnViewModel: DevConnViewModel) {
    val connectionStatus by devConnViewModel.connectionStatus.collectAsState()
    val isConnected = connectionStatus == ConnectionStatus.Connected

    var timeToSetText by remember { mutableStateOf("") }
    var scheduleToSetText by remember { mutableStateOf("") }

    // Observe current values from ViewModel (which get updated by notifications/reads)
    val currentDeviceTime by devConnViewModel.deviceTime.collectAsState()
    val currentSchedule by devConnViewModel.dispenseSchedule.collectAsState()

    // Effect to fetch initial values when screen becomes active and connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            devConnViewModel.requestDeviceTime()
            devConnViewModel.requestDispenseSchedule()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Device Configuration", style = MaterialTheme.typography.headlineSmall)

        if (!isConnected) {
            Text("Device not connected. Please connect first.")
            return@Column
        }

        // --- Set Device Time ---
        Text("Current Device Time: ${currentDeviceTime ?: "Not set/read"}")
        OutlinedTextField(
            value = timeToSetText,
            onValueChange = { timeToSetText = it },
            label = { Text("Enter Time (e.g., YYYY-MM-DD HH:MM:SS)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { devConnViewModel.setDeviceTime(timeToSetText) }) {
                Text("Set Entered Time")
            }
            Button(onClick = {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentTime = sdf.format(Date())
                devConnViewModel.setDeviceTime(currentTime)
                timeToSetText = currentTime // Update field
            }) {
                Text("Set to Phone Time")
            }
        }


        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Set Dispense Schedule ---
        Text("Current Dispense Schedule: ${currentSchedule ?: "Not set/read"}")
        OutlinedTextField(
            value = scheduleToSetText,
            onValueChange = { scheduleToSetText = it },
            label = { Text("Enter Schedule (e.g., 08:00;12:30;17:00)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { devConnViewModel.setDispenseSchedule(scheduleToSetText) }) {
            Text("Set Dispense Schedule")
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Manual Dispense ---
        Button(
            onClick = { devConnViewModel.triggerManualDispense() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Trigger Manual Dispense")
        }
    }
}