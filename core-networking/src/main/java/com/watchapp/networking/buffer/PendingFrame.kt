package com.watchapp.networking.buffer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued FCAF v2 frame waiting to be flushed once the streamer reconnects.
 *
 * The `bytes` payload is the *fully-framed* output of `FrameCodec.encode(...)` —
 * including magic + length header — so the drain path can write it straight to
 * the socket with no further work.
 */
@Entity(tableName = "pending_frames")
class PendingFrame(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "bytes")
    val bytes: ByteArray,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
