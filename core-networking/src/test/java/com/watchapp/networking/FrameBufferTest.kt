package com.watchapp.networking

import com.watchapp.networking.buffer.FrameBuffer
import com.watchapp.networking.buffer.PendingFrame
import com.watchapp.networking.buffer.PendingFrameDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FrameBuffer unit tests over a mocked [PendingFrameDao] — Room's compiled DAO
 * needs SQLite, which we cannot run on plain JVM per the stream brief. The cap
 * logic is the only non-trivial piece, so mocking is sufficient.
 */
class FrameBufferTest {

    @Test
    fun `enqueue inserts and skips trimming when under cap`() = runTest {
        val dao = mockk<PendingFrameDao>(relaxed = true)
        coEvery { dao.count() } returns 5
        val buffer = FrameBuffer(dao = dao, maxRows = 10, clock = { 1_000L })

        buffer.enqueue(byteArrayOf(1, 2, 3))

        coVerify(exactly = 1) {
            dao.insert(match { it.bytes.contentEquals(byteArrayOf(1, 2, 3)) && it.createdAt == 1_000L })
        }
        coVerify(exactly = 0) { dao.deleteOldest(any()) }
    }

    @Test
    fun `enqueue trims oldest when count exceeds cap`() = runTest {
        val dao = mockk<PendingFrameDao>(relaxed = true)
        // After insertion the DAO reports 10_003 rows with cap=10_000 → drop 3.
        coEvery { dao.count() } returns 10_003
        val buffer = FrameBuffer(dao = dao, maxRows = 10_000, clock = { 0L })

        buffer.enqueue(byteArrayOf(0))

        coVerify(exactly = 1) { dao.deleteOldest(3) }
    }

    @Test
    fun `enqueue at exactly the cap does not trim`() = runTest {
        val dao = mockk<PendingFrameDao>(relaxed = true)
        coEvery { dao.count() } returns 10_000
        val buffer = FrameBuffer(dao = dao, maxRows = 10_000, clock = { 0L })

        buffer.enqueue(byteArrayOf(0))

        coVerify(exactly = 0) { dao.deleteOldest(any()) }
    }

    @Test
    fun `peekOldest delegates to dao with the requested batch size`() = runTest {
        val dao = mockk<PendingFrameDao>()
        val expected = listOf(PendingFrame(id = 1, bytes = byteArrayOf(7), createdAt = 100L))
        coEvery { dao.peekOldest(32) } returns expected
        val buffer = FrameBuffer(dao = dao)

        val actual = buffer.peekOldest(batchSize = 32)

        assertEquals(expected, actual)
    }

    @Test
    fun `delete removes the frame by id`() = runTest {
        val dao = mockk<PendingFrameDao>(relaxed = true)
        val buffer = FrameBuffer(dao = dao)
        val frame = PendingFrame(id = 42L, bytes = byteArrayOf(0), createdAt = 0L)

        buffer.delete(frame)

        coVerify(exactly = 1) { dao.deleteById(42L) }
    }

    @Test
    fun `size returns the dao count`() = runTest {
        val dao = mockk<PendingFrameDao>()
        coEvery { dao.count() } returns 7
        val buffer = FrameBuffer(dao = dao)

        assertEquals(7, buffer.size())
    }
}
