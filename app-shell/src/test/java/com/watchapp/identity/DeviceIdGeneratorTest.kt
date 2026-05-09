package com.watchapp.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DeviceIdGeneratorTest {

    private class InMemoryStorage : DeviceIdGenerator.ImeiStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(imei: String) {
            value = imei
        }
    }

    @Test
    fun `getOrCreate produces a 15-digit numeric value starting with 9`() {
        val storage = InMemoryStorage()
        val imei = DeviceIdGenerator(storage, Random(seed = 42)).getOrCreate()

        assertEquals(15, imei.length)
        assertEquals('9', imei[0])
        assertTrue("imei must be all digits, was '$imei'", imei.all { it.isDigit() })
    }

    @Test
    fun `generated IMEI passes the Luhn check`() {
        val imei = DeviceIdGenerator(InMemoryStorage(), Random(seed = 1)).getOrCreate()

        val prefix = imei.substring(0, 14)
        val expectedCheck = DeviceIdGenerator.luhnCheckDigit(prefix)
        assertEquals(expectedCheck.toString()[0], imei[14])
    }

    @Test
    fun `generation persists the IMEI on first call`() {
        val storage = InMemoryStorage()
        assertNull(storage.value)

        val imei = DeviceIdGenerator(storage, Random(seed = 0)).getOrCreate()

        assertNotNull(storage.value)
        assertEquals(imei, storage.value)
    }

    @Test
    fun `subsequent calls return the persisted IMEI without regenerating`() {
        val storage = InMemoryStorage()
        val gen = DeviceIdGenerator(storage, Random(seed = 0))

        val first = gen.getOrCreate()
        val second = gen.getOrCreate()
        val third = gen.getOrCreate()

        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `a fresh instance over the same storage returns the previously persisted IMEI`() {
        val storage = InMemoryStorage()
        val first = DeviceIdGenerator(storage, Random(seed = 7)).getOrCreate()

        // Simulates an app restart: new generator, different RNG seed, same storage.
        val second = DeviceIdGenerator(storage, Random(seed = 999)).getOrCreate()

        assertEquals(first, second)
    }

    @Test
    fun `luhnCheckDigit matches the canonical Wikipedia example`() {
        // Standard Luhn example: "7992739871" -> check digit 3 (full number 79927398713).
        assertEquals(3, DeviceIdGenerator.luhnCheckDigit("7992739871"))
    }

    @Test
    fun `luhnCheckDigit for a 14-digit prefix produces a single digit`() {
        repeat(50) { seed ->
            val random = Random(seed)
            val prefix = buildString(14) {
                append('9')
                repeat(13) { append(random.nextInt(10)) }
            }
            val check = DeviceIdGenerator.luhnCheckDigit(prefix)
            assertTrue("check digit must be 0..9, was $check", check in 0..9)
        }
    }

    @Test
    fun `different seeds produce different IMEIs (sanity)`() {
        val a = DeviceIdGenerator(InMemoryStorage(), Random(seed = 1)).getOrCreate()
        val b = DeviceIdGenerator(InMemoryStorage(), Random(seed = 2)).getOrCreate()
        assertNotEquals(a, b)
    }

    private fun assertNotEquals(a: Any?, b: Any?) {
        if (a == b) throw AssertionError("expected different values, both were $a")
    }
}
