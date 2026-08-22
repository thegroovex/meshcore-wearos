package com.meshcore.wearos

import com.meshcore.wearos.ble.FrameParser
import com.meshcore.wearos.ble.MeshCoreProtocol
import com.meshcore.wearos.ble.ParsedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the MeshCore BLE frame parser.
 *
 * These tests verify that raw BLE frames are correctly decoded into
 * typed ParsedFrame objects according to the Companion Radio Protocol spec.
 */
class FrameParserTest {

    @Test
    fun `parse RESP_CODE_OK returns ParsedFrame_Ok`() {
        val frame = byteArrayOf(MeshCoreProtocol.RESP_CODE_OK)
        val result = FrameParser.parse(frame)
        assertTrue("Expected ParsedFrame.Ok", result is ParsedFrame.Ok)
    }

    @Test
    fun `parse RESP_CODE_ERR returns ParsedFrame_Err with error code`() {
        val frame = byteArrayOf(MeshCoreProtocol.RESP_CODE_ERR, 2) // ERR_CODE_NOT_FOUND
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.Err)
        assertEquals(2, (result as ParsedFrame.Err).errorCode)
    }

    @Test
    fun `parse RESP_CODE_CONTACTS_START returns ParsedFrame_ContactsStart`() {
        val frame = byteArrayOf(MeshCoreProtocol.RESP_CODE_CONTACTS_START)
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.ContactsStart)
    }

    @Test
    fun `parse RESP_CODE_END_OF_CONTACTS returns ParsedFrame_EndOfContacts`() {
        val frame = byteArrayOf(MeshCoreProtocol.RESP_CODE_END_OF_CONTACTS)
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.EndOfContacts)
    }

    @Test
    fun `parse PUSH_CODE_MSG_WAITING returns ParsedFrame_MessageWaiting`() {
        val frame = byteArrayOf(MeshCoreProtocol.PUSH_CODE_MSG_WAITING)
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.MessageWaiting)
    }

    @Test
    fun `parse RESP_CODE_NO_MORE_MESSAGES returns ParsedFrame_NoMoreMessages`() {
        val frame = byteArrayOf(MeshCoreProtocol.RESP_CODE_NO_MORE_MESSAGES)
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.NoMoreMessages)
    }

    @Test
    fun `parse RESP_CODE_SENT returns ParsedFrame_Sent with ack code`() {
        // Build a SENT frame with ACK code 0x12345678 (little-endian)
        val frame = byteArrayOf(
            MeshCoreProtocol.RESP_CODE_SENT,
            0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte()
        )
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.Sent)
        assertEquals(0x12345678L, (result as ParsedFrame.Sent).expectedAckCode)
    }

    @Test
    fun `parse RESP_CODE_CURR_TIME returns ParsedFrame_CurrentTime`() {
        // Epoch 1700000000 = 0x6553F100 (little-endian: 00 F1 53 65)
        val frame = byteArrayOf(
            MeshCoreProtocol.RESP_CODE_CURR_TIME,
            0x00.toByte(), 0xF1.toByte(), 0x53.toByte(), 0x65.toByte()
        )
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.CurrentTime)
        assertEquals(1700000000L, (result as ParsedFrame.CurrentTime).epochSecs)
    }

    @Test
    fun `parse PUSH_CODE_SEND_CONFIRMED returns ParsedFrame_SendConfirmed`() {
        // reserved(1) + ackCode(4) + roundTripMs(4)
        val frame = byteArrayOf(
            MeshCoreProtocol.PUSH_CODE_SEND_CONFIRMED,
            0x00.toByte(), // reserved
            0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte(), // ack code
            0xE8.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte()  // 1000ms RTT
        )
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.SendConfirmed)
        val confirmed = result as ParsedFrame.SendConfirmed
        assertEquals(0x12345678L, confirmed.ackCode)
        assertEquals(1000L, confirmed.roundTripMs)
    }

    @Test
    fun `parse RESP_CODE_BATT_AND_STORAGE returns correct values`() {
        // battMillivolts=3700 (0x0E74), usedStorage=100 (0x0064), totalStorage=512 (0x0200)
        val frame = byteArrayOf(
            MeshCoreProtocol.RESP_CODE_BATT_AND_STORAGE,
            0x74.toByte(), 0x0E.toByte(), // 3700 mV
            0x64.toByte(), 0x00.toByte(), // 100 KB used
            0x00.toByte(), 0x02.toByte()  // 512 KB total
        )
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.BatteryAndStorage)
        val batt = result as ParsedFrame.BatteryAndStorage
        assertEquals(3700, batt.batteryMillivolts)
        assertEquals(100, batt.usedStorage)
        assertEquals(512, batt.totalStorage)
    }

    @Test
    fun `parse RESP_CODE_DEVICE_INFO returns ParsedFrame_DeviceInfoFrame`() {
        // Build a dummy device info frame (32 bytes pubkey + type + name)
        val frame = ByteArray(35)
        frame[0] = MeshCoreProtocol.RESP_CODE_DEVICE_INFO
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.DeviceInfoFrame)
    }

    @Test
    fun `parse empty frame returns ParsedFrame_Unknown`() {
        val frame = ByteArray(0)
        val result = FrameParser.parse(frame)
        assertTrue(result is ParsedFrame.Unknown)
    }

    @Test
    fun `buildSimpleCommand creates single-byte frame`() {
        val frame = MeshCoreProtocol.buildSimpleCommand(MeshCoreProtocol.CMD_GET_BATT_AND_STORAGE)
        assertEquals(1, frame.size)
        assertEquals(MeshCoreProtocol.CMD_GET_BATT_AND_STORAGE, frame[0])
    }

    @Test
    fun `buildAppStartFrame includes client version`() {
        val frame = MeshCoreProtocol.buildAppStartFrame()
        assertEquals(3, frame.size)
        assertEquals(MeshCoreProtocol.CMD_APP_START, frame[0])
        assertEquals(MeshCoreProtocol.CLIENT_VERSION.toByte(), frame[1])
    }

    @Test
    fun `buildSendTextMsgFrame has correct structure`() {
        val pubKey = ByteArray(32) { it.toByte() }
        val message = "Hello, MeshCore!"
        val frame = MeshCoreProtocol.buildSendTextMsgFrame(pubKey, message)

        assertEquals(1 + 32 + 1 + message.toByteArray().size, frame.size)
        assertEquals(MeshCoreProtocol.CMD_SEND_TXT_MSG, frame[0])
        assertEquals(message, frame.copyOfRange(34, frame.size).toString(Charsets.UTF_8))
    }
}
