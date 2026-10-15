package com.meshcore.wearos.ble

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol-level frames decoded from MeshCore companion radio notifications.
 *
 * These types deliberately carry raw protocol data only (no Room entities, no Android
 * classes) so the parser can be unit-tested on the plain JVM.
 */
sealed class ParsedFrame {
    /** True for frames that answer a command (as opposed to pushes and the contact stream). */
    open val isCommandResponse: Boolean = true

    data object Ok : ParsedFrame()
    data class Err(val errorCode: Int) : ParsedFrame()
    data object Disabled : ParsedFrame()

    data class ContactsStart(val totalCount: Long) : ParsedFrame()

    /** One contact of the GET_CONTACTS stream (not a command response of its own). */
    data class ContactFrame(val contact: ContactRecord) : ParsedFrame() {
        override val isCommandResponse: Boolean = false
    }

    /** Terminates the GET_CONTACTS stream; carries the newest lastmod for incremental syncs. */
    data class EndOfContacts(val mostRecentLastMod: Long) : ParsedFrame() {
        override val isCommandResponse: Boolean = false
    }

    data class SelfInfo(val info: SelfInfoRecord) : ParsedFrame()
    data class DeviceInfo(val info: DeviceInfoRecord) : ParsedFrame()

    data class Sent(val isFlood: Boolean, val expectedAckCode: Long, val estimatedTimeoutMs: Long) : ParsedFrame()

    data class ContactMessage(val msg: ContactMessageRecord) : ParsedFrame()
    data class ChannelMessage(val msg: ChannelMessageRecord) : ParsedFrame()
    data object NoMoreMessages : ParsedFrame()

    data class CurrentTime(val epochSecs: Long) : ParsedFrame()
    data class BatteryAndStorage(val batteryMillivolts: Int, val usedKb: Long, val totalKb: Long) : ParsedFrame()
    data class ChannelInfo(val index: Int, val name: String, val secret: ByteArray) : ParsedFrame() {
        val isEmpty: Boolean get() = name.isBlank() && secret.all { it == 0.toByte() }
        override fun equals(other: Any?) = other is ChannelInfo && other.index == index && other.name == name && other.secret.contentEquals(secret)
        override fun hashCode() = 31 * index + name.hashCode()
    }

    // ---- pushes --------------------------------------------------------------
    sealed class Push : ParsedFrame() {
        override val isCommandResponse: Boolean = false
    }

    /** An advert from a contact already in the device's contact list. */
    data class AdvertPush(val publicKey: ByteArray) : Push() {
        override fun equals(other: Any?) = other is AdvertPush && other.publicKey.contentEquals(publicKey)
        override fun hashCode() = publicKey.contentHashCode()
    }

    /** An advert from a node that is new to the device (only when manual-add is on). */
    data class NewAdvertPush(val contact: ContactRecord) : Push()

    data class PathUpdated(val publicKey: ByteArray) : Push() {
        override fun equals(other: Any?) = other is PathUpdated && other.publicKey.contentEquals(publicKey)
        override fun hashCode() = publicKey.contentHashCode()
    }

    data class SendConfirmed(val ackCode: Long, val roundTripMs: Long) : Push()
    data object MessageWaiting : Push()
    data class LoginSuccess(val raw: ByteArray) : Push()
    data object LoginFail : Push()
    data class ContactDeleted(val publicKey: ByteArray) : Push()
    data object ContactsFull : Push()
    data class OtherPush(val code: Byte, val raw: ByteArray) : Push()

    data class Unknown(val code: Byte, val raw: ByteArray) : ParsedFrame() {
        override val isCommandResponse: Boolean = false
    }
    data class Malformed(val code: Byte, val raw: ByteArray, val reason: String) : ParsedFrame() {
        // a malformed response still answers the pending command, otherwise the queue stalls
        override val isCommandResponse: Boolean = !MeshCoreProtocol.isPush(code) &&
            code != MeshCoreProtocol.RESP_CODE_CONTACT && code != MeshCoreProtocol.RESP_CODE_END_OF_CONTACTS
    }
}

data class ContactRecord(
    val publicKey: ByteArray,
    val type: Int,
    val flags: Int,
    /** -1 = unknown (flood), otherwise number of hops in the stored path. */
    val outPathLen: Int,
    val name: String,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long
) {
    val publicKeyHex: String get() = publicKey.toHex()
    override fun equals(other: Any?) = other is ContactRecord && other.publicKey.contentEquals(publicKey) &&
        other.name == name && other.lastModified == lastModified && other.outPathLen == outPathLen
    override fun hashCode() = publicKey.contentHashCode()
}

data class SelfInfoRecord(
    val advType: Int,
    val txPowerDbm: Int,
    val maxTxPowerDbm: Int,
    val publicKey: ByteArray,
    val latitude: Double,
    val longitude: Double,
    val radioFreqKhz: Long,
    val radioBandwidthHz: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val name: String
) {
    override fun equals(other: Any?) = other is SelfInfoRecord && other.publicKey.contentEquals(publicKey) && other.name == name
    override fun hashCode() = publicKey.contentHashCode()
}

data class DeviceInfoRecord(
    val firmwareVersionCode: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val buildDate: String,
    val manufacturer: String,
    val firmwareVersion: String
)

data class ContactMessageRecord(
    val senderPrefix: ByteArray,
    /** hop count if received via flood, -1 when it came over a direct route */
    val pathLen: Int,
    val txtType: Int,
    val senderTimestamp: Long,
    /** SNR in dB (only for protocol v3 frames, else null) */
    val snr: Float?,
    val text: String
) {
    val senderPrefixHex: String get() = senderPrefix.toHex()
    override fun equals(other: Any?) = other is ContactMessageRecord && other.senderPrefix.contentEquals(senderPrefix) &&
        other.senderTimestamp == senderTimestamp && other.text == text
    override fun hashCode() = 31 * senderPrefix.contentHashCode() + text.hashCode()
}

data class ChannelMessageRecord(
    val channelIndex: Int,
    val pathLen: Int,
    val txtType: Int,
    val senderTimestamp: Long,
    val snr: Float?,
    /** The firmware sends "<sender name>: <text>"; split here for display. */
    val senderName: String?,
    val text: String
)

/**
 * Stateless decoder: one BLE notification == one frame.
 *
 * (The previous "stream re-assembly" approach guessed frame boundaries by scanning for
 * byte values like 0x00/0x03 - which also occur inside public keys - and therefore
 * cut contacts and messages apart. Over BLE the firmware never splits or merges frames.)
 */
object FrameParser {

    fun parse(frame: ByteArray): ParsedFrame {
        if (frame.isEmpty()) return ParsedFrame.Unknown(0, frame)
        val code = frame[0]
        val p = Reader(frame, 1)
        return try {
            when (code) {
                MeshCoreProtocol.RESP_CODE_OK -> ParsedFrame.Ok
                MeshCoreProtocol.RESP_CODE_ERR -> ParsedFrame.Err(if (p.remaining > 0) p.u8() else 0)
                MeshCoreProtocol.RESP_CODE_DISABLED -> ParsedFrame.Disabled
                MeshCoreProtocol.RESP_CODE_CONTACTS_START -> ParsedFrame.ContactsStart(if (p.remaining >= 4) p.u32() else 0)
                MeshCoreProtocol.RESP_CODE_CONTACT -> ParsedFrame.ContactFrame(readContact(p))
                MeshCoreProtocol.RESP_CODE_END_OF_CONTACTS -> ParsedFrame.EndOfContacts(if (p.remaining >= 4) p.u32() else 0)
                MeshCoreProtocol.RESP_CODE_SELF_INFO -> ParsedFrame.SelfInfo(readSelfInfo(p))
                MeshCoreProtocol.RESP_CODE_DEVICE_INFO -> ParsedFrame.DeviceInfo(readDeviceInfo(p))
                MeshCoreProtocol.RESP_CODE_SENT -> {
                    val flood = p.u8() == 1
                    val ack = p.u32()
                    val timeout = if (p.remaining >= 4) p.u32() else 0
                    ParsedFrame.Sent(flood, ack, timeout)
                }
                MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV -> ParsedFrame.ContactMessage(readContactMsg(p, v3 = false))
                MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3 -> ParsedFrame.ContactMessage(readContactMsg(p, v3 = true))
                MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV -> ParsedFrame.ChannelMessage(readChannelMsg(p, v3 = false))
                MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV_V3 -> ParsedFrame.ChannelMessage(readChannelMsg(p, v3 = true))
                MeshCoreProtocol.RESP_CODE_NO_MORE_MESSAGES -> ParsedFrame.NoMoreMessages
                MeshCoreProtocol.RESP_CODE_CURR_TIME -> ParsedFrame.CurrentTime(p.u32())
                MeshCoreProtocol.RESP_CODE_BATT_AND_STORAGE -> {
                    val mv = p.u16()
                    val used = if (p.remaining >= 4) p.u32() else 0
                    val total = if (p.remaining >= 4) p.u32() else 0
                    ParsedFrame.BatteryAndStorage(mv, used, total)
                }
                MeshCoreProtocol.RESP_CODE_CHANNEL_INFO -> {
                    val idx = p.u8()
                    val name = p.cString(32)
                    val secret = if (p.remaining >= 16) p.bytes(16) else ByteArray(16)
                    ParsedFrame.ChannelInfo(idx, name, secret)
                }

                MeshCoreProtocol.PUSH_CODE_ADVERT -> ParsedFrame.AdvertPush(p.bytes(MeshCoreProtocol.PUB_KEY_SIZE))
                MeshCoreProtocol.PUSH_CODE_NEW_ADVERT -> ParsedFrame.NewAdvertPush(readContact(p))
                MeshCoreProtocol.PUSH_CODE_PATH_UPDATED -> ParsedFrame.PathUpdated(p.bytes(MeshCoreProtocol.PUB_KEY_SIZE))
                MeshCoreProtocol.PUSH_CODE_SEND_CONFIRMED -> ParsedFrame.SendConfirmed(p.u32(), if (p.remaining >= 4) p.u32() else 0)
                MeshCoreProtocol.PUSH_CODE_MSG_WAITING -> ParsedFrame.MessageWaiting
                MeshCoreProtocol.PUSH_CODE_LOGIN_SUCCESS -> ParsedFrame.LoginSuccess(frame.copyOfRange(1, frame.size))
                MeshCoreProtocol.PUSH_CODE_LOGIN_FAIL -> ParsedFrame.LoginFail
                MeshCoreProtocol.PUSH_CODE_CONTACT_DELETED -> ParsedFrame.ContactDeleted(p.bytes(MeshCoreProtocol.PUB_KEY_SIZE))
                MeshCoreProtocol.PUSH_CODE_CONTACTS_FULL -> ParsedFrame.ContactsFull
                else -> if (MeshCoreProtocol.isPush(code)) ParsedFrame.OtherPush(code, frame)
                        else ParsedFrame.Malformed(code, frame, "unhandled response code ${code.toInt() and 0xFF}")
            }
        } catch (e: BufferUnderflowException) {
            ParsedFrame.Malformed(code, frame, "frame too short (${frame.size} bytes)")
        } catch (e: IllegalArgumentException) {
            ParsedFrame.Malformed(code, frame, e.message ?: "invalid frame")
        }
    }

    // [pub_key 32][type][flags][out_path_len i8][out_path 64][name 32][last_advert u32][lat i32][lon i32][lastmod u32]
    private fun readContact(p: Reader): ContactRecord {
        val pubKey = p.bytes(MeshCoreProtocol.PUB_KEY_SIZE)
        val type = p.u8()
        val flags = p.u8()
        val pathLen = p.i8()
        p.skip(MeshCoreProtocol.MAX_PATH_SIZE)
        val name = p.cString(MeshCoreProtocol.MAX_NAME_SIZE)
        val lastAdvert = p.u32()
        val lat = if (p.remaining >= 4) p.i32() / 1_000_000.0 else 0.0
        val lon = if (p.remaining >= 4) p.i32() / 1_000_000.0 else 0.0
        val lastMod = if (p.remaining >= 4) p.u32() else 0
        return ContactRecord(pubKey, type, flags, if (pathLen < 0) -1 else pathLen, name, lastAdvert, lat, lon, lastMod)
    }

    // [type][tx_power][max_tx_power][pub_key 32][lat i32][lon i32][multi_acks][advert_loc_policy]
    // [telemetry modes][manual_add][freq u32][bw u32][sf][cr][name...]
    private fun readSelfInfo(p: Reader): SelfInfoRecord {
        val type = p.u8()
        val tx = p.i8()
        val maxTx = p.i8()
        val pubKey = p.bytes(MeshCoreProtocol.PUB_KEY_SIZE)
        val lat = p.i32() / 1_000_000.0
        val lon = p.i32() / 1_000_000.0
        p.skip(4) // multi_acks, advert_loc_policy, telemetry modes, manual_add_contacts
        val freq = p.u32()
        val bw = p.u32()
        val sf = p.u8()
        val cr = p.u8()
        val name = p.restString()
        return SelfInfoRecord(type, tx, maxTx, pubKey, lat, lon, freq, bw, sf, cr, name)
    }

    // [fw_ver][max_contacts/2][max_channels][ble_pin u32][build_date 12][manufacturer 40][version 20]...
    private fun readDeviceInfo(p: Reader): DeviceInfoRecord {
        val fwVer = p.u8()
        if (fwVer < 3 || p.remaining < 2) {
            return DeviceInfoRecord(fwVer, 0, 0, "", "", "")
        }
        val maxContacts = p.u8() * 2
        val maxChannels = p.u8()
        p.skip(4) // BLE pin - never keep it around
        val buildDate = p.cString(12)
        val manufacturer = p.cString(40)
        val version = p.cString(20)
        return DeviceInfoRecord(fwVer, maxContacts, maxChannels, buildDate, manufacturer, version)
    }

    // v3: [snr i8 (x4)][res][res] then common part
    // [pub_key prefix 6][path_len][txt_type][sender_timestamp u32][(signed: 4 byte prefix)][text]
    private fun readContactMsg(p: Reader, v3: Boolean): ContactMessageRecord {
        var snr: Float? = null
        if (v3) {
            snr = p.i8() / 4f
            p.skip(2)
        }
        val prefix = p.bytes(MeshCoreProtocol.PUB_KEY_PREFIX_SIZE)
        val pathLen = p.u8()
        val txtType = p.u8()
        val ts = p.u32()
        if (txtType == MeshCoreProtocol.TXT_TYPE_SIGNED_PLAIN && p.remaining >= 4) p.skip(4)
        val text = p.restString()
        return ContactMessageRecord(prefix, if (pathLen == 0xFF) -1 else pathLen, txtType, ts, snr, text)
    }

    // v3: [snr][res][res] then [channel_idx][path_len][txt_type][timestamp u32][text]
    private fun readChannelMsg(p: Reader, v3: Boolean): ChannelMessageRecord {
        var snr: Float? = null
        if (v3) {
            snr = p.i8() / 4f
            p.skip(2)
        }
        val idx = p.u8()
        val pathLen = p.u8()
        val txtType = p.u8()
        val ts = p.u32()
        val full = p.restString()
        val sep = full.indexOf(": ")
        val (sender, text) = if (sep in 1..32) full.substring(0, sep) to full.substring(sep + 2) else null to full
        return ChannelMessageRecord(idx, if (pathLen == 0xFF) -1 else pathLen, txtType, ts, snr, sender, text)
    }

    /** Little-endian reader over a frame. */
    private class Reader(data: ByteArray, offset: Int) {
        private val buf: ByteBuffer = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        val remaining: Int get() = buf.remaining()
        fun u8(): Int = buf.get().toInt() and 0xFF
        fun i8(): Int = buf.get().toInt()
        fun u16(): Int = buf.short.toInt() and 0xFFFF
        fun i32(): Int = buf.int
        fun u32(): Long = buf.int.toLong() and 0xFFFFFFFFL
        fun bytes(n: Int): ByteArray = ByteArray(n).also { buf.get(it) }
        fun skip(n: Int) {
            if (buf.remaining() < n) throw BufferUnderflowException()
            buf.position(buf.position() + n)
        }
        /** Fixed-size, NUL-padded string field. Tolerates a truncated last field. */
        fun cString(size: Int): String {
            val b = bytes(minOf(size, buf.remaining()))
            val end = b.indexOf(0).let { if (it < 0) b.size else it }
            return String(b, 0, end, Charsets.UTF_8)
        }
        /** Rest of the frame as UTF-8 (trailing NULs dropped). */
        fun restString(): String {
            val b = bytes(buf.remaining())
            var end = b.size
            while (end > 0 && b[end - 1] == 0.toByte()) end--
            return String(b, 0, end, Charsets.UTF_8)
        }
    }
}
