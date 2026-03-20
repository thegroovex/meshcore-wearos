package com.meshcore.wearos.data.repository

import android.util.Log
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ble.BleManager
import com.meshcore.wearos.ble.FrameParser
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MeshCoreRepository is the central data layer for the MeshCore WearOS client.
 *
 * It coordinates between the [BleManager] (transport layer), [FrameParser] (protocol layer),
 * and the Room database (persistence layer). It exposes reactive state flows for the UI layer
 * to observe device state, contacts, and messages.
 */
@Singleton
class MeshCoreRepository @Inject constructor(
    private val bleManager: BleManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao
) {
    private val TAG = "MeshCore-Repo"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------------
    // State flows
    // -------------------------------------------------------------------------

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _batteryMillivolts = MutableStateFlow(0)
    val batteryMillivolts: StateFlow<Int> = _batteryMillivolts.asStateFlow()

    private val _isSyncingContacts = MutableStateFlow(false)
    val isSyncingContacts: StateFlow<Boolean> = _isSyncingContacts.asStateFlow()

    val connectionState = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices

    // Contacts and messages are exposed directly from the database
    val contacts = contactDao.getAllContacts()
    val recentMessages = messageDao.getRecentMessages(50)

    init {
        // Start processing incoming BLE frames
        scope.launch {
            bleManager.incomingFrames.collect { frame ->
                processIncomingFrame(frame)
            }
        }

        // On successful connection, initialize the session
        scope.launch {
            bleManager.connectionState.collect { state ->
                if (state is BleConnectionState.Connected) {
                    initializeSession()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()
    fun connect(address: String) = bleManager.connectByAddress(address)
    fun disconnect() = bleManager.disconnect()

    // -------------------------------------------------------------------------
    // Session initialization
    // -------------------------------------------------------------------------

    private fun initializeSession() {
        Log.i(TAG, "Connection established, initializing MeshCore session")
        // Step 1: Query device info
        bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_DEVICE_QUERY))
    }

    // -------------------------------------------------------------------------
    // Incoming frame processing
    // -------------------------------------------------------------------------

    private suspend fun processIncomingFrame(rawFrame: ByteArray) {
        val parsed = FrameParser.parse(rawFrame)
        Log.d(TAG, "Received frame: ${parsed::class.simpleName}")

        when (parsed) {
            is ParsedFrame.DeviceInfoFrame -> {
                _deviceInfo.value = parsed.info
                // After device info, send app start
                bleManager.sendFrame(MeshCoreProtocol.buildAppStartFrame())
            }
            is ParsedFrame.SelfInfo -> {
                _deviceInfo.value = parsed.deviceInfo
                // After self info, sync contacts and time
                syncContacts()
                syncDeviceTime()
                fetchBatteryStatus()
            }
            is ParsedFrame.ContactsStart -> {
                _isSyncingContacts.value = true
            }
            is ParsedFrame.ContactFrame -> {
                contactDao.insertOrUpdateContact(parsed.contact)
            }
            is ParsedFrame.EndOfContacts -> {
                _isSyncingContacts.value = false
                Log.i(TAG, "Contact sync complete")
            }
            is ParsedFrame.MessageReceived -> {
                handleIncomingMessage(parsed.message)
            }
            is ParsedFrame.MessageWaiting -> {
                // Pull next message from queue
                bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE))
            }
            is ParsedFrame.NoMoreMessages -> {
                Log.d(TAG, "Message queue empty")
            }
            is ParsedFrame.Sent -> {
                Log.d(TAG, "Message sent, expected ACK: ${parsed.expectedAckCode}")
            }
            is ParsedFrame.SendConfirmed -> {
                messageDao.updateMessageStatusByAck(
                    ackCode = parsed.ackCode,
                    status = MessageStatus.DELIVERED.name
                )
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
                // New node discovered, re-sync contacts
                syncContacts()
            }
            is ParsedFrame.PathUpdated -> {
                syncContacts()
            }
            is ParsedFrame.LoginSuccess -> {
                Log.i(TAG, "Login to repeater/room successful")
            }
            is ParsedFrame.LoginFail -> {
                Log.w(TAG, "Login to repeater/room failed")
            }
            is ParsedFrame.Ok -> Log.d(TAG, "Command acknowledged OK")
            is ParsedFrame.Err -> Log.w(TAG, "Command error: ${parsed.errorCode}")
            is ParsedFrame.Unknown -> Log.w(TAG, "Unknown frame code: ${parsed.code}")
            else -> {}
        }
    }

    private suspend fun handleIncomingMessage(message: Message) {
        messageDao.insertMessage(message)

        // Update contact's last message info
        if (!message.isChannelMessage) {
            contactDao.updateLastMessage(
                pubKeyHex = message.senderPubKeyHex,
                text = message.text,
                timestamp = message.timestamp
            )
        }

        // Pull next message from queue
        bleManager.sendFrame(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE))
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun syncContacts(since: Long = 0L) {
        bleManager.sendFrame(MeshCoreProtocol.buildGetContactsFrame(since))
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

    /**
     * Sends a direct message to a contact.
     *
     * @param contact   The recipient contact
     * @param text      The message text
     * @return          The locally stored message ID
     */
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

    /**
     * Sends a message to a channel.
     *
     * @param channelIndex  The channel index (0-based)
     * @param text          The message text
     */
    fun sendChannelMessage(channelIndex: Int, text: String) {
        val frame = MeshCoreProtocol.buildSendChannelMsgFrame(channelIndex, text)
        bleManager.sendFrame(frame)
    }

    fun getMessagesForContact(pubKeyHex: String) = messageDao.getMessagesForContact(pubKeyHex)

    fun getMessagesForChannel(channelIndex: Int) = messageDao.getMessagesForChannel(channelIndex)

    suspend fun clearUnreadCount(pubKeyHex: String) = contactDao.clearUnreadCount(pubKeyHex)

    fun sendSelfAdvert(floodMode: Boolean = false) {
        val frame = if (floodMode) {
            byteArrayOf(MeshCoreProtocol.CMD_SEND_SELF_ADVERT, 1)
        } else {
            MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SEND_SELF_ADVERT)
        }
        bleManager.sendFrame(frame)
    }
}
