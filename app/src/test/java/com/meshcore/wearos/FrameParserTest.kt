package com.meshcore.wearos

import com.meshcore.wearos.ble.FrameParser
import com.meshcore.wearos.ble.MeshCoreProtocol
import com.meshcore.wearos.ble.ParsedFrame
import com.meshcore.wearos.ble.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frames are built exactly the way the reference firmware writes them
 * (examples/companion_radio/MyMesh.cpp) so these tests pin the wire format.
 */
class FrameParserTest {

    private fun frame(size: Int = 256, block: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.block()
        return b.array().copyOf(b.position())
    }

    private fun ByteBuffer.fixedString(s: String, size: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        put(bytes.copyOf(size))
    }

    /** Public key deliberately full of 0x00/0x03/0x04 bytes that broke the old boundary guessing. */
    private val pubKey = ByteArray(32) { i -> listOf(0x00, 0x03, 0x04, 0x02, 0xAB)[i % 5].toByte() }

    private fun contactFrame(code: Byte, name: String, pathLen: Int = 2, lastMod: Int = 1_700_000_100) = frame {
        put(code)
        put(pubKey)
        put(MeshCoreProtocol.ADV_TYPE_CHAT.toByte())
        put(0) // flags
        put(pathLen.toByte())
        put(ByteArray(64))
        fixedString(name, 32)
        putInt(1_700_000_000)    // last advert
        putInt(51_034_567)       // lat * 1e6
        putInt(7_012_345)        // lon * 1e6
        putInt(lastMod)
    }

    @Test
    fun `ok and err`() {
        assertEquals(ParsedFrame.Ok, FrameParser.parse(byteArrayOf(0)))
        val err = FrameParser.parse(byteArrayOf(1, 2))
        assertEquals(ParsedFrame.Err(MeshCoreProtocol.ERR_CODE_NOT_FOUND), err)
        assertEquals(ParsedFrame.Err(0), FrameParser.parse(byteArrayOf(1)))
    }

    @Test
    fun `contact frame has full 148 byte layout`() {
        val raw = contactFrame(MeshCoreProtocol.RESP_CODE_CONTACT, "Bärbel")
        assertEquals(148, raw.size)
        val parsed = FrameParser.parse(raw) as ParsedFrame.ContactFrame
        val c = parsed.contact
        assertArrayEquals(pubKey, c.publicKey)
        assertEquals("Bärbel", c.name)
        assertEquals(1, c.type)
        assertEquals(2, c.outPathLen)
        assertEquals(1_700_000_000L, c.lastAdvertTimestamp)
        assertEquals(51.034567, c.latitude, 1e-9)
        assertEquals(7.012345, c.longitude, 1e-9)
        assertEquals(1_700_000_100L, c.lastModified)
        assertFalse("contact stream must not complete a pending command", parsed.isCommandResponse)
    }

    @Test
    fun `contact with unknown path reports -1`() {
        val c = (FrameParser.parse(contactFrame(MeshCoreProtocol.RESP_CODE_CONTACT, "X", pathLen = -1)) as ParsedFrame.ContactFrame).contact
        assertEquals(-1, c.outPathLen)
    }

    @Test
    fun `new advert push carries a contact`() {
        val p = FrameParser.parse(contactFrame(MeshCoreProtocol.PUSH_CODE_NEW_ADVERT, "Repeater 1"))
        assertTrue(p is ParsedFrame.NewAdvertPush)
        assertEquals("Repeater 1", (p as ParsedFrame.NewAdvertPush).contact.name)
        assertFalse(p.isCommandResponse)
    }

    @Test
    fun `contacts start and end`() {
        val start = FrameParser.parse(frame { put(2); putInt(42) })
        assertEquals(ParsedFrame.ContactsStart(42), start)
        assertTrue(start.isCommandResponse)
        val end = FrameParser.parse(frame { put(4); putInt(-1) })
        assertEquals(ParsedFrame.EndOfContacts(0xFFFFFFFFL), end)
        assertFalse(end.isCommandResponse)
    }

    @Test
    fun `self info`() {
        val raw = frame {
            put(MeshCoreProtocol.RESP_CODE_SELF_INFO)
            put(1); put(22); put(30)
            put(pubKey)
            putInt(51_000_000); putInt(-7_500_000)
            put(0); put(0); put(0); put(0)
            putInt(869_525)      // kHz
            putInt(250_000)      // Hz
            put(11); put(5)
            put("Watch Node".toByteArray())
        }
        val s = (FrameParser.parse(raw) as ParsedFrame.SelfInfo).info
        assertEquals("Watch Node", s.name)
        assertEquals(22, s.txPowerDbm)
        assertEquals(869_525L, s.radioFreqKhz)
        assertEquals(250_000L, s.radioBandwidthHz)
        assertEquals(11, s.spreadingFactor)
        assertEquals(-7.5, s.longitude, 1e-9)
        assertArrayEquals(pubKey, s.publicKey)
    }

    @Test
    fun `device info v3 plus`() {
        val raw = frame {
            put(MeshCoreProtocol.RESP_CODE_DEVICE_INFO)
            put(8); put(50); put(8)
            putInt(123456)
            fixedString("19 Sep 2025", 12)
            fixedString("Heltec V3", 40)
            fixedString("v1.9.0", 20)
            put(0); put(0)
        }
        val d = (FrameParser.parse(raw) as ParsedFrame.DeviceInfo).info
        assertEquals(8, d.firmwareVersionCode)
        assertEquals(100, d.maxContacts)
        assertEquals(8, d.maxChannels)
        assertEquals("Heltec V3", d.manufacturer)
        assertEquals("v1.9.0", d.firmwareVersion)
        assertEquals("19 Sep 2025", d.buildDate)
    }

    @Test
    fun `sent response`() {
        val raw = frame { put(6); put(1); putInt(0xDEADBEEF.toInt()); putInt(12_000) }
        val s = FrameParser.parse(raw) as ParsedFrame.Sent
        assertTrue(s.isFlood)
        assertEquals(0xDEADBEEFL, s.expectedAckCode)
        assertEquals(12_000L, s.estimatedTimeoutMs)
    }

    @Test
    fun `contact message v3`() {
        val raw = frame {
            put(MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3)
            put((-26).toByte()) // SNR * 4 = -6.5 dB
            put(0); put(0)
            put(pubKey, 0, 6)
            put(3) // path len
            put(MeshCoreProtocol.TXT_TYPE_PLAIN.toByte())
            putInt(1_700_000_500)
            put("Hallo Uhr 👋".toByteArray())
        }
        val m = (FrameParser.parse(raw) as ParsedFrame.ContactMessage).msg
        assertEquals(pubKey.copyOf(6).toHex(), m.senderPrefixHex)
        assertEquals(-6.5f, m.snr!!, 0.001f)
        assertEquals(3, m.pathLen)
        assertEquals(1_700_000_500L, m.senderTimestamp)
        assertEquals("Hallo Uhr 👋", m.text)
    }

    @Test
    fun `contact message legacy and signed`() {
        val raw = frame {
            put(MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV)
            put(pubKey, 0, 6)
            put(0xFF.toByte()) // direct
            put(MeshCoreProtocol.TXT_TYPE_SIGNED_PLAIN.toByte())
            putInt(5)
            put(byteArrayOf(9, 9, 9, 9)) // signer prefix
            put("signed".toByteArray())
        }
        val m = (FrameParser.parse(raw) as ParsedFrame.ContactMessage).msg
        assertNull(m.snr)
        assertEquals(-1, m.pathLen)
        assertEquals("signed", m.text)
    }

    @Test
    fun `channel message splits sender name`() {
        val raw = frame {
            put(MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV_V3)
            put(20); put(0); put(0)
            put(0) // channel 0
            put(1)
            put(0)
            putInt(1_700_000_600)
            put("Alice: hi: all".toByteArray())
        }
        val m = (FrameParser.parse(raw) as ParsedFrame.ChannelMessage).msg
        assertEquals(0, m.channelIndex)
        assertEquals("Alice", m.senderName)
        assertEquals("hi: all", m.text)
        assertEquals(5f, m.snr!!, 0.001f)
    }

    @Test
    fun `battery and storage`() {
        val b = FrameParser.parse(frame { put(12); putShort(4012); putInt(100); putInt(2000) }) as ParsedFrame.BatteryAndStorage
        assertEquals(4012, b.batteryMillivolts)
        assertEquals(100L, b.usedKb)
        assertEquals(2000L, b.totalKb)
        // older firmware only sends the voltage
        val old = FrameParser.parse(frame { put(12); putShort(3700) }) as ParsedFrame.BatteryAndStorage
        assertEquals(3700, old.batteryMillivolts)
    }

    @Test
    fun `current time`() {
        assertEquals(ParsedFrame.CurrentTime(1_700_000_000L), FrameParser.parse(frame { put(9); putInt(1_700_000_000) }))
    }

    @Test
    fun `channel info`() {
        val raw = frame { put(18); put(1); fixedString("Wandergruppe", 32); put(ByteArray(16) { 7 }) }
        val c = FrameParser.parse(raw) as ParsedFrame.ChannelInfo
        assertEquals(1, c.index)
        assertEquals("Wandergruppe", c.name)
        assertFalse(c.isEmpty)
        val empty = FrameParser.parse(frame { put(18); put(5); put(ByteArray(48)) }) as ParsedFrame.ChannelInfo
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `pushes are not command responses`() {
        val confirmed = FrameParser.parse(frame { put(0x82.toByte()); putInt(77); putInt(1500) })
        assertEquals(ParsedFrame.SendConfirmed(77, 1500), confirmed)
        assertFalse(confirmed.isCommandResponse)
        assertEquals(ParsedFrame.MessageWaiting, FrameParser.parse(byteArrayOf(0x83.toByte())))
        val advert = FrameParser.parse(frame { put(0x80.toByte()); put(pubKey) }) as ParsedFrame.AdvertPush
        assertArrayEquals(pubKey, advert.publicKey)
        assertTrue(FrameParser.parse(byteArrayOf(0x88.toByte(), 1, 2)) is ParsedFrame.OtherPush)
    }

    @Test
    fun `truncated frames do not throw and still answer the pending command`() {
        val p = FrameParser.parse(byteArrayOf(MeshCoreProtocol.RESP_CODE_SELF_INFO, 1, 2))
        assertTrue(p is ParsedFrame.Malformed)
        assertTrue(p.isCommandResponse)
        val c = FrameParser.parse(byteArrayOf(MeshCoreProtocol.RESP_CODE_CONTACT, 1, 2))
        assertTrue(c is ParsedFrame.Malformed)
        assertFalse(c.isCommandResponse)
        assertTrue(FrameParser.parse(ByteArray(0)) is ParsedFrame.Unknown)
    }
}
