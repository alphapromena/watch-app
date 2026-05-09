package com.watchapp.networking

import com.watchapp.networking.codec.FrameCodec
import com.watchapp.networking.codec.MalformedFrameException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class FrameCodecTest {

    @Test
    fun `encode prepends magic and big-endian length`() {
        val frame = FrameCodec.encode("AB")
        // 0xFC 0xAF, length=2 (0x0002), payload "AB" = 0x41 0x42
        assertArrayEquals(byteArrayOf(0xFC.toByte(), 0xAF.toByte(), 0x00, 0x02, 0x41, 0x42), frame)
    }

    @Test
    fun `encode of empty payload yields just the header`() {
        val frame = FrameCodec.encode("")
        assertArrayEquals(byteArrayOf(0xFC.toByte(), 0xAF.toByte(), 0x00, 0x00), frame)
    }

    @Test
    fun `length field is big-endian for a multi-byte length`() {
        // payload of 0x1234 = 4660 bytes, all 'X' (0x58)
        val payload = ByteArray(0x1234) { 0x58 }
        val frame = FrameCodec.encode(payload)
        assertEquals(0xFC.toByte(), frame[0])
        assertEquals(0xAF.toByte(), frame[1])
        assertEquals(0x12.toByte(), frame[2])
        assertEquals(0x34.toByte(), frame[3])
        assertEquals(FrameCodec.HEADER_SIZE + 0x1234, frame.size)
    }

    @Test
    fun `encode at maximum payload size succeeds`() {
        val payload = ByteArray(FrameCodec.MAX_PAYLOAD_BYTES) { 0x00 }
        val frame = FrameCodec.encode(payload)
        assertEquals(FrameCodec.HEADER_SIZE + FrameCodec.MAX_PAYLOAD_BYTES, frame.size)
        assertEquals(0xFF.toByte(), frame[2])
        assertEquals(0xFF.toByte(), frame[3])
    }

    @Test
    fun `encode rejects payload over the uint16 limit`() {
        val tooBig = ByteArray(FrameCodec.MAX_PAYLOAD_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(tooBig) }
    }

    @Test
    fun `encode then decode round-trips ASCII JSON`() {
        val json = """{"type":"upHeartbeat","imei":"900000000000123","ref":"w:hb"}"""
        val frame = FrameCodec.encode(json)
        val decoded = FrameCodec.decode(ByteArrayInputStream(frame))
        assertEquals(json, decoded)
    }

    @Test
    fun `encode then decode round-trips multi-byte UTF-8`() {
        val json = """{"city":"東京","note":"тест 🚀"}"""
        val frame = FrameCodec.encode(json)
        val decoded = FrameCodec.decode(ByteArrayInputStream(frame))
        assertEquals(json, decoded)
    }

    @Test
    fun `decode tolerates a stream that returns one byte at a time`() {
        val json = """{"hello":"world"}"""
        val frame = FrameCodec.encode(json)
        val decoded = FrameCodec.decode(OneByteAtATimeStream(frame))
        assertEquals(json, decoded)
    }

    @Test
    fun `decode throws MalformedFrameException on bad magic`() {
        val notAFrame = byteArrayOf(0x00, 0x00, 0x00, 0x05, 'h'.code.toByte())
        assertThrows(MalformedFrameException::class.java) {
            FrameCodec.decode(ByteArrayInputStream(notAFrame))
        }
    }

    @Test
    fun `decode throws EOFException when header is truncated`() {
        val truncated = byteArrayOf(0xFC.toByte(), 0xAF.toByte(), 0x00) // missing low length byte
        assertThrows(EOFException::class.java) {
            FrameCodec.decode(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `decode throws EOFException when payload is truncated`() {
        // header advertises 5 bytes, only 2 follow
        val truncated = byteArrayOf(
            0xFC.toByte(), 0xAF.toByte(), 0x00, 0x05,
            'h'.code.toByte(), 'i'.code.toByte()
        )
        assertThrows(EOFException::class.java) {
            FrameCodec.decode(ByteArrayInputStream(truncated))
        }
    }

    /** Wraps an [InputStream] and forces single-byte reads to model TCP fragmentation. */
    private class OneByteAtATimeStream(bytes: ByteArray) : InputStream() {
        private val backing = ByteArrayInputStream(bytes)
        override fun read(): Int = backing.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val one = backing.read()
            if (one < 0) return -1
            b[off] = one.toByte()
            return 1
        }
    }
}
