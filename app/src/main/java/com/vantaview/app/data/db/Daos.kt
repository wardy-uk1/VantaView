// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    /** A profile's playlists that are switched on. */
    @Query("SELECT * FROM provider WHERE profileId = :profileId AND enabled = 1 ORDER BY createdAt")
    fun observeFor(profileId: Long): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider WHERE profileId = :profileId AND enabled = 1 ORDER BY createdAt")
    suspend fun allFor(profileId: Long): List<ProviderEntity>

    /** Every playlist of a profile, switched off ones too (the Playlists page, copying and removing). */
    @Query("SELECT * FROM provider WHERE profileId = :profileId ORDER BY createdAt")
    fun observeEveryFor(profileId: Long): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider WHERE profileId = :profileId ORDER BY createdAt")
    suspend fun everyFor(profileId: Long): List<ProviderEntity>

    @Query("UPDATE provider SET enabled = :on WHERE id = :id")
    suspend fun setEnabled(id: Long, on: Boolean)

    @Query("SELECT * FROM provider WHERE id = :id")
    suspend fun get(id: Long): ProviderEntity?

    @Query("SELECT COUNT(*) FROM provider WHERE profileId = :profileId")
    suspend fun countFor(profileId: Long): Int

    @Query("UPDATE provider SET profileId = :profileId WHERE profileId = 0")
    suspend fun adoptOrphans(profileId: Long)

    @Insert
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM provider WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM category WHERE providerId = :providerId ORDER BY position")
    fun observeCategories(providerId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE providerId = :providerId ORDER BY position")
    suspend fun categories(providerId: Long): List<CategoryEntity>

    @Query(
        """
        SELECT c.*, (f.remoteId IS NOT NULL) AS isFav, COALESCE(f.position, 0) AS favPos, COALESCE(f.addedAt, 0) AS favAt
        FROM channel c
        LEFT JOIN favourite f ON f.providerId = c.providerId AND f.remoteId = c.remoteId
        WHERE c.providerId = :providerId
          AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = c.providerId AND h.remoteId = c.remoteId)
          AND (:category IS NULL OR c.categoryRemoteId = :category)
          AND (:query = '' OR c.name LIKE '%' || :query || '%' OR CAST(c.number AS TEXT) = :query)
          AND (:favOnly = 0 OR f.remoteId IS NOT NULL)
          AND (:favOnly = 1 OR :query != '' OR c.isRadio = :radio)
        ORDER BY c.number, c.sortKey
        LIMIT 3000
        """
    )
    fun observeChannels(providerId: Long, category: String?, query: String, favOnly: Int, radio: Int): Flow<List<ChannelRow>>

    /** Channels from the chosen countries, optionally in one category. */
    @Query(
        """
        SELECT c.*, (f.remoteId IS NOT NULL) AS isFav, COALESCE(f.position, 0) AS favPos, COALESCE(f.addedAt, 0) AS favAt
        FROM channel c
        LEFT JOIN favourite f ON f.providerId = c.providerId AND f.remoteId = c.remoteId
        WHERE c.providerId = :providerId
          AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = c.providerId AND h.remoteId = c.remoteId)
          AND (:category IS NULL OR c.categoryRemoteId = :category)
          AND c.country IN (:codes)
          AND (:query = '' OR c.name LIKE '%' || :query || '%' OR CAST(c.number AS TEXT) = :query)
          AND (:favOnly = 0 OR f.remoteId IS NOT NULL)
          AND (:favOnly = 1 OR :query != '' OR c.isRadio = :radio)
        ORDER BY c.number, c.sortKey
        LIMIT 3000
        """
    )
    fun observeChannelsIn(providerId: Long, category: String?, codes: List<String>, query: String, favOnly: Int, radio: Int): Flow<List<ChannelRow>>

    /** How many radio stations a playlist has (Live TV shows a Radio tab when there are some). */
    @Query("SELECT COUNT(*) FROM channel WHERE providerId = :providerId AND isRadio = 1")
    fun observeRadioCount(providerId: Long): Flow<Int>

    /** Just what's needed to work out channels' countries, with no limit. */
    @Query("SELECT id, categoryRemoteId, name, epgChannelId FROM channel WHERE providerId = :providerId")
    suspend fun countryRows(providerId: Long): List<CountryRow>

    @Query("SELECT COUNT(*) FROM channel WHERE providerId = :providerId AND country IS NULL")
    suspend fun countryMissing(providerId: Long): Int

    @Query("UPDATE channel SET country = :code WHERE id IN (:ids)")
    suspend fun setCountry(code: String, ids: List<Long>)

    @Query("SELECT categoryRemoteId AS category, country AS code, COUNT(*) AS n FROM channel WHERE providerId = :providerId GROUP BY categoryRemoteId, country")
    suspend fun countryTally(providerId: Long): List<CountryTally>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun channel(id: Long): ChannelEntity?

    /** Several channels by id (in any order), for zapping through the list a channel was opened from. */
    @Query("SELECT * FROM channel WHERE id IN (:ids)")
    suspend fun channelsById(ids: List<Long>): List<ChannelEntity>

    /** Guide IDs used by a playlist's channels. */
    @Query("SELECT DISTINCT epgChannelId FROM channel WHERE providerId = :providerId AND epgChannelId IS NOT NULL AND epgChannelId != ''")
    suspend fun guideKeys(providerId: Long): List<String>

    /** Guide IDs used by channels from the chosen countries. */
    @Query("SELECT DISTINCT epgChannelId FROM channel WHERE providerId = :providerId AND country IN (:codes) AND epgChannelId IS NOT NULL AND epgChannelId != ''")
    suspend fun guideKeysIn(providerId: Long, codes: List<String>): List<String>

    /** Search: channels by name or number across several playlists. */
    @Query(
        """
        SELECT * FROM channel
        WHERE providerId IN (:providerIds)
          AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = channel.providerId AND h.remoteId = channel.remoteId)
          AND (name LIKE '%' || :query || '%' OR CAST(number AS TEXT) = :query)
        ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, number
        LIMIT 60
        """
    )
    suspend fun searchChannels(providerIds: List<Long>, query: String): List<ChannelEntity>

    /** Channels whose guide ID is one of [keys] (to show a programme's channel). */
    /** The first channels of a playlist (by number), e.g. one to run the speed test on. */
    @Query("SELECT * FROM channel WHERE providerId = :providerId ORDER BY number, sortKey LIMIT :n")
    suspend fun channelsPage(providerId: Long, n: Int): List<ChannelEntity>

    /** Channels with catch-up (Catch-up page), in channel-number order. */
    @Query("SELECT COUNT(*) FROM channel WHERE providerId IN (:providerIds) AND archiveDays > 0 AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = channel.providerId AND h.remoteId = channel.remoteId)")
    suspend fun catchUpCount(providerIds: List<Long>): Int

    @Query("SELECT * FROM channel WHERE providerId IN (:providerIds) AND archiveDays > 0 AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = channel.providerId AND h.remoteId = channel.remoteId) ORDER BY number, sortKey")
    suspend fun catchUpChannels(providerIds: List<Long>): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE providerId = :providerId AND epgChannelId IN (:keys) AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = channel.providerId AND h.remoteId = channel.remoteId)")
    suspend fun channelsByGuideKey(providerId: Long, keys: List<String>): List<ChannelEntity>

    /** A channel by its number in a playlist (for typing a number on the remote). */
    @Query("SELECT * FROM channel WHERE providerId = :providerId AND number = :number AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = channel.providerId AND h.remoteId = channel.remoteId) ORDER BY sortKey LIMIT 1")
    suspend fun channelByNumber(providerId: Long, number: Int): ChannelEntity?

    @Query(
        """
        SELECT * FROM channel
        WHERE providerId = :providerId AND (:category IS NULL OR categoryRemoteId = :category)
          AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = channel.providerId AND h.remoteId = channel.remoteId)
        ORDER BY number, sortKey
        LIMIT 5000
        """
    )
    suspend fun siblings(providerId: Long, category: String?): List<ChannelEntity>

    @Insert
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert
    suspend fun insertChannels(items: List<ChannelEntity>)

    @Query("DELETE FROM category WHERE providerId = :providerId")
    suspend fun deleteCategories(providerId: Long)

    @Query("DELETE FROM channel WHERE providerId = :providerId")
    suspend fun deleteChannels(providerId: Long)

    @Query("DELETE FROM favourite WHERE providerId = :providerId")
    suspend fun deleteFavourites(providerId: Long)

    // ---- Show or hide channels ----

    /** Every channel of a playlist (hidden ones too), optionally in one group or matching a search. */
    @Query(
        """
        SELECT c.*, EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = c.providerId AND h.remoteId = c.remoteId) AS hidden
        FROM channel c
        WHERE c.providerId = :providerId
          AND (:category IS NULL OR c.categoryRemoteId = :category)
          AND (:query = '' OR c.name LIKE '%' || :query || '%' OR CAST(c.number AS TEXT) = :query)
          AND (:hiddenOnly = 0 OR EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = c.providerId AND h.remoteId = c.remoteId))
        ORDER BY c.number, c.sortKey
        LIMIT 5000
        """
    )
    fun observePicks(providerId: Long, category: String?, query: String, hiddenOnly: Int): Flow<List<ChannelPick>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(items: List<HiddenChannelEntity>)

    @Query("DELETE FROM hidden_channel WHERE providerId = :providerId AND remoteId IN (:remoteIds)")
    suspend fun unhide(providerId: Long, remoteIds: List<String>)

    @Query("DELETE FROM hidden_channel WHERE providerId = :providerId")
    suspend fun deleteHidden(providerId: Long)

    @Query("SELECT COUNT(*) FROM hidden_channel WHERE providerId = :providerId")
    fun observeHiddenCount(providerId: Long): Flow<Int>

    /** Swap a provider's live content in one go, so a failed sync never leaves an empty list. */
    @Transaction
    suspend fun replaceLive(providerId: Long, categories: List<CategoryEntity>, channels: List<ChannelEntity>) {
        deleteCategories(providerId)
        deleteChannels(providerId)
        categories.chunked(500).forEach { insertCategories(it) }
        channels.chunked(500).forEach { insertChannels(it) }
    }

    /** Favourite channels across the given playlists, newest first. */
    @Query(
        """
        SELECT c.* FROM channel c
        JOIN favourite f ON f.providerId = c.providerId AND f.remoteId = c.remoteId
        WHERE c.providerId IN (:providerIds)
          AND NOT EXISTS (SELECT 1 FROM hidden_channel h WHERE h.providerId = c.providerId AND h.remoteId = c.remoteId)
        ORDER BY f.position, f.addedAt DESC
        LIMIT 40
        """
    )
    fun observeFavouriteChannels(providerIds: List<Long>): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM channel WHERE providerId IN (:providerIds)")
    fun observeChannelCount(providerIds: List<Long>): Flow<Int>

    @Query("SELECT logo FROM channel WHERE providerId IN (:providerIds) AND logo IS NOT NULL AND logo != '' ORDER BY number LIMIT 4")
    suspend fun sampleLogos(providerIds: List<Long>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavourite(fav: FavouriteEntity)

    @Query("DELETE FROM favourite WHERE providerId = :providerId AND remoteId = :remoteId")
    suspend fun removeFavourite(providerId: Long, remoteId: String)

    @Query("SELECT COALESCE(MAX(position), 0) FROM favourite WHERE providerId = :providerId")
    suspend fun maxFavouritePosition(providerId: Long): Int

    @Query("UPDATE favourite SET position = :position WHERE providerId = :providerId AND remoteId = :remoteId")
    suspend fun setFavouritePosition(providerId: Long, remoteId: String, position: Int)
}

@Dao
interface AddonDao {
    @Query("SELECT * FROM addon WHERE profileId = :profileId ORDER BY position, addedAt")
    fun observeFor(profileId: Long): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addon WHERE profileId = :profileId ORDER BY position, addedAt")
    suspend fun allFor(profileId: Long): List<AddonEntity>

    @Query("SELECT COUNT(*) FROM addon WHERE profileId = :profileId")
    suspend fun countFor(profileId: Long): Int

    @Query("SELECT * FROM addon WHERE profileId = :profileId AND manifestUrl = :url")
    suspend fun byUrl(profileId: Long, url: String): AddonEntity?

    @Query("UPDATE addon SET profileId = :profileId WHERE profileId = 0")
    suspend fun adoptOrphans(profileId: Long)

    @Query("DELETE FROM addon WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(addon: AddonEntity): Long

    @Query("UPDATE addon SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM addon WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile ORDER BY createdAt")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = :id")
    fun observe(id: Long): Flow<ProfileEntity?>

    @Insert
    suspend fun insert(p: ProfileEntity): Long

    @Update
    suspend fun update(p: ProfileEntity)

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM kids_category WHERE profileId = :profileId")
    fun observeKidsCategories(profileId: Long): Flow<List<KidsCategoryEntity>>

    @Query("SELECT * FROM kids_category WHERE profileId = :profileId")
    suspend fun kidsCategories(profileId: Long): List<KidsCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addKidsCategory(k: KidsCategoryEntity)

    @Query("DELETE FROM kids_category WHERE profileId = :profileId AND providerId = :providerId AND categoryRemoteId = :remoteId")
    suspend fun removeKidsCategory(profileId: Long, providerId: Long, remoteId: String)

    @Query("DELETE FROM kids_category WHERE profileId = :profileId")
    suspend fun clearKidsCategories(profileId: Long)
}

@Dao
interface GuideDao {
    @Insert
    suspend fun insert(items: List<ProgrammeEntity>)

    @Query("DELETE FROM programme WHERE providerId = :providerId")
    suspend fun deleteFor(providerId: Long)

    @Query("DELETE FROM programme")
    suspend fun clearAll()

    /** Keeps programmes that ended after [from] and before [cut] (catch-up), and drops the rest. */
    @Query("DELETE FROM programme WHERE providerId = :providerId AND (stop <= :from OR stop > :cut)")
    suspend fun keepPast(providerId: Long, from: Long, cut: Long)

    @Query("SELECT channelKey || '|' || start FROM programme WHERE providerId = :providerId AND stop <= :cut")
    suspend fun pastKeys(providerId: Long, cut: Long): List<String>

    /** Search: programmes on now or later whose title matches. */
    @Query(
        """
        SELECT * FROM programme
        WHERE providerId IN (:providerIds) AND title LIKE '%' || :query || '%' AND stop > :now
        ORDER BY start LIMIT 80
        """
    )
    suspend fun search(providerIds: List<Long>, query: String, now: Long): List<ProgrammeEntity>

    /** Search: programmes that have finished (since [from]) whose title matches, newest first. */
    @Query(
        """
        SELECT * FROM programme
        WHERE providerId IN (:providerIds) AND title LIKE '%' || :query || '%' AND stop <= :now AND stop > :from
        ORDER BY start DESC LIMIT 80
        """
    )
    suspend fun searchPast(providerIds: List<Long>, query: String, now: Long, from: Long): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programme WHERE providerId = :providerId")
    suspend fun count(providerId: Long): Int

    /** Programmes on one channel that overlap [from, to). */
    @Query(
        """
        SELECT * FROM programme
        WHERE providerId = :providerId AND channelKey = :key AND stop > :from AND start < :to
        ORDER BY start
        """
    )
    suspend fun range(providerId: Long, key: String, from: Long, to: Long): List<ProgrammeEntity>

    /** What's on now and next on one channel. */
    @Query(
        """
        SELECT * FROM programme
        WHERE providerId = :providerId AND channelKey = :key AND stop > :now
        ORDER BY start LIMIT 3
        """
    )
    suspend fun nowNext(providerId: Long, key: String, now: Long): List<ProgrammeEntity>

    /** What's on now across several channels (keys are guide IDs). */
    @Query(
        """
        SELECT * FROM programme
        WHERE providerId = :providerId AND channelKey IN (:keys) AND start <= :now AND stop > :now
        """
    )
    suspend fun onNow(providerId: Long, keys: List<String>, now: Long): List<ProgrammeEntity>
}

/** Whole-table reads and writes used by backup and restore. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM profile") suspend fun profiles(): List<ProfileEntity>
    @Query("SELECT * FROM provider") suspend fun providers(): List<ProviderEntity>
    @Query("SELECT * FROM favourite") suspend fun favourites(): List<FavouriteEntity>
    @Query("SELECT * FROM addon") suspend fun addons(): List<AddonEntity>
    @Query("SELECT * FROM kids_category") suspend fun kidsCategories(): List<KidsCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfiles(items: List<ProfileEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProviders(items: List<ProviderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFavourites(items: List<FavouriteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAddons(items: List<AddonEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putKidsCategories(items: List<KidsCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addAddon(item: AddonEntity): Long
    @Insert suspend fun addProfile(item: ProfileEntity): Long
    @Insert suspend fun addProvider(item: ProviderEntity): Long

    /** Fields that matter for a backup, so a re-sync (new counts and times) doesn't trigger one. */
    @Query("SELECT id || '|' || profileId || '|' || name || '|' || baseUrl || '|' || username || '|' || passwordEnc FROM provider ORDER BY id")
    fun watchProviders(): Flow<List<String>>

    @Query("SELECT id || '|' || name || '|' || avatar || '|' || isKids || '|' || IFNULL(pinHash, '') FROM profile ORDER BY id")
    fun watchProfiles(): Flow<List<String>>
}

/** A playlist's films and series. */
@Dao
interface VodDao {
    @Query("DELETE FROM vod WHERE providerId = :providerId")
    suspend fun deleteFor(providerId: Long)

    @Query("DELETE FROM vod_episode WHERE providerId = :providerId")
    suspend fun deleteEpisodesFor(providerId: Long)

    @Insert
    suspend fun insert(items: List<VodEntity>)

    @Insert
    suspend fun insertEpisodes(items: List<VodEpisodeEntity>)

    /** Swaps a playlist's films and series for a fresh list, keeping IMDb ids already matched. */
    @Transaction
    suspend fun replace(providerId: Long, items: List<VodEntity>, episodes: List<VodEpisodeEntity>) {
        val known = imdbIds(providerId).associate { it.remoteId to it.imdb }
        deleteFor(providerId)
        deleteEpisodesFor(providerId)
        items.chunked(500).forEach { c -> insert(c.map { v -> if (v.imdb == null) v.copy(imdb = known[v.remoteId]) else v }) }
        episodes.chunked(500).forEach { insertEpisodes(it) }
    }

    data class KnownImdb(val remoteId: String, val imdb: String?)

    @Query("SELECT remoteId, imdb FROM vod WHERE providerId = :providerId AND imdb IS NOT NULL")
    suspend fun imdbIds(providerId: Long): List<KnownImdb>

    data class Group(val groupName: String?, val count: Int)

    @Query("SELECT groupName, COUNT(*) AS count FROM vod WHERE providerId = :providerId AND kind = :kind GROUP BY groupName ORDER BY MIN(position)")
    suspend fun groups(providerId: Long, kind: String): List<Group>

    @Query("SELECT COUNT(*) FROM vod WHERE providerId = :providerId AND kind = :kind")
    suspend fun count(providerId: Long, kind: String): Int

    @Query("SELECT * FROM vod WHERE providerId = :providerId AND kind = :kind AND groupName = :group ORDER BY position LIMIT :limit OFFSET :offset")
    suspend fun inGroup(providerId: Long, kind: String, group: String, limit: Int, offset: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId = :providerId AND kind = :kind ORDER BY position LIMIT :limit OFFSET :offset")
    suspend fun all(providerId: Long, kind: String, limit: Int, offset: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId = :providerId AND kind = :kind ORDER BY added DESC, position LIMIT :limit OFFSET :offset")
    suspend fun newest(providerId: Long, kind: String, limit: Int, offset: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId IN (:providerIds) AND kind = :kind AND norm LIKE :like ORDER BY LENGTH(norm) LIMIT :limit")
    suspend fun search(providerIds: List<Long>, kind: String, like: String, limit: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId IN (:providerIds) AND kind = :kind AND (norm = :norm OR imdb = :imdb)")
    suspend fun matches(providerIds: List<Long>, kind: String, norm: String, imdb: String): List<VodEntity>

    @Query("SELECT * FROM vod WHERE providerId = :providerId AND kind = :kind AND remoteId = :remoteId LIMIT 1")
    suspend fun find(providerId: Long, kind: String, remoteId: String): VodEntity?

    @Query("UPDATE vod SET imdb = :imdb WHERE id = :id")
    suspend fun setImdb(id: Long, imdb: String)

    @Query("SELECT * FROM vod_episode WHERE providerId = :providerId AND seriesId = :seriesId ORDER BY season, episode")
    suspend fun episodes(providerId: Long, seriesId: String): List<VodEpisodeEntity>
}
