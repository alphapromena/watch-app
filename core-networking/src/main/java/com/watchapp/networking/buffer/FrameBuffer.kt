package com.watchapp.networking.buffer

/**
 * FIFO offline frame queue with a hard row cap.
 *
 * - `enqueue` appends a wire-ready frame to the tail. If the cap is exceeded after
 *   insertion, the oldest rows are dropped to bring the size back to [maxRows].
 *   Per CONTRACTS.md and the stream brief, dropping the *oldest* (rather than
 *   refusing the new write) gives us a sliding window of recent telemetry, which
 *   is what the server prefers to see when a watch comes back online after a
 *   prolonged outage.
 * - `peekOldest` returns up to [batchSize] frames in insertion order — the
 *   streamer drains, writes each to the socket, and calls [delete] only after the
 *   write succeeds. A mid-batch failure leaves the surviving frames intact for
 *   the next reconnect cycle.
 *
 * Persistence is handled by Room; this class is the policy layer on top of
 * [PendingFrameDao] and is unit-testable with a mocked DAO.
 *
 * @param dao backing Room DAO, supplied by app-shell.
 * @param maxRows hard cap on the queue size (default 10 000 per the brief).
 * @param clock injected wall-clock millis for the `created_at` column.
 */
class FrameBuffer(
    private val dao: PendingFrameDao,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Persists [frameBytes] at the tail of the queue and trims to [maxRows] if needed. */
    suspend fun enqueue(frameBytes: ByteArray) {
        dao.insert(PendingFrame(bytes = frameBytes, createdAt = clock()))
        val overflow = dao.count() - maxRows
        if (overflow > 0) {
            dao.deleteOldest(overflow)
        }
    }

    /**
     * Returns up to [batchSize] oldest frames without removing them. The caller
     * must call [delete] after each frame is successfully written to the socket.
     */
    suspend fun peekOldest(batchSize: Int = DEFAULT_DRAIN_BATCH): List<PendingFrame> =
        dao.peekOldest(batchSize)

    /** Removes a frame after it has been successfully transmitted. */
    suspend fun delete(frame: PendingFrame) {
        dao.deleteById(frame.id)
    }

    /** Current number of queued frames. */
    suspend fun size(): Int = dao.count()

    companion object {
        /** Hard row cap from CONTRACTS.md / the stream brief. */
        const val DEFAULT_MAX_ROWS: Int = 10_000

        /** Reasonable batch size for drain reads — keeps memory bounded. */
        const val DEFAULT_DRAIN_BATCH: Int = 64
    }
}
