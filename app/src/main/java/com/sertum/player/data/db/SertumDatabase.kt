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
    ],
    version = 8,
    exportSchema = true,
)
abstract class SertumDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        /**
         * Kept as history rather than deleted, even though 6 -> 7 drops what it creates.
         *
         * A migration chain has to describe how a database got here, and an install that has not
         * been opened since schema 1 still has to run this before it can run the rest. Removing
         * it would break that path for no gain; the table is simply created and later dropped.
         */
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

        /**
         * Adds the disc ordering key and the album's container label.
         *
         * Both nullable and left null for existing rows. Their values are filled by the next
         * scan rather than by the migration, because they come from reading each file, which
         * a migration cannot do - and a scan rewrites every track and album anyway. Until
         * then the columns are simply absent, which is inert: the album ordering falls back
         * to the track number exactly as it did before, and the quality label shows nothing.
         *
         * The track's `sampleRate` and `bitDepth` columns already exist; they were left null
         * by the scanner and start being populated with the same scan.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN discBand TEXT")
                db.execSQL("ALTER TABLE albums ADD COLUMN format TEXT")
            }
        }

        /**
         * Adds the span a track occupies inside its file, for a single-file disc rip.
         *
         * Nullable and left null, which is the correct value for every existing row: an
         * ordinary track is the whole file. The columns are populated by the next scan, which
         * is also what creates the carved tracks in the first place - the migration cannot,
         * because the boundaries come from reading each CUE sheet.
         *
         * A track with `startMs` null plays from the beginning to the end of its file, which is
         * what every track did before this existed, so nothing changes until the scan.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN startMs INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN endMs INTEGER")
            }
        }

        /**
         * Drops the per-track resume position, at the user's request.
         *
         * Resume was removed from playback on 2026-10-02 - every tap was starting mid-track
         * after a track had been played to the end, and the request was that a tap always
         * begins at the beginning. The store, the entity and the DAO methods were left in
         * place at the time, so the feature kept its shape in the code and its rows in the
         * database: 145 of them, the newest written the day it was disabled, plus the tests
         * that kept the store green and made a removed feature look present.
         *
         * Dropping the table is the point of this migration rather than a side effect. The
         * data is not recoverable and is not meant to be: it records where somebody stopped
         * listening in a build that no longer reads it.
         *
         * The PlaybackPositions table has a foreign key to nothing, so no table rebuild is
         * needed - dropping it cannot orphan a row elsewhere.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `playback_positions`")
            }
        }

        /**
         * Records the file's size and modification time, so a scan can tell whether a file needs
         * re-reading without reading it.
         *
         * Both default to 0, which is the safe direction: the incremental scan treats any mismatch
         * as changed, so every existing row re-parses once after the upgrade and is recorded
         * properly, rather than the diff deciding nothing changed when it has no evidence either
         * way. That first pass costs what a full scan costs; every one after it does not.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN modifiedAtEpochMs INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
