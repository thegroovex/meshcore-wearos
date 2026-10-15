package com.meshcore.wearos.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MeshCore Companion Radio Protocol (BLE / Nordic UART transport).
 *
 * All values below are taken from the reference firmware
 * (meshcore-dev/MeshCore, examples/companion_radio/MyMesh.cpp).
 *
 * Transport rules that matter for BLE:
 *  - Every BLE write to the RX characteristic is exactly ONE command frame.
 *  - Every BLE notification on the TX characteristic is exactly ONE response/push frame.
 *    There is no length prefix and no stream framing over BLE, so frames must never be
 *    concatenated or split. The client has to negotiate an MTU large enough for the
 *    biggest frame (MAX_FRAME_SIZE = 176 bytes) or notifications get truncated.
 *  - All multi-byte integers are little-endian.
 */
object MeshCoreProtocol {

    // BLE Nordic UART Service (NUS) UUIDs
    const val NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NUS_RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // write  (app -> radio)
    const val NUS_TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // notify (radio -> app)
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /** Firmware advertises as "MeshCore-<node name>". */
    const val MESHCORE_DEVICE_NAME_PREFIX = "MeshCore"

    /** Largest frame the firmware sends or accepts. */
    const val MAX_FRAME_SIZE = 176

    /** MTU we ask for: frame + 3 bytes ATT header, with some headroom. */
    const val DESIRED_MTU = 185

    /** Protocol version this client understands (v3 = SNR in message frames). */
    const val APP_PROTOCOL_VERSION: Byte = 3

    const val APP_NAME = "MeshCore WearOS"

    const val PUB_KEY_SIZE = 32
    const val PUB_KEY_PREFIX_SIZE = 6
    const val MAX_PATH_SIZE = 64
    const val MAX_NAME_SIZE = 32

    /** Max UTF-8 bytes of a direct message the firmware accepts (MAX_TEXT_LEN = 10*16). */
    const val MAX_TEXT_BYTES = 150

    // -------------------------------------------------------------------------
    // Commands (App -> Radio)
    // -------------------------------------------------------------------------
    const val CMD_APP_START: Byte = 1
    const val CMD_SEND_TXT_MSG: Byte = 2
    const val CMD_SEND_CHANNEL_TXT_MSG: Byte = 3
    const val CMD_GET_CONTACTS: Byte = 4
    const val CMD_GET_DEVICE_TIME: Byte = 5
    const val CMD_SET_DEVICE_TIME: Byte = 6
    const val CMD_SEND_SELF_ADVERT: Byte = 7
    const val CMD_SET_ADVERT_NAME: Byte = 8
    const val CMD_ADD_UPDATE_CONTACT: Byte = 9
    const val CMD_SYNC_NEXT_MESSAGE: Byte = 10
    const val CMD_SET_RADIO_PARAMS: Byte = 11
    const val CMD_SET_RADIO_TX_POWER: Byte = 12
    const val CMD_RESET_PATH: Byte = 13
    const val CMD_SET_ADVERT_LATLON: Byte = 14
    const val CMD_REMOVE_CONTACT: Byte = 15
    const val CMD_SHARE_CONTACT: Byte = 16
    const val CMD_EXPORT_CONTACT: Byte = 17
    const val CMD_IMPORT_CONTACT: Byte = 18
    const val CMD_REBOOT: Byte = 19
    const val CMD_GET_BATT_AND_STORAGE: Byte = 20
    const val CMD_SET_TUNING_PARAMS: Byte = 21
    const val CMD_DEVICE_QUERY: Byte = 22
    const val CMD_SEND_RAW_DATA: Byte = 25
    const val CMD_SEND_LOGIN: Byte = 26
    const val CMD_SEND_STATUS_REQ: Byte = 27
    const val CMD_GET_CONTACT_BY_KEY: Byte = 30
    const val CMD_GET_CHANNEL: Byte = 31
    const val CMD_SET_CHANNEL: Byte = 32
    const val CMD_SEND_TRACE_PATH: Byte = 36
    const val CMD_SET_OTHER_PARAMS: Byte = 38
    const val CMD_GET_CUSTOM_VARS: Byte = 40
    const val CMD_SET_CUSTOM_VAR: Byte = 41
    const val CMD_GET_ADVERT_PATH: Byte = 42
    const val CMD_GET_TUNING_PARAMS: Byte = 43
    const val CMD_SEND_BINARY_REQ: Byte = 50
    const val CMD_FACTORY_RESET: Byte = 51
    const val CMD_GET_STATS: Byte = 56

    // -------------------------------------------------------------------------
    // Responses (Radio -> App, reply to exactly one command)
    // -------------------------------------------------------------------------
    const val RESP_CODE_OK: Byte = 0
    const val RESP_CODE_ERR: Byte = 1
    const val RESP_CODE_CONTACTS_START: Byte = 2
    const val RESP_CODE_CONTACT: Byte = 3
    const val RESP_CODE_END_OF_CONTACTS: Byte = 4
    const val RESP_CODE_SELF_INFO: Byte = 5
    const val RESP_CODE_SENT: Byte = 6
    const val RESP_CODE_CONTACT_MSG_RECV: Byte = 7
    const val RESP_CODE_CHANNEL_MSG_RECV: Byte = 8
    const val RESP_CODE_CURR_TIME: Byte = 9
    const val RESP_CODE_NO_MORE_MESSAGES: Byte = 10
    const val RESP_CODE_EXPORT_CONTACT: Byte = 11
    const val RESP_CODE_BATT_AND_STORAGE: Byte = 12
    const val RESP_CODE_DEVICE_INFO: Byte = 13
    const val RESP_CODE_PRIVATE_KEY: Byte = 14
    const val RESP_CODE_DISABLED: Byte = 15
    const val RESP_CODE_CONTACT_MSG_RECV_V3: Byte = 16
    const val RESP_CODE_CHANNEL_MSG_RECV_V3: Byte = 17
    const val RESP_CODE_CHANNEL_INFO: Byte = 18

    // -------------------------------------------------------------------------
    // Push notifications (Radio -> App, unsolicited, code >= 0x80)
    // -------------------------------------------------------------------------
    const val PUSH_CODE_ADVERT: Byte = 0x80.toByte()
    const val PUSH_CODE_PATH_UPDATED: Byte = 0x81.toByte()
    const val PUSH_CODE_SEND_CONFIRMED: Byte = 0x82.toByte()
    const val PUSH_CODE_MSG_WAITING: Byte = 0x83.toByte()
    const val PUSH_CODE_RAW_DATA: Byte = 0x84.toByte()
    const val PUSH_CODE_LOGIN_SUCCESS: Byte = 0x85.toByte()
    const val PUSH_CODE_LOGIN_FAIL: Byte = 0x86.toByte()
    const val PUSH_CODE_STATUS_RESPONSE: Byte = 0x87.toByte()
    const val PUSH_CODE_LOG_RX_DATA: Byte = 0x88.toByte()
    const val PUSH_CODE_TRACE_DATA: Byte = 0x89.toByte()
    const val PUSH_CODE_NEW_ADVERT: Byte = 0x8A.toByte()
    const val PUSH_CODE_TELEMETRY_RESPONSE: Byte = 0x8B.toByte()
    const val PUSH_CODE_BINARY_RESPONSE: Byte = 0x8C.toByte()
    const val PUSH_CODE_PATH_DISCOVERY_RESPONSE: Byte = 0x8D.toByte()
    const val PUSH_CODE_CONTROL_DATA: Byte = 0x8E.toByte()
    const val PUSH_CODE_CONTACT_DELETED: Byte = 0x8F.toByte()
    const val PUSH_CODE_CONTACTS_FULL: Byte = 0x90.toByte()

    // -------------------------------------------------------------------------
    // Misc constants
    // -------------------------------------------------------------------------
    const val ADV_TYPE_NONE = 0
    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3
    const val ADV_TYPE_SENSOR = 4

    const val TXT_TYPE_PLAIN = 0
    const val TXT_TYPE_CLI_DATA = 1
    const val TXT_TYPE_SIGNED_PLAIN = 2

    const val ERR_CODE_UNSUPPORTED_CMD = 1
    const val ERR_CODE_NOT_FOUND = 2
    const val ERR_CODE_TABLE_FULL = 3
    const val ERR_CODE_BAD_STATE = 4
    const val ERR_CODE_FILE_IO_ERROR = 5
    const val ERR_CODE_ILLEGAL_ARG = 6

    /** out_path_len value meaning "no known route, send flood". */
    const val OUT_PATH_UNKNOWN = -1

    fun isPush(code: Byte): Boolean = (code.toInt() and 0x80) != 0

    // -------------------------------------------------------------------------
    // Frame builders
    // -------------------------------------------------------------------------

    fun buildSimpleCommand(cmd: Byte): ByteArray = byteArrayOf(cmd)

    /** CMD_DEVICE_QUERY: [22][app_target_ver] -> RESP_CODE_DEVICE_INFO */
    fun buildDeviceQueryFrame(version: Byte = APP_PROTOCOL_VERSION): ByteArray =
        byteArrayOf(CMD_DEVICE_QUERY, version)

    /**
     * CMD_APP_START: [1][7 reserved bytes][app name] -> RESP_CODE_SELF_INFO.
     * The firmware ignores the command entirely when it is shorter than 8 bytes.
     */
    fun buildAppStartFrame(appName: String = APP_NAME): ByteArray {
        val frame = ByteArray(8)
        frame[0] = CMD_APP_START
        frame[1] = APP_PROTOCOL_VERSION
        return frame + appName.toByteArray(Charsets.UTF_8)
    }

    /**
     * CMD_SEND_TXT_MSG:
     * [2][txt_type][attempt][timestamp u32][pub_key prefix (6)][text] -> RESP_CODE_SENT / ERR
     */
    fun buildSendTextMsgFrame(
        recipientPubKey: ByteArray,
        message: String,
        timestampSecs: Long,
        attempt: Int = 0,
        txtType: Int = TXT_TYPE_PLAIN
    ): ByteArray {
        require(recipientPubKey.size >= PUB_KEY_PREFIX_SIZE) { "Public key too short" }
        val text = truncateUtf8(message, MAX_TEXT_BYTES)
        val buf = ByteBuffer.allocate(1 + 1 + 1 + 4 + PUB_KEY_PREFIX_SIZE + text.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_SEND_TXT_MSG)
        buf.put(txtType.toByte())
        buf.put(attempt.coerceIn(0, 3).toByte())
        buf.putInt(timestampSecs.toInt())
        buf.put(recipientPubKey, 0, PUB_KEY_PREFIX_SIZE)
        buf.put(text)
        return buf.array()
    }

    /**
     * CMD_SEND_CHANNEL_TXT_MSG:
     * [3][txt_type][channel_idx][timestamp u32][text] -> RESP_CODE_OK / ERR
     */
    fun buildSendChannelMsgFrame(channelIdx: Int, message: String, timestampSecs: Long): ByteArray {
        val text = truncateUtf8(message, MAX_TEXT_BYTES)
        val buf = ByteBuffer.allocate(1 + 1 + 1 + 4 + text.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_SEND_CHANNEL_TXT_MSG)
        buf.put(TXT_TYPE_PLAIN.toByte())
        buf.put(channelIdx.toByte())
        buf.putInt(timestampSecs.toInt())
        buf.put(text)
        return buf.array()
    }

    /** CMD_GET_CONTACTS [4][since u32 optional] */
    fun buildGetContactsFrame(since: Long = 0L): ByteArray =
        if (since <= 0L) byteArrayOf(CMD_GET_CONTACTS)
        else byteArrayOf(CMD_GET_CONTACTS) + leInt(since.toInt())

    /** CMD_SET_DEVICE_TIME [6][epoch secs u32] */
    fun buildSetDeviceTimeFrame(epochSecs: Long): ByteArray =
        byteArrayOf(CMD_SET_DEVICE_TIME) + leInt(epochSecs.toInt())

    /** CMD_SEND_SELF_ADVERT [7][1 = flood, 0 = zero hop] */
    fun buildSelfAdvertFrame(flood: Boolean): ByteArray =
        byteArrayOf(CMD_SEND_SELF_ADVERT, if (flood) 1 else 0)

    /** CMD_GET_CHANNEL [31][idx] -> RESP_CODE_CHANNEL_INFO / ERR */
    fun buildGetChannelFrame(channelIdx: Int): ByteArray =
        byteArrayOf(CMD_GET_CHANNEL, channelIdx.toByte())

    /** CMD_RESET_PATH [13][pub_key 32] */
    fun buildResetPathFrame(pubKey: ByteArray): ByteArray {
        require(pubKey.size == PUB_KEY_SIZE)
        return byteArrayOf(CMD_RESET_PATH) + pubKey
    }

    /** Truncates a string to at most [maxBytes] UTF-8 bytes without splitting a code point. */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        // step back over UTF-8 continuation bytes (10xxxxxx)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end)
    }

    private fun leInt(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = if (length % 2 == 0) this else "0$this"
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
