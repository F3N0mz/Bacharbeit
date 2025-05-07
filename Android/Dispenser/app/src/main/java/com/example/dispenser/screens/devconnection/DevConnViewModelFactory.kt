package com.example.dispenser.screens.devconnection

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.dispenser.ble.BluetoothLeManager
import kotlinx.coroutines.CoroutineScope

class DevConnViewModelFactory(
    private val application: Application,
    private val bluetoothLeManager: BluetoothLeManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DevConnViewModel::class.java)) {
            return DevConnViewModel(application, bluetoothLeManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}