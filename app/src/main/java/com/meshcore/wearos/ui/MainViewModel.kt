package com.meshcore.wearos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.repository.MeshCoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainViewModel provides app-wide state for the MeshCore WearOS UI.
 *
 * It exposes connection state, device info, and contacts list to all composable screens,
 * and provides methods for connection management and message sending.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MeshCoreRepository
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BleConnectionState.Disconnected)

    val deviceInfo = repository.deviceInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val contacts = repository.contacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scannedDevices = repository.scannedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSyncingContacts = repository.isSyncingContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val batteryMillivolts = repository.batteryMillivolts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connect(address: String) = repository.connect(address)
    fun disconnect() = repository.disconnect()

    // -------------------------------------------------------------------------
    // Data operations
    // -------------------------------------------------------------------------

    fun syncContacts() = repository.syncContacts()
    fun syncMessages() = repository.syncMessages()
    fun fetchBatteryStatus() = repository.fetchBatteryStatus()
    fun sendSelfAdvert(floodMode: Boolean = false) = repository.sendSelfAdvert(floodMode)

    fun sendDirectMessage(contact: Contact, text: String) {
        viewModelScope.launch {
            repository.sendDirectMessage(contact, text)
        }
    }

    fun sendChannelMessage(channelIndex: Int, text: String) {
        repository.sendChannelMessage(channelIndex, text)
    }

    fun getMessagesForContact(pubKeyHex: String) = repository.getMessagesForContact(pubKeyHex)
    fun getMessagesForChannel(channelIndex: Int) = repository.getMessagesForChannel(channelIndex)

    fun clearUnreadCount(pubKeyHex: String) {
        viewModelScope.launch {
            repository.clearUnreadCount(pubKeyHex)
        }
    }

    val isConnected: Boolean
        get() = connectionState.value is BleConnectionState.Connected
}
