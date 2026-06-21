package com.meshcore.wearos.data.repository

import android.util.Log
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ble.BleManager
import com.meshcore.wearos.ble.MeshCoreProtocol
import com.meshcore.wearos.ble.ParsedFrame
import com.meshcore.wearos.data.db.ContactDao
import com.meshcore.wearos.data.db.MessageDao
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.DeviceInfo
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageDirection
import com.meshcore.wearos.data.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshCoreRepository @Inject constructor(
    private val bleManager: BleManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao
) {
    private val TAG = "MeshCore-Repo"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _batteryMillivolts = MutableStateFlow(0)
    val batteryMillivolts: StateFlow<Int> = _batteryMillivolts.asStateFlow()

    private val _isSyncingContacts = MutableStateFlow(false)
    val isSyncingContacts: StateFlow<Boolean> = _isSyncingContacts.asStateFlow()

    private var syncTimeoutJob: kotlinx.coroutines.Job? = null

    val connectionState = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices
    val contacts = contactDao.getAllContacts()
    val recentMessages = messageDao.getRecentMessages(50)

    init {
        scope.launch {
            bleManager.incomingFrames.collect { frame ->
                Log.d(TAG, "Processing frame: ${frame::class.simpleName}")
                processParsedFrame(frame)
            }
        }

        scope.launch {
            bleManager.connectionState.collect { state ->
                if (state is BleConnectionState.Connected) {
                    initializeSession()
                }
            }
        }
    }

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()
    fun connect(address: String) = bleManager.connectByAddress(address)
    fun disconnect() = bleManager.disconnect()

    private fun initializeSession() {
        Log.i(TAG, "Connection established, initializing MeshCore session")
        bleManager.sendFrame(MeshCoreProtocol.buildAppStartFrame())
    }

    private fun processParsedFrame(parsed: ParsedFrame) {
        when (parsed) {
            is ParsedFrame.SelfInfo -> {
                _deviceInfo.value = parsed.deviceInfo
                bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_DEVICE_QUERY))
                syncContacts()
                syncDeviceTime()
                fetchBatteryStatus()
            }
            is ParsedFrame.DeviceInfoFrame -> {
                _deviceInfo.value = parsed.info
            }
            is ParsedFrame.ContactsStart -> {
                Log.d(TAG, "Contact sync started")
                _isSyncingContacts.value = true
            }
            is ParsedFrame.ContactFrame -> {
                Log.d(TAG, "Inserting contact to DB: ${parsed.contact.name} (${parsed.contact.publicKeyHex})")
                try {
                    scope.launch {
                        contactDao.insertOrUpdateContact(parsed.contact)
                    }
                    Log.d(TAG, "Contact saved successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save contact: ${e.message}")
                }
            }
            is ParsedFrame.EndOfContacts -> {
                syncTimeoutJob?.cancel()
                _isSyncingContacts.value = false
                Log.i(TAG, "Contact sync complete")
            }
            is ParsedFrame.MessageReceived -> {
                scope.launch {
                    handleIncomingMessage(parsed.message)
                }
            }
            is ParsedFrame.MessageWaiting -> {
                bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE))
            }
            is ParsedFrame.NoMoreMessages -> {
                Log.d(TAG, "Message queue empty")
            }
            is ParsedFrame.Sent -> {
                Log.d(TAG, "Message sent, expected ACK: ${parsed.expectedAckCode}")
            }
            is ParsedFrame.SendConfirmed -> {
                scope.launch {
                    messageDao.updateMessageStatusByAck(
                        ackCode = parsed.ackCode,
                        status = MessageStatus.DELIVERED.name
                    )
                }
                Log.i(TAG, "Message delivered, RTT: ${parsed.roundTripMs}ms")
            }
            is ParsedFrame.BatteryAndStorage -> {
                _batteryMillivolts.value = parsed.batteryMillivolts
                _deviceInfo.value = _deviceInfo.value?.copy(
                    batteryMillivolts = parsed.batteryMillivolts,
                    usedStorage = parsed.usedStorage,
                    totalStorage = parsed.totalStorage
                )
            }
            is ParsedFrame.CurrentTime -> {
                Log.d(TAG, "Device time: ${parsed.epochSecs}")
            }
            is ParsedFrame.AdvertPush -> {
                Log.d(TAG, "Advert received, re-syncing contacts")
                syncContacts()
            }
            is ParsedFrame.PathUpdated -> {
                syncContacts()
            }
            is ParsedFrame.LoginSuccess -> {
                Log.i(TAG, "Login successful")
            }
            is ParsedFrame.LoginFail -> {
                Log.w(TAG, "Login failed")
            }
            is ParsedFrame.Ok -> Log.d(TAG, "Command acknowledged OK")
            is ParsedFrame.Err -> Log.w(TAG, "Command error: ${parsed.errorCode}")
            is ParsedFrame.Unknown -> Log.w(TAG, "Unknown frame code: ${parsed.code}")
            else -> {}
        }
    }

    private suspend fun handleIncomingMessage(message: Message) {
        Log.d(TAG, "Inserting message from: ${message.senderPubKeyHex}")
        messageDao.insertMessage(message)

        if (!message.isChannelMessage) {
            contactDao.updateLastMessage(
                pubKeyHex = message.senderPubKeyHex,
                text = message.text,
                timestamp = message.timestamp
            )
        }
        bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE))
    }

    fun syncContacts(since: Long = 0L) {
        Log.i(TAG, "Requesting contact sync since $since")
        _isSyncingContacts.value = true
        startSyncTimeout()
        bleManager.sendFrame(MeshCoreProtocol.buildGetContactsFrame(since))
    }

    private fun startSyncTimeout() {
        syncTimeoutJob?.cancel()
        syncTimeoutJob = scope.launch {
            delay(10000)
            if (_isSyncingContacts.value) {
                Log.w(TAG, "Sync timeout reached, resetting state")
                _isSyncingContacts.value = false
            }
        }
    }

    fun syncDeviceTime() {
        bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_GET_DEVICE_TIME))
    }

    fun fetchBatteryStatus() {
        bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_GET_BATT_AND_STORAGE))
    }

    fun syncMessages() {
        bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE))
    }

    suspend fun sendDirectMessage(contact: Contact, text: String): Long {
        val frame = MeshCoreProtocol.buildSendTextMsgFrame(
            recipientPubKey = contact.publicKey,
            message = text
        )
        bleManager.sendFrame(frame)

        val message = Message(
            senderPubKeyHex = _deviceInfo.value?.publicKeyHex ?: "",
            recipientPubKeyHex = contact.publicKeyHex,
            text = text,
            timestamp = System.currentTimeMillis() / 1000,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENDING
        )
        val id = messageDao.insertMessage(message)

        contactDao.updateLastMessage(
            pubKeyHex = contact.publicKeyHex,
            text = text,
            timestamp = message.timestamp
        )

        return id
    }

    fun sendChannelMessage(channelIndex: Int, text: String) {
        val frame = MeshCoreProtocol.buildSendChannelMsgFrame(channelIndex, text)
        bleManager.sendFrame(frame)
    }

    fun getMessagesForContact(pubKeyHex: String) = messageDao.getMessagesForContact(pubKeyHex)
    fun getMessagesForChannel(channelIndex: Int) = messageDao.getMessagesForChannel(channelIndex)
    suspend fun clearUnreadCount(pubKeyHex: String) = contactDao.clearUnreadCount(pubKeyHex)

    fun sendSelfAdvert(floodMode: Boolean = false) {
        Log.i(TAG, "Sending self advert (floodMode=$floodMode)")
        val frame = if (floodMode) {
            byteArrayOf(MeshCoreProtocol.CMD_SEND_SELF_ADVERT, 1)
        } else {
            MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SEND_SELF_ADVERT)
        }
        bleManager.sendFrame(frame)
    }
}
