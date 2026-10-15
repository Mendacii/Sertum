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
    version = 5,
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
    }
}
