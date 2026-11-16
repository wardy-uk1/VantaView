// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class,
        AddonEntity::class, ProfileEntity::class, KidsCategoryEntity::class, ProgrammeEntity::class,
        VodEntity::class, VodEpisodeEntity::class,
    ],
    // Version 3 (profiles) starts the test database afresh: playlists and add-ons are added again.
    version = 10,
    exportSchema = false,
)
abstract class VantaDb : RoomDatabase() {
    abstract fun providers(): ProviderDao
    abstract fun content(): ContentDao
    abstract fun addons(): AddonDao
    abstract fun profiles(): ProfileDao
    abstract fun guide(): GuideDao
    abstract fun backup(): BackupDao
    abstract fun vod(): VodDao

    companion object {
        /** Version 10: playlists can be switched off. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `provider` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Version 9: films and series from playlists (M3U and Xtream Codes), filled on the next refresh. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vod` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `providerId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, `norm` TEXT NOT NULL, `year` INTEGER, " +
                        "`groupName` TEXT, `poster` TEXT, `url` TEXT, `ext` TEXT, `plot` TEXT, `rating` TEXT, `imdb` TEXT, " +
                        "`added` INTEGER NOT NULL, `position` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_providerId_kind_groupName` ON `vod` (`providerId`, `kind`, `groupName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_providerId_kind_remoteId` ON `vod` (`providerId`, `kind`, `remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_norm` ON `vod` (`norm`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vod_episode` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `providerId` INTEGER NOT NULL, " +
                        "`seriesId` TEXT NOT NULL, `season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `title` TEXT, `url` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_episode_providerId_seriesId` ON `vod_episode` (`providerId`, `seriesId`)")
            }
        }

        /** Version 8: radio stations are marked, for Live TV's Radio tab (set on the next refresh). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `isRadio` INTEGER NOT NULL DEFAULT 0")
                // Mark the obvious ones straight away; refreshing a playlist finds the rest.
                db.execSQL("UPDATE `channel` SET `isRadio` = 1 WHERE `name` LIKE '%radio%' AND `name` NOT LIKE '%tv%' AND `name` NOT LIKE '%televi%'")
            }
        }

        /** Version 7: favourites order, M3U/Xtream catch-up rules, and a typed TV guide link per playlist. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `favourite` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `catchupSource` TEXT")
                db.execSQL("ALTER TABLE `provider` ADD COLUMN `customEpg` TEXT")
            }
        }

        /** Version 6 adds catch-up: how many days each channel keeps, and each programme's own id. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `archiveDays` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `programme` ADD COLUMN `remoteId` TEXT")
            }
        }

        /** Version 5 adds the TV guide's programmes; everything else is kept. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `programme` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`providerId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                        "`stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `category` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programme_providerId_channelKey_start` " +
                        "ON `programme` (`providerId`, `channelKey`, `start`)"
                )
            }
        }

        /** Version 4 adds each channel's country; existing playlists are kept and sorted on next open. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `country` TEXT")
            }
        }

        /** Version 2 adds installed add-ons; playlists and favourites are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `addon` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`manifestUrl` TEXT NOT NULL, `name` TEXT NOT NULL, `manifestJson` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_addon_manifestUrl` ON `addon` (`manifestUrl`)")
            }
        }
    }
}
