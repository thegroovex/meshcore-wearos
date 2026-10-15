package com.meshcore.wearos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.repository.MeshCoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MeshCoreRepository
) : ViewModel() {

    // repository flows are already hot StateFlows - expose them directly so the first
    // composition sees the real state (stateIn with a default caused a flash of "Disconnected")
    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
    val deviceInfo = repository.deviceInfo
    val isSyncingContacts = repository.isSyncingContacts
    val sessionReady = repository.sessionReady
    val lastError = repository.lastError
    val scannedDevices = repository.scannedDevices

    val contacts: StateFlow<List<Contact>> = repository.contacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val channels = repository.channels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val unreadTotal = repository.unreadTotal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    val hasSavedDevice: Boolean get() = repository.savedDeviceAddress != null
    val savedDeviceName: String? get() = repository.savedDeviceName

    private val _pairingPin = kotlinx.coroutines.flow.MutableStateFlow(repository.pairingPin)
    val pairingPin: StateFlow<String> = _pairingPin

    fun setPairingPin(pin: String) {
        val digits = pin.filter { it.isDigit() }
        if (digits.length != 6) {
            _toasts.tryEmit("PIN must have 6 digits")
            return
        }
        repository.pairingPin = digits
        _pairingPin.value = digits
    }

    fun hasPermissions() = repository.hasPermissions()
    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connect(address: String) = repository.connect(address)
    fun reconnect() = repository.connectToSavedDevice()
    fun disconnect() = repository.disconnect()
    fun forgetDevice() = repository.forgetDevice()

    fun syncAll() {
        repository.syncContacts()
        repository.syncMessages()
        repository.fetchBatteryStatus()
    }
    fun syncContacts() = repository.syncContacts()
    fun fetchBatteryStatus() = repository.fetchBatteryStatus()

    fun sendSelfAdvert(flood: Boolean) {
        viewModelScope.launch {
            val ok = repository.sendSelfAdvert(flood)
            _toasts.tryEmit(if (ok) (if (flood) "Flood advert sent" else "Advert sent") else "Advert failed")
        }
    }

    fun observeContact(pubKeyHex: String) = repository.observeContact(pubKeyHex)
    fun observeChannel(index: Int) = repository.observeChannel(index)
    fun getMessagesForContact(pubKeyHex: String) = repository.getMessagesForContact(pubKeyHex)
    fun getMessagesForChannel(channelIndex: Int) = repository.getMessagesForChannel(channelIndex)

    fun sendDirectMessage(contact: Contact, text: String) = repository.sendDirectMessage(contact, text)
    fun retryMessage(message: Message, contact: Contact) = repository.retryMessage(message, contact)
    fun sendChannelMessage(channelIndex: Int, text: String) = repository.sendChannelMessage(channelIndex, text)
    fun toggleFavorite(pubKeyHex: String) = repository.toggleFavorite(pubKeyHex)
    fun resetPath(contact: Contact) {
        repository.resetPath(contact)
        _toasts.tryEmit("Route reset")
    }

    fun openConversation(key: String?) {
        repository.activeConversation = key
    }
    fun clearUnreadCount(pubKeyHex: String) = repository.clearUnreadCount(pubKeyHex)
    fun clearChannelUnread(index: Int) = repository.clearChannelUnread(index)
}
