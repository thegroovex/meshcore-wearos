package com.meshcore.wearos

import com.meshcore.wearos.ble.MeshCoreProtocol
import com.meshcore.wearos.ble.hexToBytes
import com.meshcore.wearos.ble.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Command frames must match what MyMesh::handleCmdFrame() expects byte by byte. */
class MeshCoreProtocolTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `device query carries protocol version`() {
        assertArrayEquals(byteArrayOf(22, 3), MeshCoreProtocol.buildDeviceQueryFrame())
    }

    @Test
    fun `app start is at least 8 bytes with app name at offset 8`() {
        val f = MeshCoreProtocol.buildAppStartFrame("Test")
        assertEquals(MeshCoreProtocol.CMD_APP_START, f[0])
        assertTrue("firmware ignores APP_START shorter than 8 bytes", f.size >= 8)
        assertEquals("Test", String(f, 8, f.size - 8))
    }

    @Test
    fun `send text message layout`() {
        val f = MeshCoreProtocol.buildSendTextMsgFrame(key, "hi", timestampSecs = 0x01020304, attempt = 1)
        assertEquals("02" + "00" + "01" + "04030201" + "000102030405" + "6869", f.toHex())
        assertTrue("firmware requires len >= 14", f.size >= 14)
    }

    @Test
    fun `send channel message layout`() {
        val f = MeshCoreProtocol.buildSendChannelMsgFrame(2, "yo", 0x0A0B0C0D)
        assertEquals("03" + "00" + "02" + "0d0c0b0a" + "796f", f.toHex())
    }

    @Test
    fun `get contacts with and without since`() {
        assertArrayEquals(byteArrayOf(4), MeshCoreProtocol.buildGetContactsFrame())
        assertEquals("0410000000", MeshCoreProtocol.buildGetContactsFrame(16).toHex())
    }

    @Test
    fun `set time and advert and channel`() {
        assertEquals("0600f15365", MeshCoreProtocol.buildSetDeviceTimeFrame(1_700_000_000).toHex())
        assertEquals("0701", MeshCoreProtocol.buildSelfAdvertFrame(true).toHex())
        assertEquals("0700", MeshCoreProtocol.buildSelfAdvertFrame(false).toHex())
        assertEquals("1f03", MeshCoreProtocol.buildGetChannelFrame(3).toHex())
    }

    @Test
    fun `long text is truncated on a utf8 boundary`() {
        val text = "ä".repeat(100) // 200 bytes
        val f = MeshCoreProtocol.buildSendTextMsgFrame(key, text, 0)
        val payload = f.copyOfRange(13, f.size)
        assertTrue(payload.size <= MeshCoreProtocol.MAX_TEXT_BYTES)
        assertEquals(0, payload.size % 2)
        assertEquals("ä".repeat(payload.size / 2), String(payload, Charsets.UTF_8))
        assertTrue(f.size <= MeshCoreProtocol.MAX_FRAME_SIZE)
    }

    @Test
    fun `hex round trip`() {
        assertArrayEquals(key, key.toHex().hexToBytes())
    }

    @Test
    fun `push detection`() {
        assertTrue(MeshCoreProtocol.isPush(MeshCoreProtocol.PUSH_CODE_MSG_WAITING))
        assertTrue(!MeshCoreProtocol.isPush(MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3))
    }
}
