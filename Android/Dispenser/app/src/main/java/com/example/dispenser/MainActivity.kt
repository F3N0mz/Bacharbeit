package com.example.dispenser

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.example.dispenser.ui.theme.DispenserTheme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue // Import this
import androidx.compose.runtime.setValue // Import this
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DispenserTheme {
                // You might want to initialize ViewModels or dependencies here
                // and pass them down, or use dependency injection (e.g., Hilt)
                AppScaffoldWithDrawer()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffoldWithDrawer(modifier: Modifier = Modifier) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Use state destructuring for easier access (optional but common)
    var selectedItem by remember { mutableStateOf("Device Connection") } // Set initial screen

    val drawerItems = listOf("Device Connection", "Device Configuration", "Monitoring & Status")

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
                            selectedItem = item // Update the selected item state
                            scope.launch { drawerState.close() } // Close the drawer
                            // No explicit navigation needed here, just state change
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
                    title = { Text("Pillenspender") },
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
            // --- THIS IS WHERE THE CONTENT SWITCHING HAPPENS ---
            // Based on selectedItem, display the corresponding screen composable
            Column(modifier = Modifier
                .padding(innerPadding) // Apply the scaffold's padding
                .fillMaxSize() // Allow the content to fill the remaining space
            ) {
                when (selectedItem) {
                    "Device Connection" -> DeviceConnectionScreen()
                    "Device Configuration" -> DeviceConfigurationScreen()
                    "Monitoring & Status" -> MonitoringStatusScreen()
                    // Add other cases if you have more drawer items
                    else -> Text("Error: Unknown Screen") // Fallback
                }
            }
            // --- END OF CONTENT SWITCHING ---
        }
    }
}

// Placeholder Composable for Device Connection Screen
@Composable
fun DeviceConnectionScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Device Connection Screen")
        // TODO: Implement Bluetooth scanning, listing, pairing UI here
        // This screen will need permissions checks, Bluetooth adapter interaction,
        // scanning logic, displaying found devices, initiating connections.
        // This is the most complex part involving Android's Bluetooth APIs.
    }
}

// Placeholder Composable for Device Configuration Screen
@Composable
fun DeviceConfigurationScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Device Configuration Screen")
        // TODO: Implement UI for adding/editing/viewing schedules, setting device time.
        // This screen will need forms (time pickers), lists (LazyColumn for schedules),
        // buttons. It will interact with the Bluetooth communication layer to send/receive config.
        // You'll also need local data storage for schedules (e.g., Room database or SharedPreferences).
    }
}

// Placeholder Composable for Monitoring & Status Screen
@Composable
fun MonitoringStatusScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Monitoring & Status Screen")
        // TODO: Implement UI for displaying device status (battery, last dispense, etc.),
        // and a list (LazyColumn) for dispense history.
        // This screen will also interact with the Bluetooth communication layer to receive status updates and history logs.
        // You'll need local storage for the dispense history log.
    }
}

// Original Greeting Composable - might not be needed anymore in the main content area
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Hello $name!",
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AppScaffoldWithDrawerPreview() {
    DispenserTheme {
        AppScaffoldWithDrawer()
    }
}

// Preview for DeviceConnectionScreen
@Preview(showBackground = true)
@Composable
fun DeviceConnectionScreenPreview() {
    DispenserTheme {
        DeviceConnectionScreen()
    }
}

// Preview for DeviceConfigurationScreen
@Preview(showBackground = true)
@Composable
fun DeviceConfigurationScreenPreview() {
    DispenserTheme {
        DeviceConfigurationScreen()
    }
}

// Preview for MonitoringStatusScreen
@Preview(showBackground = true)
@Composable
fun MonitoringStatusScreenPreview() {
    DispenserTheme {
        MonitoringStatusScreen()
    }
}