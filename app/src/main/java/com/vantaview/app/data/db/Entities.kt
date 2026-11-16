// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A playlist the user added. kind is "M3U", "XTREAM" or "STALKER" (portal address in baseUrl, MAC address encrypted in passwordEnc). */
@Entity(tableName = "provider", indices = [Index("profileId")])
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The profile this playlist belongs to. */
    val profileId: Long = 0,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val username: String = "",
    val passwordEnc: String = "",
    val epgUrl: String? = null,
    /** A TV guide link the user typed; used instead of the playlist's own. */
    val customEpg: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0,
    val channelCount: Int = 0,
    val lastError: String? = null,
    /** Off: kept, but hidden everywhere (channels, guide, films and series, search) and not refreshed. */
    @androidx.room.ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
)

@Entity(tableName = "category", indices = [Index("providerId")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,
    val name: String,
    val position: Int,
)

/**
 * A live channel. remoteId is the provider's own id (text), so favourites survive a re-sync.
 * For Xtream, streamUrl is "xtream:<stream_id>" and is turned into a real address just before playback,
 * so the login is never stored inside channel rows.
 */
@Entity(
    tableName = "channel",
    indices = [Index("providerId"), Index("providerId", "categoryRemoteId"), Index("providerId", "remoteId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,
    val name: String,
    val sortKey: String,
    val number: Int,
    val logo: String? = null,
    val categoryRemoteId: String? = null,
    val streamUrl: String,
    val epgChannelId: String? = null,
    val headersJson: String? = null,
    /** Marked adult by the provider (Xtream "is_adult"), hidden on Kids profiles. */
    val isAdult: Boolean = false,
    /** Country code worked out after each sync (see CountryDetect); null until then. */
    val country: String? = null,
    /** Days of catch-up the provider keeps for this channel (Stalker tv_archive); 0 = none. */
    val archiveDays: Int = 0,
    /**
     * How to ask for a past programme: "xc" (Xtream timeshift) or an M3U catch-up rule
     * ("default|<template>", "append|<template>", "shift", "flussonic"). Null = none.
     */
    val catchupSource: String? = null,
    /** A radio station: shown under Live TV's Radio tab, not with the TV channels. */
    val isRadio: Boolean = false,
)

@Entity(tableName = "favourite", primaryKeys = ["providerId", "remoteId"])
data class FavouriteEntity(
    val providerId: Long,
    val remoteId: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** Your own order for favourites (Live TV › Favourites › Edit). */
    val position: Int = 0,
)

/** A channel the viewer hid (Live TV › Channels ✎): kept by playlist and the provider's own id, so refreshes keep it hidden. */
@Entity(tableName = "hidden_channel", primaryKeys = ["providerId", "remoteId"])
data class HiddenChannelEntity(val providerId: Long, val remoteId: String)

/** A channel in the show or hide list, and whether it's hidden. */
data class ChannelPick(@androidx.room.Embedded val channel: ChannelEntity, val hidden: Boolean)

data class ChannelRow(
    @Embedded val channel: ChannelEntity,
    val isFav: Boolean,
    val favPos: Int = 0,
    val favAt: Long = 0,
)

/** An installed Stremio-compatible add-on (works the same in Stremio and Nuvio). */
@Entity(tableName = "addon", indices = [Index(value = ["profileId", "manifestUrl"], unique = true)])
data class AddonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long = 0,
    val manifestUrl: String,
    val name: String,
    val manifestJson: String,
    val enabled: Boolean = true,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

/** A person using the app. Favourites, playlists and add-ons belong to a profile. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into the built-in avatar set. */
    val avatar: Int,
    val isKids: Boolean,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Live TV categories a parent has allowed for a Kids profile. */
@Entity(tableName = "kids_category", primaryKeys = ["profileId", "providerId", "categoryRemoteId"])
data class KidsCategoryEntity(
    val profileId: Long,
    val providerId: Long,
    val categoryRemoteId: String,
)

/** A channel's category, name and guide id, used to work out its country. */
data class CountryRow(val id: Long, val categoryRemoteId: String?, val name: String, val epgChannelId: String?)

/** How many channels of each country a category has. */
data class CountryTally(val category: String?, val code: String?, val n: Int)

/**
 * One programme from a playlist's TV guide (XMLTV). channelKey matches ChannelEntity.epgChannelId.
 * Times are milliseconds since 1970 (UTC).
 */
@Entity(tableName = "programme", indices = [Index(value = ["providerId", "channelKey", "start"])])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    /** The provider's own id for this programme, used to ask for its catch-up recording. */
    val remoteId: String? = null,
)

/**
 * A film or series from a playlist (M3U or Xtream Codes), shown on Movies and TV Series. [remoteId] is the
 * Xtream id, or for an M3U a key made from the address (films) or the show's name (series). [url] is the
 * M3U address of a film; Xtream addresses are made when it plays, from the saved login.
 */
@Entity(
    tableName = "vod",
    indices = [Index("providerId", "kind", "groupName"), Index("providerId", "kind", "remoteId"), Index("norm")],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    /** "movie" or "series". */
    val kind: String,
    val remoteId: String,
    val name: String,
    /** The name in lower case without year, quality or punctuation, for matching other sources' titles. */
    val norm: String,
    val year: Int? = null,
    val groupName: String? = null,
    val poster: String? = null,
    val url: String? = null,
    val ext: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    /** IMDb id once known (from the provider, or matched on TMDB when the title is opened). */
    val imdb: String? = null,
    val added: Long = 0,
    val position: Int = 0,
)

/** An episode of an M3U series ([seriesId] is its [VodEntity.remoteId]). */
@Entity(tableName = "vod_episode", indices = [Index("providerId", "seriesId")])
data class VodEpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val url: String,
)
