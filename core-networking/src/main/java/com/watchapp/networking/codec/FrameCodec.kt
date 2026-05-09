package com.watchapp.networking.codec

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * FCAF v2 wire framing.
 *
 * Layout (big-endian):
 * ```
 * [0xFC][0xAF][uint16 length][JSON payload UTF-8]
 * ```
 * `length` is the byte length of the JSON payload only; the 4-byte header is not
 * included. One frame per message — there is no fragmentation. The 16-bit
 * length caps a single payload at [MAX_PAYLOAD_BYTES] (65 535) bytes.
 *
 * All methods are pure and have no Android dependencies; safe to unit test on JVM.
 */
object FrameCodec {

    /** First byte of the FCAF v2 magic prefix. */
    const val MAGIC_0: Byte = 0xFC.toByte()

    /** Second byte of the FCAF v2 magic prefix. */
    const val MAGIC_1: Byte = 0xAF.toByte()

    /** Fixed header size: 2 magic bytes + 2 length bytes. */
    const val HEADER_SIZE: Int = 4

    /** Maximum payload size that can be expressed in a uint16 length field. */
    const val MAX_PAYLOAD_BYTES: Int = 0xFFFF

    /**
     * Wraps a UTF-8-encoded JSON [payload] in a FCAF v2 frame.
     *
     * @throws IllegalArgumentException if [payload] exceeds [MAX_PAYLOAD_BYTES].
     */
    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "FCAF v2 payload exceeds $MAX_PAYLOAD_BYTES bytes (was ${payload.size})"
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    /**
     * Convenience overload — encodes [json] as UTF-8 and frames it.
     *
     * @throws IllegalArgumentException if the UTF-8 encoding exceeds [MAX_PAYLOAD_BYTES].
     */
    fun encode(json: String): ByteArray = encode(json.toByteArray(Charsets.UTF_8))

    /**
     * Reads exactly one FCAF v2 frame from [input] and returns the JSON payload as a UTF-8 string.
     * Blocks until the full frame is read.
     *
     * @throws MalformedFrameException if the leading two bytes are not the FCAF v2 magic.
     * @throws EOFException if the stream ends before the full frame is read.
     * @throws IOException if [input] errors.
     */
    @Throws(IOException::class)
    fun decode(input: InputStream): String {
        val header = readFully(input, HEADER_SIZE)
        if (header[0] != MAGIC_0 || header[1] != MAGIC_1) {
            throw MalformedFrameException(
                "Bad FCAF v2 magic: %02X%02X".format(
                    header[0].toInt() and 0xFF,
                    header[1].toInt() and 0xFF,
                )
            )
        }
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = readFully(input, length)
        return String(payload, Charsets.UTF_8)
    }

    /**
     * Reads exactly [n] bytes from [input], looping until the buffer is full.
     * Tolerates short reads from `InputStream.read`, which is required for sockets.
     */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException("EOF after $read of $n bytes")
            read += r
        }
        return buf
    }
}

/** Thrown by [FrameCodec.decode] when the leading bytes do not match `0xFC 0xAF`. */
class MalformedFrameException(message: String) : IOException(message)
