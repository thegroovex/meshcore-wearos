package com.meshcore.wearos.data.repository

import android.util.Log
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.ble.BleManager
import com.meshcore.wearos.ble.ChannelMessageRecord
import com.meshcore.wearos.ble.ContactMessageRecord
import com.meshcore.wearos.ble.ContactRecord
import com.meshcore.wearos.ble.FrameParser
import com.meshcore.wearos.ble.MeshCoreProtocol
import com.meshcore.wearos.ble.ParsedFrame
import com.meshcore.wearos.ble.toHex
import com.meshcore.wearos.data.db.ChannelDao
import com.meshcore.wearos.data.db.ContactDao
import com.meshcore.wearos.data.db.MessageDao
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.DeviceInfo
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageDirection
import com.meshcore.wearos.data.model.MessageStatus
import com.meshcore.wearos.data.model.NodeType
import com.meshcore.wearos.service.MessageNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the MeshCore session on top of [BleManager].
 *
 * The companion firmware answers every command with exactly one response frame, in order
 * (pushes >= 0x80 and the contact stream are separate). Commands are therefore sent strictly
 * one at a time through [request], which waits for the matching response. This avoids
 * overflowing the radio's small receive queue and makes it possible to know which command an
 * OK/ERR belongs to - both were sources of lost commands in the previous version.
 */
@Singleton
class MeshCoreRepository @Inject constructor(
    private val bleManager: BleManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val channelDao: ChannelDao,
    private val notifier: MessageNotifier
) {
    private val tag = "MeshCore-Repo"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val commandLock = Mutex()
    @Volatile private var pendingResponse: CompletableDeferred<ParsedFrame>? = null
    @Volatile private var contactsDone: CompletableDeferred<Boolean>? = null
    private val receivedContactKeys = mutableSetOf<String>()
    @Volatile private var lastContactsMod = 0L
    private var incrementalSyncJob: Job? = null

    private val drainRequests = Channel<Unit>(Channel.CONFLATED)
    private var sessionJob: Job? = null

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _isSyncingContacts = MutableStateFlow(false)
    val isSyncingContacts: StateFlow<Boolean> = _isSyncingContacts.asStateFlow()

    /** True once the handshake (device query + app start) succeeded. */
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Conversation currently on screen - no notification for it. */
    @Volatile var activeConversation: String? = null

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices
    val contacts: Flow<List<Contact>> = contactDao.getAllContacts()
    val channels = channelDao.getAllChannels()
    val unreadTotal: Flow<Int> = combine(contactDao.observeUnreadTotal(), channelDao.observeUnreadTotal()) { a, b -> a + b }

    var pairingPin: String
        get() = bleManager.pairingPin
        set(value) { bleManager.pairingPin = value }

    val savedDeviceAddress: String? get() = bleManager.savedDeviceAddress
    val savedDeviceName: String? get() = bleManager.savedDeviceName

    init {
        // single consumer of incoming frames - keeps frame order intact
        scope.launch {
            bleManager.incomingFrames.collect { raw ->
                val frame = FrameParser.parse(raw)
                try {
                    handleFrame(frame)
                } catch (e: Exception) {
                    Log.e(tag, "Error handling $frame", e)
                }
            }
        }

        scope.launch {
            bleManager.connectionState.collect { state ->
                when (state) {
                    is BleConnectionState.Connected -> startSession()
                    else -> {
                        if (_sessionReady.value || sessionJob != null) endSession()
                    }
                }
            }
        }

        scope.launch {
            for (ignored in drainRequests) drainMessages()
        }

        scope.launch { messageDao.failStaleSending() }
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    fun hasPermissions() = bleManager.hasPermissions()
    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()
    fun connect(address: String) = bleManager.connect(address)
    fun connectToSavedDevice() = bleManager.connectToSavedDevice()
    fun disconnect() = bleManager.disconnect()
    fun forgetDevice() {
        bleManager.forgetDevice()
        _deviceInfo.value = null
    }

    private fun startSession() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            Log.i(tag, "Connected - starting MeshCore session")
            val devInfo = request(MeshCoreProtocol.buildDeviceQueryFrame()) as? ParsedFrame.DeviceInfo
            val self = request(MeshCoreProtocol.buildAppStartFrame()) as? ParsedFrame.SelfInfo
            if (self == null) {
                _lastError.value = "Radio did not answer APP_START"
                Log.e(tag, "Handshake failed")
                return@launch
            }
            val s = self.info
            val d = devInfo?.info
            _deviceInfo.value = DeviceInfo(
                publicKey = s.publicKey,
                name = s.name,
                nodeType = NodeType.fromAdvType(s.advType),
                txPowerDbm = s.txPowerDbm,
                radioFreqKhz = s.radioFreqKhz,
                radioBandwidthHz = s.radioBandwidthHz,
                spreadingFactor = s.spreadingFactor,
                codingRate = s.codingRate,
                firmwareVersion = d?.firmwareVersion ?: "",
                firmwareBuildDate = d?.buildDate ?: "",
                manufacturer = d?.manufacturer ?: "",
                maxContacts = d?.maxContacts ?: 0,
                maxChannels = d?.maxChannels ?: 0
            )
            _sessionReady.value = true
            _lastError.value = null

            // the watch has a reliable clock; the radio often does not (no RTC)
            request(MeshCoreProtocol.buildSetDeviceTimeFrame(System.currentTimeMillis() / 1000))
            fetchBatteryStatusInternal()
            syncContactsInternal()
            syncChannelsInternal(d?.maxChannels ?: 0)
            drainRequests.trySend(Unit)
        }
    }

    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
        _sessionReady.value = false
        _isSyncingContacts.value = false
        lastContactsMod = 0L
        pendingResponse?.complete(ParsedFrame.Err(-1))
        contactsDone?.complete(false)
        scope.launch { messageDao.failStaleSending() }
    }

    /**
     * Sends one command and waits for its response frame.
     * Returns null on timeout or when not connected.
     */
    private suspend fun request(frame: ByteArray, timeoutMs: Long = 5_000): ParsedFrame? =
        commandLock.withLock {
            val deferred = CompletableDeferred<ParsedFrame>()
            pendingResponse = deferred
            if (!bleManager.sendFrame(frame)) {
                pendingResponse = null
                return@withLock null
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            pendingResponse = null
            if (result == null) Log.w(tag, "Timeout waiting for response to cmd ${frame[0]}")
            result
        }

    // -------------------------------------------------------------------------
    // Incoming frames
    // -------------------------------------------------------------------------

    private suspend fun handleFrame(frame: ParsedFrame) {
        if (frame.isCommandResponse) {
            val pending = pendingResponse
            if (pending != null && !pending.isCompleted) {
                pending.complete(frame)
            } else {
                Log.w(tag, "Unsolicited response $frame")
            }
            return
        }
        when (frame) {
            is ParsedFrame.ContactFrame -> {
                receivedContactKeys += frame.contact.publicKeyHex
                contactDao.upsertFromRadio(frame.contact.toEntity())
            }
            is ParsedFrame.EndOfContacts -> {
                if (frame.mostRecentLastMod > lastContactsMod) lastContactsMod = frame.mostRecentLastMod
                contactsDone?.complete(true)
            }
            is ParsedFrame.NewAdvertPush -> contactDao.upsertFromRadio(frame.contact.toEntity())
            is ParsedFrame.AdvertPush, is ParsedFrame.PathUpdated -> {
                // a known contact re-advertised / route changed: refresh lastSeen + path (debounced)
                scheduleIncrementalSync()
            }
            is ParsedFrame.ContactDeleted -> contactDao.deleteContact(frame.publicKey.toHex())
            is ParsedFrame.MessageWaiting -> drainRequests.trySend(Unit)
            is ParsedFrame.SendConfirmed -> {
                val n = messageDao.markDeliveredByAck(frame.ackCode)
                Log.i(tag, "ACK ${frame.ackCode} (${frame.roundTripMs} ms) -> $n message(s) delivered")
            }
            is ParsedFrame.ContactsFull -> _lastError.value = "Contact storage on the radio is full"
            is ParsedFrame.Unknown, is ParsedFrame.OtherPush -> Log.d(tag, "Ignored frame $frame")
            else -> Log.d(tag, "Unhandled $frame")
        }
    }

    // -------------------------------------------------------------------------
    // Contacts
    // -------------------------------------------------------------------------

    fun syncContacts() {
        scope.launch { syncContactsInternal() }
    }

    /**
     * Full sync (removes contacts deleted on the radio) or incremental sync of contacts
     * modified since the last sync (used for advert / path pushes).
     */
    private suspend fun syncContactsInternal(incremental: Boolean = false) {
        if (!_sessionReady.value) return
        if (!_isSyncingContacts.compareAndSet(expect = false, update = true)) return
        try {
            val done = CompletableDeferred<Boolean>()
            contactsDone = done
            receivedContactKeys.clear()
            val since = if (incremental) lastContactsMod else 0L
            val start = request(MeshCoreProtocol.buildGetContactsFrame(since))
            if (start !is ParsedFrame.ContactsStart) {
                Log.w(tag, "GET_CONTACTS failed: $start")
                return
            }
            // ~150 bytes per contact; allow plenty of time for large lists
            val timeout = 10_000L + start.totalCount * 300L
            val complete = withTimeoutOrNull(timeout) { done.await() } ?: false
            if (complete && since > 0) {
                Log.i(tag, "Incremental contact sync: ${receivedContactKeys.size} updated")
            } else if (complete) {
                val keep = receivedContactKeys.toList()
                // contacts removed on the radio disappear on the watch, too
                if (keep.isNotEmpty() || start.totalCount == 0L) contactDao.deleteAllExcept(keep)
                Log.i(tag, "Contact sync complete: ${keep.size} contacts")
            } else {
                Log.w(tag, "Contact sync incomplete")
            }
            if (complete) attachOrphanMessages()
        } finally {
            contactsDone = null
            _isSyncingContacts.value = false
        }
    }

    /** Moves messages from then-unknown senders into the proper contact conversation. */
    private suspend fun attachOrphanMessages() {
        for (key in messageDao.getPrefixConversations()) {
            val prefix = key.removePrefix("c:")
            val contact = contactDao.getContactByPrefix(prefix) ?: continue
            messageDao.reassignConversation(key, Message.contactKey(contact.publicKeyHex), contact.publicKeyHex)
            contactDao.incrementUnread(contact.publicKeyHex)
        }
    }

    private fun scheduleIncrementalSync() {
        if (incrementalSyncJob?.isActive == true) return
        incrementalSyncJob = scope.launch {
            delay(3_000)
            syncContactsInternal(incremental = true)
        }
    }

    fun toggleFavorite(pubKeyHex: String) {
        scope.launch { contactDao.toggleFavorite(pubKeyHex) }
    }

    fun observeContact(pubKeyHex: String) = contactDao.observeContact(pubKeyHex)

    /** Forget the stored route to a contact so the next message floods (fixes "stuck" routes). */
    fun resetPath(contact: Contact) {
        scope.launch {
            if (request(MeshCoreProtocol.buildResetPathFrame(contact.publicKey)) is ParsedFrame.Ok) {
                syncContactsInternal()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Channels
    // -------------------------------------------------------------------------

    fun observeChannel(index: Int) = channelDao.observeChannel(index)

    private suspend fun syncChannelsInternal(maxChannels: Int) {
        val count = if (maxChannels > 0) maxChannels.coerceAtMost(MAX_CHANNELS_TO_QUERY) else 8
        val found = mutableListOf<Int>()
        for (i in 0 until count) {
            when (val r = request(MeshCoreProtocol.buildGetChannelFrame(i), timeoutMs = 2_000)) {
                is ParsedFrame.ChannelInfo -> if (!r.isEmpty) {
                    channelDao.upsertFromRadio(r.index, r.name.ifBlank { "Channel ${r.index}" })
                    found += r.index
                }
                is ParsedFrame.Err -> if (r.errorCode == MeshCoreProtocol.ERR_CODE_UNSUPPORTED_CMD) break
                null -> break
                else -> {}
            }
        }
        if (found.isNotEmpty()) channelDao.deleteAllExcept(found)
    }

    // -------------------------------------------------------------------------
    // Incoming messages
    // -------------------------------------------------------------------------

    fun syncMessages() {
        drainRequests.trySend(Unit)
    }

    private suspend fun drainMessages() {
        if (!_sessionReady.value) return
        // bounded loop: the radio's offline queue holds at most 16-32 messages
        repeat(64) {
            when (val r = request(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE))) {
                is ParsedFrame.ContactMessage -> storeContactMessage(r.msg)
                is ParsedFrame.ChannelMessage -> storeChannelMessage(r.msg)
                else -> return // NoMoreMessages, error or timeout
            }
        }
    }

    private suspend fun storeContactMessage(m: ContactMessageRecord) {
        if (m.txtType == MeshCoreProtocol.TXT_TYPE_CLI_DATA) return // repeater CLI output, not chat
        val contact = contactDao.getContactByPrefix(m.senderPrefixHex)
        val senderKey = contact?.publicKeyHex ?: m.senderPrefixHex
        val convKey = Message.contactKey(senderKey)
        if (messageDao.countDuplicates(convKey, m.senderTimestamp, m.text) > 0) return

        messageDao.insertMessage(
            Message(
                conversationKey = convKey,
                senderPubKeyHex = senderKey,
                senderName = contact?.name ?: "",
                text = m.text,
                timestamp = m.senderTimestamp,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                txtType = m.txtType,
                snr = m.snr,
                pathLen = m.pathLen
            )
        )
        val isActive = activeConversation == convKey
        if (contact != null) {
            contactDao.updateLastMessage(contact.publicKeyHex, m.text, nowSecs(), if (isActive) 0 else 1)
        } else {
            // unknown sender: fetch contacts so the message can be attributed
            scheduleIncrementalSync()
        }
        if (!isActive) notifier.notifyMessage(convKey, contact?.name ?: "Node ${m.senderPrefixHex.take(8)}", m.text)
    }

    private suspend fun storeChannelMessage(m: ChannelMessageRecord) {
        val convKey = Message.channelKey(m.channelIndex)
        val ownName = _deviceInfo.value?.name
        if (messageDao.countDuplicates(convKey, m.senderTimestamp, m.text) > 0) return
        messageDao.insertMessage(
            Message(
                conversationKey = convKey,
                senderName = m.senderName ?: "",
                text = m.text,
                timestamp = m.senderTimestamp,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                txtType = m.txtType,
                snr = m.snr,
                pathLen = m.pathLen,
                isChannelMessage = true,
                channelIndex = m.channelIndex
            )
        )
        if (channelDao.getChannel(m.channelIndex) == null) {
            channelDao.upsertFromRadio(m.channelIndex, if (m.channelIndex == 0) "Public" else "Channel ${m.channelIndex}")
        }
        val isActive = activeConversation == convKey
        val preview = if (m.senderName != null) "${m.senderName}: ${m.text}" else m.text
        channelDao.updateChannelLastMessage(m.channelIndex, preview, nowSecs(), if (isActive) 0 else 1)
        if (!isActive && m.senderName != ownName) {
            val chName = channelDao.getChannel(m.channelIndex)?.name ?: "Channel ${m.channelIndex}"
            notifier.notifyMessage(convKey, chName, preview)
        }
    }

    fun getMessagesForContact(pubKeyHex: String) = messageDao.getMessages(Message.contactKey(pubKeyHex))
    fun getMessagesForChannel(channelIndex: Int) = messageDao.getMessages(Message.channelKey(channelIndex))

    fun clearUnreadCount(pubKeyHex: String) {
        scope.launch { contactDao.clearUnreadCount(pubKeyHex) }
        notifier.cancel(Message.contactKey(pubKeyHex))
    }

    fun clearChannelUnread(index: Int) {
        scope.launch { channelDao.clearChannelUnreadCount(index) }
        notifier.cancel(Message.channelKey(index))
    }

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------

    /**
     * Sends a direct message. Retries up to [MAX_ATTEMPTS] times (the firmware uses the
     * attempt number to flood on the last try) and tracks the ACK.
     */
    fun sendDirectMessage(contact: Contact, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val timestamp = nowSecs()
            val id = messageDao.insertMessage(
                Message(
                    conversationKey = Message.contactKey(contact.publicKeyHex),
                    senderPubKeyHex = _deviceInfo.value?.publicKeyHex ?: "",
                    text = trimmed,
                    timestamp = timestamp,
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENDING
                )
            )
            contactDao.updateLastMessage(contact.publicKeyHex, trimmed, timestamp, 0)

            for (attempt in 0 until MAX_ATTEMPTS) {
                val frame = MeshCoreProtocol.buildSendTextMsgFrame(contact.publicKey, trimmed, timestamp, attempt)
                val r = request(frame)
                if (r !is ParsedFrame.Sent) {
                    Log.w(tag, "Send failed: $r")
                    messageDao.updateStatus(id, MessageStatus.FAILED)
                    if (r is ParsedFrame.Err && r.errorCode == MeshCoreProtocol.ERR_CODE_NOT_FOUND) {
                        _lastError.value = "${contact.name} is not in the radio's contact list"
                    }
                    return@launch
                }
                messageDao.updateSendState(id, MessageStatus.SENT, r.expectedAckCode, attempt)
                val wait = (if (r.estimatedTimeoutMs > 0) r.estimatedTimeoutMs else 10_000L)
                    .coerceIn(3_000L, 60_000L) + 2_000L
                delay(wait)
                val current = messageDao.getMessage(id) ?: return@launch
                if (current.status == MessageStatus.DELIVERED) return@launch
                if (!_sessionReady.value) break
            }
            val current = messageDao.getMessage(id)
            if (current != null && current.status != MessageStatus.DELIVERED) {
                messageDao.updateStatus(id, MessageStatus.FAILED)
            }
        }
    }

    fun retryMessage(message: Message, contact: Contact) {
        scope.launch {
            messageDao.deleteMessage(message.id)
            sendDirectMessage(contact, message.text)
        }
    }

    fun sendChannelMessage(channelIndex: Int, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val timestamp = nowSecs()
            val id = messageDao.insertMessage(
                Message(
                    conversationKey = Message.channelKey(channelIndex),
                    senderName = _deviceInfo.value?.name ?: "",
                    text = trimmed,
                    timestamp = timestamp,
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENDING,
                    isChannelMessage = true,
                    channelIndex = channelIndex
                )
            )
            val r = request(MeshCoreProtocol.buildSendChannelMsgFrame(channelIndex, trimmed, timestamp))
            // channel messages are flooded without ACK: OK means "sent"
            messageDao.updateStatus(id, if (r is ParsedFrame.Ok) MessageStatus.SENT else MessageStatus.FAILED)
            channelDao.updateChannelLastMessage(channelIndex, "Me: $trimmed", timestamp, 0)
        }
    }

    // -------------------------------------------------------------------------
    // Device
    // -------------------------------------------------------------------------

    fun fetchBatteryStatus() {
        scope.launch { fetchBatteryStatusInternal() }
    }

    private suspend fun fetchBatteryStatusInternal() {
        val r = request(MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_GET_BATT_AND_STORAGE))
        if (r is ParsedFrame.BatteryAndStorage) {
            _deviceInfo.value = _deviceInfo.value?.copy(
                batteryMillivolts = r.batteryMillivolts,
                usedStorage = r.usedKb,
                totalStorage = r.totalKb
            )
        }
    }

    /** Returns true when the radio accepted the advert. */
    suspend fun sendSelfAdvert(floodMode: Boolean): Boolean =
        request(MeshCoreProtocol.buildSelfAdvertFrame(floodMode)) is ParsedFrame.Ok

    private fun ContactRecord.toEntity() = Contact(
        publicKeyHex = publicKeyHex,
        publicKey = publicKey,
        name = name.ifBlank { "Node ${publicKeyHex.take(8)}" },
        nodeType = NodeType.fromAdvType(type),
        lastSeen = lastAdvertTimestamp,
        flags = flags,
        outPathLen = outPathLen,
        latitude = latitude,
        longitude = longitude,
        lastModified = lastModified
    )

    private fun nowSecs() = System.currentTimeMillis() / 1000

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_CHANNELS_TO_QUERY = 16
    }
}
