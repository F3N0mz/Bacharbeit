package com.example.dispenser

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.example.dispenser.ui.theme.DispenserTheme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
// Import collectAsState to observe StateFlows
import androidx.compose.runtime.collectAsState
// Import viewModel from lifecycle-viewmodel-compose
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Import the screen composables from their new files
// Assuming the screens are directly under com.example.dispenser.screens
import com.example.dispenser.screens.DevConnScreen
import com.example.dispenser.screens.devconnection.DevConnViewModel // Update path if needed
// Import the data classes/enums used by the screen
import com.example.dispenser.screens.ConnectionStatus
import com.example.dispenser.screens.UiBluetoothDevice


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DispenserTheme {
                // The ViewModel should ideally be tied to the Activity or NavHost
                // For this simple example, instantiating it here works,
                // but consider scope in larger apps.
                // Using viewModel() ensures it survives configuration changes.
                val devConnViewModel: DevConnViewModel = viewModel()

                AppScaffoldWithDrawer(
                    // Pass the ViewModel instance to the main layout,
                    // or you could instantiate it inside AppScaffoldWithDrawer
                    // depending on preferred architecture. Passing it here is cleaner
                    // if AppScaffoldWithDrawer itself doesn't need to know about
                    // the ViewModel's details, but just needs to pass it down.
                    // Let's instantiate it inside AppScaffoldWithDrawer for now,
                    // as it's tied to the screen selection logic within that composable.
                    // This means the ViewModel is scope to the AppScaffoldWithDrawer's
                    // lifecycle within the setContent block.
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffoldWithDrawer(modifier: Modifier = Modifier) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedItem by remember { mutableStateOf("Device Connection") }

    val drawerItems = listOf("Device Connection", "Device Configuration", "Monitoring & Status")

    // Instantiate ViewModels here. This ViewModel's lifecycle will be tied
    // to the lifecycle of the AppScaffoldWithDrawer composable instance within setContent.
    // Using viewModel() helper from lifecycle-viewmodel-compose
    val devConnViewModel: DevConnViewModel = viewModel()
    // TODO: Instantiate other ViewModels for other screens here similarly


    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                drawerItems.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item) },
                        selected = item == selectedItem,
                        onClick = {
                            selectedItem = item
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        },
        modifier = modifier
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(selectedItem) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open Navigation Drawer"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
            ) {
                when (selectedItem) {
                    "Device Connection" -> {
                        // --- Collect state from ViewModel ---
                        val scanStatus by devConnViewModel.scanStatus.collectAsState()
                        val foundDevices by devConnViewModel.foundDevices.collectAsState()
                        val connectionStatus by devConnViewModel.connectionStatus.collectAsState()
                        val connectingDevice by devConnViewModel.connectingDevice.collectAsState()
                        val lastError by devConnViewModel.lastError.collectAsState()

                        // --- Pass state and ViewModel functions to the screen composable ---
                        DevConnScreen(
                            scanStatus = scanStatus,
                            foundDevices = foundDevices,
                            connectionStatus = connectionStatus,
                            connectingDevice = connectingDevice,
                            lastError = lastError,
                            onStartScanClick = { devConnViewModel.startScan() },
                            onStopScanClick = { devConnViewModel.stopScan() },
                            // Use the method reference ::connectToDevice to pass the function
                            onDeviceClick = devConnViewModel::connectToDevice,
                            onDisconnectClick = { devConnViewModel.disconnect() }
                        )
                    }
                    "Device Configuration" -> DeviceConfigurationScreen()
                    "Monitoring & Status" -> MonitoringStatusScreen()
                    else -> Text("Error: Unknown Screen Selected")
                }
            }
        }
    }
}

// Placeholder functions for other screens (keep them until they get their own files and ViewModels)
@Composable
fun DeviceConfigurationScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Device Configuration Screen (Placeholder)")
    }
}

@Composable
fun MonitoringStatusScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Monitoring & Status Screen (Placeholder)")
    }
}

// Original Greeting Composable (optional)
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Hello $name!")
    }
}

@Preview(showBackground = true)
@Composable
fun AppScaffoldWithDrawerPreview() {
    DispenserTheme {
        // For previewing, you might need to mock the ViewModel dependency
        // This preview will likely not fully function without providing a mock ViewModel
        // A better preview might be to create a dedicated AppScaffoldWithDrawerPreview
        // that provides dummy ViewModel states/functions.
        // Or, preview the individual screens with sample data as done in DevConnScreen.kt
        AppScaffoldWithDrawer()
    }
}

// Keep or move previews for other screens
@Preview(showBackground = true)
@Composable
fun DeviceConfigurationScreenPreview() {
    DispenserTheme {
        DeviceConfigurationScreen()
    }
}

@Preview(showBackground = true)
@Composable
fun MonitoringStatusScreenPreview() {
    DispenserTheme {
        MonitoringStatusScreen()
    }
}