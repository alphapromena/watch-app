package com.watchapp.networking.buffer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Room DAO for [PendingFrame]. All operations FIFO ordered by `id`. */
@Dao
interface PendingFrameDao {

    /** Inserts a frame at the tail of the queue. Returns the generated row id. */
    @Insert
    suspend fun insert(frame: PendingFrame): Long

    /** Returns up to [limit] oldest frames (FIFO). */
    @Query("SELECT * FROM pending_frames ORDER BY id ASC LIMIT :limit")
    suspend fun peekOldest(limit: Int): List<PendingFrame>

    /** Removes a single frame by primary key after it has been successfully sent. */
    @Query("DELETE FROM pending_frames WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Total number of queued frames. */
    @Query("SELECT COUNT(*) FROM pending_frames")
    suspend fun count(): Int

    /** Drops the [n] oldest frames; used to enforce the row cap. */
    @Query(
        "DELETE FROM pending_frames WHERE id IN " +
            "(SELECT id FROM pending_frames ORDER BY id ASC LIMIT :n)"
    )
    suspend fun deleteOldest(n: Int)
}
