package com.meshcore.wearos.ble

/**
 * MeshCore Companion Radio Protocol constants.
 *
 * This file defines all command codes, response codes, and push notification codes
 * as specified in the MeshCore Companion Radio Protocol documentation.
 * See: https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol
 */
object MeshCoreProtocol {

    // BLE Nordic UART Service (NUS) UUIDs
    const val NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NUS_RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // Write (app -> radio)
    const val NUS_TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // Notify (radio -> app)

    // MeshCore device name prefix for BLE scan filter
    const val MESHCORE_DEVICE_NAME_PREFIX = "MeshCore"

    // Protocol version supported by this client
    const val CLIENT_VERSION = 3

    // -------------------------------------------------------------------------
    // App Commands / Requests (App -> Radio)
    // -------------------------------------------------------------------------

    /** First command after establishing connection. Responds with RESP_CODE_DEVICE_INFO(13) */
    const val CMD_DEVICE_QUERY: Byte = 22

    /** First request after connection. Responds with RESP_CODE_SELF_INFO(5) */
    const val CMD_APP_START: Byte = 1

    /** Send a direct message to a contact. Responds with RESP_CODE_SENT(6) or RESP_CODE_ERR(1) */
    const val CMD_SEND_TXT_MSG: Byte = 2

    /** Send a flood-mode message to a channel. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SEND_CHANNEL_TXT_MSG: Byte = 3

    /** Sync contacts list. Responds with RESP_CODE_CONTACTS_START, RESP_CODE_CONTACT..., RESP_CODE_END_OF_CONTACTS */
    const val CMD_GET_CONTACTS: Byte = 4 // v3 standard

    /** Get device clock. Responds with RESP_CODE_CURR_TIME(9) + 32-bit epoch secs */
    const val CMD_GET_DEVICE_TIME: Byte = 5

    /** Set device clock. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SET_DEVICE_TIME: Byte = 6

    /** Send advertisement packet. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SEND_SELF_ADVERT: Byte = 7

    /** Update node name. Responds with RESP_CODE_OK(0) */
    const val CMD_SET_ADVERT_NAME: Byte = 8

    /** Add or update a contact. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_ADD_UPDATE_CONTACT: Byte = 9

    /** Get next message from queue. Responds with message frame or RESP_CODE_NO_MORE_MESSAGES(10) */
    const val CMD_SYNC_NEXT_MESSAGE: Byte = 10

    /** Set radio parameters. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SET_RADIO_PARAMS: Byte = 11

    /** Set radio TX power. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SET_RADIO_TX_POWER: Byte = 12

    /** Reset contact path. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_RESET_PATH: Byte = 13

    /** Update node lat/lon. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SET_ADVERT_LATLON: Byte = 14

    /** Remove a contact. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_REMOVE_CONTACT: Byte = 15

    /** Share a contact via zero-hop advert. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SHARE_CONTACT: Byte = 16

    /** Export contact (business card). Responds with RESP_CODE_EXPORT_CONTACT(11) or RESP_CODE_ERR(1) */
    const val CMD_EXPORT_CONTACT: Byte = 17

    /** Import contact (business card). Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_IMPORT_CONTACT: Byte = 18

    /** Reboot the companion device. No response. */
    const val CMD_REBOOT: Byte = 19

    /** Get battery and storage stats. Responds with RESP_CODE_BATT_AND_STORAGE(12) */
    const val CMD_GET_BATT_AND_STORAGE: Byte = 20

    /** Set tuning parameters. Responds with RESP_CODE_OK(0) */
    const val CMD_SET_TUNING_PARAMS: Byte = 21

    /** Send device query. Responds with RESP_CODE_DEVICE_INFO(13) */
    const val CMD_DEVICE_QUERY_V2: Byte = 22

    /** Send raw data packet. Responds with RESP_CODE_OK(0) or RESP_CODE_ERR(1) */
    const val CMD_SEND_RAW_DATA: Byte = 25

    /** Send login request to repeater/room server. Responds with RESP_CODE_SENT(6) */
    const val CMD_SEND_LOGIN: Byte = 26

    /** Send status request to repeater/sensor. Responds with RESP_CODE_SENT(6) */
    const val CMD_SEND_STATUS_REQ: Byte = 27

    /** Set other parameters. Responds with RESP_CODE_OK(0) */
    const val CMD_SET_OTHER_PARAMS: Byte = 38

    /** Get all custom variables. Responds with RESP_CODE_CUSTOM_VARS(21) */
    const val CMD_GET_CUSTOM_VARS: Byte = 40

    /** Set a custom variable. Responds with RESP_CODE_OK(0) or error */
    const val CMD_SET_CUSTOM_VAR: Byte = 41

    /** Query last advert path for a contact. Responds with RESP_CODE_ADVERT_PATH(22) */
    const val CMD_GET_ADVERT_PATH: Byte = 42

    /** Get tuning parameters. Responds with RESP_CODE_TUNING_PARAMS */
    const val CMD_GET_TUNING_PARAMS: Byte = 43

    /** Send binary request to a node. Responds with RESP_CODE_SENT(6) */
    const val CMD_SEND_BINARY_REQ: Byte = 50

    /** Factory reset the device. No response. */
    const val CMD_FACTORY_RESET: Byte = 51

    /** Get device statistics. Responds with RESP_CODE_STATS(24) */
    const val CMD_GET_STATS: Byte = 56

    // -------------------------------------------------------------------------
    // Response Codes (Radio -> App)
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
    const val RESP_CODE_CONTACT_MSG_RECV_V3: Byte = 16
    const val RESP_CODE_CHANNEL_MSG_RECV_V3: Byte = 17

    // -------------------------------------------------------------------------
    // Push Notification Codes (Radio -> App, unsolicited)
    // -------------------------------------------------------------------------

    const val PUSH_CODE_ADVERT: Byte = 0x80.toByte()
    const val PUSH_CODE_PATH_UPDATED: Byte = 0x81.toByte()
    const val PUSH_CODE_SEND_CONFIRMED: Byte = 0x82.toByte()
    const val PUSH_CODE_MSG_WAITING: Byte = 0x83.toByte()
    const val PUSH_CODE_RAW_DATA: Byte = 0x84.toByte()
    const val PUSH_CODE_LOGIN_SUCCESS: Byte = 0x85.toByte()
    const val PUSH_CODE_LOGIN_FAIL: Byte = 0x86.toByte()
    const val PUSH_CODE_STATUS_RESPONSE: Byte = 0x87.toByte()
    const val PUSH_CODE_TRACE_DATA: Byte = 0x89.toByte()
    const val PUSH_CODE_NEW_ADVERT: Byte = 0x8A.toByte()
    const val PUSH_CODE_TELEMETRY_RESPONSE: Byte = 0x8B.toByte()
    const val PUSH_CODE_BINARY_RESPONSE: Byte = 0x8C.toByte()
    const val PUSH_CODE_CONTROL_DATA: Byte = 0x8E.toByte()

    // -------------------------------------------------------------------------
    // Advertisement Types
    // -------------------------------------------------------------------------

    const val ADV_TYPE_NONE: Int = 0
    const val ADV_TYPE_CHAT: Int = 1
    const val ADV_TYPE_REPEATER: Int = 2
    const val ADV_TYPE_ROOM: Int = 3

    // -------------------------------------------------------------------------
    // Text Message Types
    // -------------------------------------------------------------------------

    const val TXT_TYPE_PLAIN: Int = 0
    const val TXT_TYPE_CLI_DATA: Int = 1
    const val TXT_TYPE_SIGNED_PLAIN: Int = 2

    // -------------------------------------------------------------------------
    // Error Codes
    // -------------------------------------------------------------------------

    const val ERR_CODE_UNSUPPORTED_CMD: Int = 1
    const val ERR_CODE_NOT_FOUND: Int = 2
    const val ERR_CODE_TABLE_FULL: Int = 3
    const val ERR_CODE_BAD_STATE: Int = 4
    const val ERR_CODE_FILE_IO_ERROR: Int = 5
    const val ERR_CODE_ILLEGAL_ARG: Int = 6

    // -------------------------------------------------------------------------
    // Frame building helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a simple command frame with just the command code byte.
     */
    fun buildSimpleCommand(cmd: Byte): ByteArray = byteArrayOf(cmd)

    /**
     * Builds a command frame with the command code followed by a payload.
     */
    fun buildCommandFrame(cmd: Byte, payload: ByteArray): ByteArray {
        return byteArrayOf(cmd) + payload
    }

    /**
     * Builds a CMD_APP_START frame with the client version encoded.
     * The version is encoded as a 2-byte little-endian value.
     */
    fun buildAppStartFrame(): ByteArray {
        return byteArrayOf(
            CMD_APP_START,
            (CLIENT_VERSION and 0xFF).toByte(),
            ((CLIENT_VERSION shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Builds a CMD_SEND_TXT_MSG frame.
     *
     * @param recipientPubKey 32-byte public key of the recipient
     * @param message         UTF-8 encoded message text
     * @param txtType         Message type (TXT_TYPE_PLAIN, etc.)
     */
    fun buildSendTextMsgFrame(
        recipientPubKey: ByteArray,
        message: String,
        txtType: Int = TXT_TYPE_PLAIN
    ): ByteArray {
        require(recipientPubKey.size == 32) { "Public key must be 32 bytes" }
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        return byteArrayOf(CMD_SEND_TXT_MSG) +
                recipientPubKey +
                byteArrayOf(txtType.toByte()) +
                msgBytes
    }

    /**
     * Builds a CMD_SEND_CHANNEL_TXT_MSG frame.
     *
     * @param channelIdx Channel index (0-based)
     * @param message    UTF-8 encoded message text
     */
    fun buildSendChannelMsgFrame(channelIdx: Int, message: String): ByteArray {
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        return byteArrayOf(CMD_SEND_CHANNEL_TXT_MSG, channelIdx.toByte()) + msgBytes
    }

    /**
     * Builds a CMD_GET_CONTACTS frame with an optional 'since' timestamp.
     *
     * @param since Unix epoch seconds; if 0, all contacts are returned
     */
    fun buildGetContactsFrame(since: Long = 0L): ByteArray {
        return if (since == 0L) {
            byteArrayOf(CMD_GET_CONTACTS)
        } else {
            byteArrayOf(CMD_GET_CONTACTS) + since.toInt().toLeBytes()
        }
    }

    /**
     * Builds a CMD_SET_DEVICE_TIME frame with the current Unix epoch time.
     */
    fun buildSetDeviceTimeFrame(epochSecs: Long): ByteArray {
        return byteArrayOf(CMD_SET_DEVICE_TIME) + epochSecs.toInt().toLeBytes()
    }

    /**
     * Encodes a 32-bit integer as 4 little-endian bytes.
     */
    private fun Int.toLeBytes(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )
}
