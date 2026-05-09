package com.watchapp.networking.buffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the offline frame queue. Single-table.
 *
 * App-shell constructs the database (it owns Android lifecycle) via [create] and
 * injects the resulting [PendingFrameDao] into [FrameBuffer]. Core-networking
 * never touches `Context` outside this companion object.
 */
@Database(
    entities = [PendingFrame::class],
    version = 1,
    exportSchema = false,
)
abstract class PendingFrameDatabase : RoomDatabase() {

    /** Single DAO for the `pending_frames` table. */
    abstract fun pendingFrameDao(): PendingFrameDao

    companion object {
        /** Default file name for the on-disk SQLite database. */
        const val DATABASE_NAME: String = "pending_frames.db"

        /**
         * Builds the Room database with the standard configuration. App-shell calls
         * this from its DI container; tests should bypass it and mock [PendingFrameDao].
         */
        fun create(context: Context): PendingFrameDatabase = Room.databaseBuilder(
            context.applicationContext,
            PendingFrameDatabase::class.java,
            DATABASE_NAME,
        ).build()
    }
}
