package com.meshcore.wearos.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// -------------------------------------------------------------------------
// Node Type
// -------------------------------------------------------------------------

enum class NodeType(val advType: Int, val displayName: String) {
    UNKNOWN(0, "Unknown"),
    CHAT(1, "Chat"),
    REPEATER(2, "Repeater"),
    ROOM(3, "Room");

    companion object {
        fun fromAdvType(advType: Int): NodeType =
            values().firstOrNull { it.advType == advType } ?: UNKNOWN
    }
}

// -------------------------------------------------------------------------
// Device Info (from RESP_CODE_SELF_INFO / RESP_CODE_DEVICE_INFO)
// -------------------------------------------------------------------------

data class DeviceInfo(
    val publicKey: ByteArray,
    val name: String,
    val nodeType: NodeType,
    val batteryMillivolts: Int = 0,
    val usedStorage: Int = 0,
    val totalStorage: Int = 0
) {
    val publicKeyHex: String get() = publicKey.joinToString("") { "%02x".format(it) }
    val batteryPercent: Int get() {
        // Approximate Li-Ion battery percentage from millivolts
        val mv = batteryMillivolts
        return when {
            mv >= 4200 -> 100
            mv >= 4000 -> 80 + (mv - 4000) / 10
            mv >= 3700 -> 20 + (mv - 3700) * 60 / 300
            mv >= 3500 -> (mv - 3500) * 20 / 200
            else -> 0
        }.coerceIn(0, 100)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceInfo
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode()
}

// -------------------------------------------------------------------------
// Contact (from RESP_CODE_CONTACT)
// -------------------------------------------------------------------------

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey
    val publicKeyHex: String,
    val publicKey: ByteArray,
    val name: String,
    val nodeType: NodeType = NodeType.UNKNOWN,
    val lastSeen: Long = 0L,
    val flags: Int = 0,
    val isFavorite: Boolean = false,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0
) {
    val isOnline: Boolean get() {
        val now = System.currentTimeMillis() / 1000
        return (now - lastSeen) < 3600 // Considered online if seen within 1 hour
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Contact
        return publicKeyHex == other.publicKeyHex
    }

    override fun hashCode(): Int = publicKeyHex.hashCode()
}

// -------------------------------------------------------------------------
// Message
// -------------------------------------------------------------------------

enum class MessageDirection { INCOMING, OUTGOING }

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val senderPubKeyHex: String,
    val recipientPubKeyHex: String = "",
    val text: String,
    val timestamp: Long,
    val direction: MessageDirection,
    val status: MessageStatus = MessageStatus.SENT,
    val txtType: Int = 0,
    val snr: Int = 0,
    val isChannelMessage: Boolean = false,
    val channelIndex: Int = 0,
    val expectedAckCode: Long = 0L
)

// -------------------------------------------------------------------------
// Channel
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
