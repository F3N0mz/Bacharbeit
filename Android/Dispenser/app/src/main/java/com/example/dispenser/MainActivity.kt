package com.example.dispenser

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dispenser.ble.BluetoothLeManager
import com.example.dispenser.screens.ConnectionStatus // From DevConnScreen.kt
import com.example.dispenser.screens.DevConnScreen
import com.example.dispenser.screens.UiBluetoothDevice // From DevConnScreen.kt
import com.example.dispenser.screens.devconnection.DevConnViewModel
import com.example.dispenser.screens.devconnection.DevConnViewModelFactory
import com.example.dispenser.ui.theme.DispenserTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment


class MainActivity : ComponentActivity() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bluetoothLeManager: BluetoothLeManager // Instantiate early

    // List of permissions
    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION // Recommended even for API 31+ for robust discovery
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION // Or COARSE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothLeManager = BluetoothLeManager(applicationContext, applicationScope)

        setContent {
            DispenserTheme {
                // Permission handling
                var hasPermissions by remember {
                    mutableStateOf(checkAllPermissionsGranted())
                }

                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissionsMap ->
                    hasPermissions = permissionsMap.values.all { it }
                    if (!hasPermissions) {
                        // Handle permission denial (e.g., show a snackbar or dialog)
                        Log.w("MainActivity", "Not all BLE permissions were granted.")
                        // You might want to inform the ViewModel or show a message on screen
                    }
                }

                // Bluetooth Enabled Check
                var isBluetoothEnabled by remember { mutableStateOf(isBluetoothAdapterEnabled()) }
                val enableBluetoothLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    isBluetoothEnabled = result.resultCode == RESULT_OK && isBluetoothAdapterEnabled()
                    if (!isBluetoothEnabled) {
                        Log.w("MainActivity", "Bluetooth was not enabled by the user.")
                    }
                }

                // Request permissions when the app starts or when needed
                LaunchedEffect(Unit) {
                    if (!hasPermissions) {
                        permissionsLauncher.launch(blePermissions)
                    }
                    if (!isBluetoothEnabled) {
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBluetoothLauncher.launch(enableBtIntent)
                    }
                }

                // ViewModel Factory
                val devConnViewModelFactory = DevConnViewModelFactory(
                    application = application, // Pass application instance
                    bluetoothLeManager = bluetoothLeManager
                )

                AppScaffoldWithDrawer(
                    permissionsGranted = hasPermissions,
                    isBluetoothEnabled = isBluetoothEnabled,
                    onRequestPermissions = { permissionsLauncher.launch(blePermissions) },
                    onRequestEnableBluetooth = {
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBluetoothLauncher.launch(enableBtIntent)
                    },
                    devConnViewModelFactory = devConnViewModelFactory
                )
            }
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        return blePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBluetoothAdapterEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager?
        val bluetoothAdapter = bluetoothManager?.adapter
        return bluetoothAdapter?.isEnabled ?: false
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeManager.cleanup() // Clean up manager resources
        // Cancel applicationScope if it's only for BluetoothLeManager and not used elsewhere
        // (SupervisorJob().cancel() if you created it here)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffoldWithDrawer(
    modifier: Modifier = Modifier,
    permissionsGranted: Boolean,
    isBluetoothEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    devConnViewModelFactory: DevConnViewModelFactory
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedItem by remember { mutableStateOf("Device Connection") }

    val drawerItems = listOf("Device Connection", "Device Configuration", "Monitoring & Status")

    // Instantiate DevConnViewModel using the factory
    val devConnViewModel: DevConnViewModel = viewModel(factory = devConnViewModelFactory)

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
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                when (selectedItem) {
                    "Device Connection" -> {
                        if (!permissionsGranted) {
                            MissingPermissionsScreen(onRequestPermissions)
                        } else if (!isBluetoothEnabled) {
                            BluetoothDisabledScreen(onRequestEnableBluetooth)
                        } else {
                            val scanStatus by devConnViewModel.scanStatus.collectAsState()
                            val foundDevices by devConnViewModel.foundDevices.collectAsState()
                            val connectionStatus by devConnViewModel.connectionStatus.collectAsState()
                            val connectingDevice by devConnViewModel.connectingDevice.collectAsState()
                            // Use the combined displayError from ViewModel
                            val lastError by devConnViewModel.displayError.collectAsState()

                            DevConnScreen(
                                scanStatus = scanStatus,
                                foundDevices = foundDevices,
                                connectionStatus = connectionStatus,
                                connectingDevice = connectingDevice,
                                lastError = lastError,
                                onStartScanClick = { devConnViewModel.startScan() },
                                onStopScanClick = { devConnViewModel.stopScan() },
                                onDeviceClick = { device -> devConnViewModel.connectToDevice(device) },
                                onDisconnectClick = { devConnViewModel.disconnect() }
                            )
                        }
                    }
                    "Device Configuration" -> DeviceConfigurationScreen()
                    "Monitoring & Status" -> MonitoringStatusScreen()
                    else -> Text("Error: Unknown Screen Selected")
                }
            }
        }
    }
}

@Composable
fun MissingPermissionsScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bluetooth permissions are required to scan for and connect to devices.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Permissions")
        }
    }
}

@Composable
fun BluetoothDisabledScreen(onRequestEnableBluetooth: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bluetooth is currently disabled. Please enable it to connect to devices.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestEnableBluetooth) {
            Text("Enable Bluetooth")
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

// Preview for AppScaffoldWithDrawer might need adjustments or mocks for factory
@Preview(showBackground = true)
@Composable
fun AppScaffoldWithDrawerPreview() {
    DispenserTheme {
        // Mocking dependencies for preview. This is a simplified example.
        val mockApplication = LocalContext.current.applicationContext as Application
        val mockBleManager = BluetoothLeManager(mockApplication, CoroutineScope(Dispatchers.Unconfined))
        val mockFactory = DevConnViewModelFactory(mockApplication, mockBleManager)

        AppScaffoldWithDrawer(
            permissionsGranted = true,
            isBluetoothEnabled = true,
            onRequestPermissions = {},
            onRequestEnableBluetooth = {},
            devConnViewModelFactory = mockFactory
        )
    }
}