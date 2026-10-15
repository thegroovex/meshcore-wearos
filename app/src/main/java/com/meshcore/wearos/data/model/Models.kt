package com.meshcore.wearos.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// -------------------------------------------------------------------------
// Node Type
// -------------------------------------------------------------------------

enum class NodeType(val advType: Int, val displayName: String) {
    UNKNOWN(0, "Unknown"),
    CHAT(1, "Chat"),
    REPEATER(2, "Repeater"),
    ROOM(3, "Room"),
    SENSOR(4, "Sensor");

    companion object {
        fun fromAdvType(advType: Int): NodeType = entries.firstOrNull { it.advType == advType } ?: UNKNOWN
    }
}

// -------------------------------------------------------------------------
// Connected radio (RESP_CODE_SELF_INFO + RESP_CODE_DEVICE_INFO + battery)
// -------------------------------------------------------------------------

data class DeviceInfo(
    val publicKey: ByteArray,
    val name: String,
    val nodeType: NodeType,
    val txPowerDbm: Int = 0,
    val radioFreqKhz: Long = 0,
    val radioBandwidthHz: Long = 0,
    val spreadingFactor: Int = 0,
    val codingRate: Int = 0,
    val firmwareVersion: String = "",
    val firmwareBuildDate: String = "",
    val manufacturer: String = "",
    val maxContacts: Int = 0,
    val maxChannels: Int = 0,
    val batteryMillivolts: Int = 0,
    val usedStorage: Long = 0,
    val totalStorage: Long = 0
) {
    val publicKeyHex: String get() = publicKey.joinToString("") { "%02x".format(it) }

    /** Approximate Li-Ion state of charge. */
    val batteryPercent: Int get() {
        val mv = batteryMillivolts
        return when {
            mv <= 0 -> 0
            mv >= 4200 -> 100
            mv >= 4000 -> 80 + (mv - 4000) / 10
            mv >= 3700 -> 20 + (mv - 3700) * 60 / 300
            mv >= 3500 -> (mv - 3500) * 20 / 200
            else -> 0
        }.coerceIn(0, 100)
    }

    val radioSummary: String get() =
        if (radioFreqKhz <= 0) "" else
            "%.3f MHz · BW %.1f · SF%d · CR%d".format(radioFreqKhz / 1000.0, radioBandwidthHz / 1000.0, spreadingFactor, codingRate)

    override fun equals(other: Any?): Boolean =
        other is DeviceInfo && publicKey.contentEquals(other.publicKey) && name == other.name &&
            batteryMillivolts == other.batteryMillivolts && firmwareVersion == other.firmwareVersion &&
            usedStorage == other.usedStorage && radioFreqKhz == other.radioFreqKhz && maxChannels == other.maxChannels

    override fun hashCode(): Int = publicKey.contentHashCode()
}

// -------------------------------------------------------------------------
// Contact (RESP_CODE_CONTACT)
// -------------------------------------------------------------------------

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey
    val publicKeyHex: String,
    val publicKey: ByteArray,
    val name: String,
    val nodeType: NodeType = NodeType.UNKNOWN,
    /** Timestamp of the contact's last advert (contact's clock, epoch secs). */
    val lastSeen: Long = 0L,
    val flags: Int = 0,
    /** -1 = no known route (flood), else hop count. */
    val outPathLen: Int = -1,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val lastModified: Long = 0L,
    val isFavorite: Boolean = false,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0
) {
    val publicKeyPrefixHex: String get() = publicKeyHex.take(12)

    val isRecentlySeen: Boolean get() {
        val now = System.currentTimeMillis() / 1000
        return lastSeen > 0 && (now - lastSeen) < 3 * 3600
    }

    val routeLabel: String get() = when {
        outPathLen < 0 -> "flood"
        outPathLen == 0 -> "direct"
        else -> "$outPathLen hop" + if (outPathLen > 1) "s" else ""
    }

    override fun equals(other: Any?): Boolean =
        other is Contact && publicKeyHex == other.publicKeyHex && name == other.name &&
            lastSeen == other.lastSeen && outPathLen == other.outPathLen && unreadCount == other.unreadCount &&
            lastMessageText == other.lastMessageText && lastMessageTimestamp == other.lastMessageTimestamp &&
            isFavorite == other.isFavorite && nodeType == other.nodeType

    override fun hashCode(): Int = publicKeyHex.hashCode()
}

// -------------------------------------------------------------------------
// Message
// -------------------------------------------------------------------------

enum class MessageDirection { INCOMING, OUTGOING }

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

@Entity(
    tableName = "messages",
    indices = [Index("conversationKey"), Index("expectedAckCode")]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** "c:<full pubkey hex>" for direct chats, "ch:<index>" for channels. */
    val conversationKey: String,
    /** Contact pubkey (or 6-byte prefix if the sender was unknown) / sender name for channels. */
    val senderPubKeyHex: String = "",
    val senderName: String = "",
    val text: String,
    /** Sender's timestamp (epoch secs) as sent over the mesh. */
    val timestamp: Long,
    /** Local receive/send time (epoch millis), used for ordering. */
    val receivedAt: Long = System.currentTimeMillis(),
    val direction: MessageDirection,
    val status: MessageStatus = MessageStatus.SENT,
    val txtType: Int = 0,
    val snr: Float? = null,
    val pathLen: Int = -1,
    val isChannelMessage: Boolean = false,
    val channelIndex: Int = 0,
    val expectedAckCode: Long = 0L,
    val attempt: Int = 0
) {
    companion object {
        fun contactKey(pubKeyHex: String) = "c:$pubKeyHex"
        fun channelKey(index: Int) = "ch:$index"
    }
}

// -------------------------------------------------------------------------
// Channel (RESP_CODE_CHANNEL_INFO)
// -------------------------------------------------------------------------

@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey
    val index: Int,
    val name: String,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0
)
