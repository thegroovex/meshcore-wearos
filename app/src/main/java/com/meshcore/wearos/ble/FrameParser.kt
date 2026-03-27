package com.meshcore.wearos.ble

import android.util.Log
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.DeviceInfo
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageDirection
import com.meshcore.wearos.data.model.MessageStatus
import com.meshcore.wearos.data.model.NodeType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sealed class hierarchy representing all parsed MeshCore protocol frames.
 */
sealed class ParsedFrame {
    data object Ok : ParsedFrame()
    data class Err(val errorCode: Int) : ParsedFrame()
    data class SelfInfo(val deviceInfo: DeviceInfo) : ParsedFrame()
    data class DeviceInfoFrame(val info: com.meshcore.wearos.data.model.DeviceInfo) : ParsedFrame()
    data object ContactsStart : ParsedFrame()
    data class ContactFrame(val contact: Contact) : ParsedFrame()
    data object EndOfContacts : ParsedFrame()
    data class MessageReceived(val message: Message) : ParsedFrame()
    data object NoMoreMessages : ParsedFrame()
    data class Sent(val expectedAckCode: Long) : ParsedFrame()
    data class CurrentTime(val epochSecs: Long) : ParsedFrame()
    data class BatteryAndStorage(
        val batteryMillivolts: Int,
        val usedStorage: Int,
        val totalStorage: Int
    ) : ParsedFrame()
    data class AdvertPush(val pubKeyPrefix: ByteArray) : ParsedFrame()
    data object PathUpdated : ParsedFrame()
    data class SendConfirmed(val ackCode: Long, val roundTripMs: Long) : ParsedFrame()
    data object MessageWaiting : ParsedFrame()
    data class LoginSuccess(val pubKey: ByteArray) : ParsedFrame()
    data object LoginFail : ParsedFrame()
    data class Unknown(val code: Byte, val rawData: ByteArray) : ParsedFrame()
}

/**
 * FrameParser decodes raw BLE frames received from a MeshCore companion radio.
 */
object FrameParser {
    private const val TAG = "MeshCore-Parser"

    fun parse(frame: ByteArray): ParsedFrame {
        if (frame.isEmpty()) return ParsedFrame.Unknown(0, frame)

        val code = frame[0]
        val payload = if (frame.size > 1) frame.copyOfRange(1, frame.size) else ByteArray(0)

        return try {
            when (code) {
                MeshCoreProtocol.RESP_CODE_OK -> ParsedFrame.Ok
                MeshCoreProtocol.RESP_CODE_ERR -> parseErr(payload)
                MeshCoreProtocol.RESP_CODE_CONTACTS_START -> ParsedFrame.ContactsStart
                MeshCoreProtocol.RESP_CODE_CONTACT -> parseContact(payload)
                MeshCoreProtocol.RESP_CODE_END_OF_CONTACTS -> ParsedFrame.EndOfContacts
                MeshCoreProtocol.RESP_CODE_SELF_INFO -> parseSelfInfo(payload)
                MeshCoreProtocol.RESP_CODE_SENT -> parseSent(payload)
                MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV,
                MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3 -> parseContactMessage(payload, code)
                MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV,
                MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV_V3 -> parseChannelMessage(payload, code)
                MeshCoreProtocol.RESP_CODE_NO_MORE_MESSAGES -> ParsedFrame.NoMoreMessages
                MeshCoreProtocol.RESP_CODE_CURR_TIME -> parseCurrentTime(payload)
                MeshCoreProtocol.RESP_CODE_BATT_AND_STORAGE -> parseBatteryAndStorage(payload)
                MeshCoreProtocol.RESP_CODE_DEVICE_INFO -> parseDeviceInfo(payload)
                MeshCoreProtocol.PUSH_CODE_ADVERT -> parseAdvertPush(payload)
                MeshCoreProtocol.PUSH_CODE_PATH_UPDATED -> ParsedFrame.PathUpdated
                MeshCoreProtocol.PUSH_CODE_SEND_CONFIRMED -> parseSendConfirmed(payload)
                MeshCoreProtocol.PUSH_CODE_MSG_WAITING -> ParsedFrame.MessageWaiting
                MeshCoreProtocol.PUSH_CODE_LOGIN_SUCCESS -> parseLoginSuccess(payload)
                MeshCoreProtocol.PUSH_CODE_LOGIN_FAIL -> ParsedFrame.LoginFail
                else -> {
                    Log.w(TAG, "Unknown code: $code (hex: ${"%02x".format(code)})")
                    ParsedFrame.Unknown(code, frame)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing frame code $code: ${e.message}")
            ParsedFrame.Unknown(code, frame)
        }
    }

    private fun parseErr(payload: ByteArray): ParsedFrame {
        val errorCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
        return ParsedFrame.Err(errorCode)
    }

    private fun parseSelfInfo(payload: ByteArray): ParsedFrame {
        if (payload.size < 32) return ParsedFrame.Unknown(MeshCoreProtocol.RESP_CODE_SELF_INFO, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val pubKey = ByteArray(32).also { buf.get(it) }
        val advType = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
        val nameBytes = ByteArray(buf.remaining())
        buf.get(nameBytes)
        val name = nameBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        return ParsedFrame.SelfInfo(DeviceInfo(pubKey, name, NodeType.fromAdvType(advType)))
    }

    private fun parseDeviceInfo(payload: ByteArray): ParsedFrame {
        if (payload.size < 32) return ParsedFrame.Unknown(MeshCoreProtocol.RESP_CODE_DEVICE_INFO, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val pubKey = ByteArray(32).also { buf.get(it) }
        val advType = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
        val nameBytes = ByteArray(buf.remaining())
        buf.get(nameBytes)
        val name = nameBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        return ParsedFrame.DeviceInfoFrame(com.meshcore.wearos.data.model.DeviceInfo(pubKey, name, NodeType.fromAdvType(advType)))
    }

    private fun parseContact(payload: ByteArray): ParsedFrame {
        // V3 Contact Frame: 32 bytes pubkey + 1 byte advType + 1 byte flags + 4 bytes lastSeen + name...
        if (payload.size < 32) return ParsedFrame.Unknown(MeshCoreProtocol.RESP_CODE_CONTACT, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val pubKey = ByteArray(32).also { buf.get(it) }
        val advType = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
        val flags = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
        val lastSeen = if (buf.remaining() >= 4) buf.int.toLong() and 0xFFFFFFFFL else 0L
        val nameBytes = ByteArray(buf.remaining())
        buf.get(nameBytes)
        val name = nameBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        
        Log.d(TAG, "Parsed contact: $name (${pubKey.toHexString().take(8)}...)")
        
        // Contact constructor: publicKeyHex, publicKey, name, nodeType, lastSeen, flags, ...
        val contact = Contact(
            publicKeyHex = pubKey.toHexString(),
            publicKey = pubKey,
            name = name,
            nodeType = NodeType.fromAdvType(advType),
            lastSeen = lastSeen,
            flags = flags
        )
        return ParsedFrame.ContactFrame(contact)
    }

    private fun parseContactMessage(payload: ByteArray, code: Byte): ParsedFrame {
        if (payload.size < 32) return ParsedFrame.Unknown(code, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val senderPubKey = ByteArray(32).also { buf.get(it) }
        val timestamp = if (buf.remaining() >= 4) buf.int.toLong() and 0xFFFFFFFFL else 0L
        val txtType = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
        val snr = if (buf.hasRemaining()) buf.get().toInt() else 0
        val msgBytes = ByteArray(buf.remaining())
        buf.get(msgBytes)
        val text = msgBytes.toString(Charsets.UTF_8)
        
        // Message constructor: id, senderPubKeyHex, recipientPubKeyHex, text, timestamp, direction, status, txtType, snr, isChannelMessage, channelIndex, expectedAckCode
        val message = Message(
            senderPubKeyHex = senderPubKey.toHexString(),
            text = text,
            timestamp = timestamp,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.SENT,
            txtType = txtType,
            snr = snr,
            isChannelMessage = false
        )
        return ParsedFrame.MessageReceived(message)
    }

    private fun parseChannelMessage(payload: ByteArray, code: Byte): ParsedFrame {
        if (payload.size < 1) return ParsedFrame.Unknown(code, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val channelIdx = buf.get().toInt() and 0xFF
        val senderPubKey = if (buf.remaining() >= 32) ByteArray(32).also { buf.get(it) } else ByteArray(32)
        val timestamp = if (buf.remaining() >= 4) buf.int.toLong() and 0xFFFFFFFFL else 0L
        val txtType = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
        val snr = if (buf.hasRemaining()) buf.get().toInt() else 0
        val msgBytes = ByteArray(buf.remaining())
        buf.get(msgBytes)
        val text = msgBytes.toString(Charsets.UTF_8)
        
        // Message constructor: id, senderPubKeyHex, recipientPubKeyHex, text, timestamp, direction, status, txtType, snr, isChannelMessage, channelIndex, expectedAckCode
        val message = Message(
            senderPubKeyHex = senderPubKey.toHexString(),
            text = text,
            timestamp = timestamp,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.SENT,
            txtType = txtType,
            snr = snr,
            isChannelMessage = true,
            channelIndex = channelIdx
        )
        return ParsedFrame.MessageReceived(message)
    }

    private fun parseSent(payload: ByteArray): ParsedFrame {
        val ackCode = if (payload.size >= 4) ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL else 0L
        return ParsedFrame.Sent(ackCode)
    }

    private fun parseCurrentTime(payload: ByteArray): ParsedFrame {
        val epochSecs = if (payload.size >= 4) ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL else 0L
        return ParsedFrame.CurrentTime(epochSecs)
    }

    private fun parseBatteryAndStorage(payload: ByteArray): ParsedFrame {
        if (payload.size < 6) return ParsedFrame.Unknown(MeshCoreProtocol.RESP_CODE_BATT_AND_STORAGE, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return ParsedFrame.BatteryAndStorage(buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF)
    }

    private fun parseAdvertPush(payload: ByteArray): ParsedFrame {
        val pubKeyPrefix = if (payload.size >= 6) payload.copyOfRange(1, 7) else ByteArray(6)
        return ParsedFrame.AdvertPush(pubKeyPrefix)
    }

    private fun parseSendConfirmed(payload: ByteArray): ParsedFrame {
        if (payload.size < 9) return ParsedFrame.Unknown(MeshCoreProtocol.PUSH_CODE_SEND_CONFIRMED, payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // reserved
        return ParsedFrame.SendConfirmed(buf.int.toLong() and 0xFFFFFFFFL, buf.int.toLong() and 0xFFFFFFFFL)
    }

    private fun parseLoginSuccess(payload: ByteArray): ParsedFrame {
        val pubKey = if (payload.size >= 33) payload.copyOfRange(1, 33) else ByteArray(32)
        return ParsedFrame.LoginSuccess(pubKey)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
