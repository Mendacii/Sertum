package com.sertum.player.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        CoverEntity::class,
        PlaybackPositionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class SertumDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_positions` (" +
                        "`trackId` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`trackId`))",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE albums ADD COLUMN embeddedCoverPath TEXT")
                db.execSQL("ALTER TABLE albums ADD COLUMN folderCoverPath TEXT")
            }
        }

        /**
         * Adds the artist's chosen representative cover.
         *
         * Nullable with no default, so existing rows keep null and fall back to the first
         * album that has a cover - no data is invented for a choice nobody has made.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artists ADD COLUMN imageAlbumKey TEXT")
            }
        }
    }
}
