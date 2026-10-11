package org.ungoverned.plexora

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

internal fun String.usableMetadataLine(): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("Unknown Artist", ignoreCase = true)) return null
    if (trimmed.equals("Unknown Album", ignoreCase = true)) return null
    return trimmed
}

object PlaybackMetadata {
    fun buildTrackMetadata(
        context: Context,
        track: PlexTrack,
        releaseYear: Int = 0,
        durationOverrideMs: Long = 0L,
    ): MediaMetadata {
        val artist = track.artistTitle.usableMetadataLine().orEmpty()
        val album = track.albumTitle.usableMetadataLine().orEmpty()
        val durationMs = when {
            durationOverrideMs > 0L -> durationOverrideMs
            track.durationMs > 0L -> track.durationMs
            else -> 0L
        }
        val subtitle = buildString {
            if (artist.isNotEmpty()) append(artist)
            if (album.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(album)
            }
            if (releaseYear > 0) {
                if (isNotEmpty()) append(" · ")
                append(releaseYear)
            }
        }
        val extras = Bundle().apply {
            if (track.parentRatingKey.isNotEmpty()) {
                putString(PlaybackTrackExtras.PARENT_RATING_KEY, track.parentRatingKey)
            }
            if (track.grandparentRatingKey.isNotEmpty()) {
                putString(PlaybackTrackExtras.GRANDPARENT_RATING_KEY, track.grandparentRatingKey)
            }
            if (track.playQueueItemId.isNotEmpty()) {
                putString(PlaybackTrackExtras.PLAY_QUEUE_ITEM_ID, track.playQueueItemId)
            }
        }
        return MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(artist)
            .apply {
                if (subtitle.isNotEmpty()) setSubtitle(subtitle)
                if (releaseYear > 0) setReleaseYear(releaseYear)
                if (durationMs > 0L) setDurationMs(durationMs)
                if (!extras.isEmpty) setExtras(extras)
                ArtworkUris.forPlexUrl(context, track.thumbUrl)?.let { setArtworkUri(it) }
            }
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    fun trackMediaItem(
        context: Context,
        track: PlexTrack,
        releaseYear: Int = 0,
        durationOverrideMs: Long = 0L,
    ): MediaItem {
        val streamUri = Uri.parse(track.streamUrl)
        return MediaItem.Builder()
            .setMediaId("track_${track.ratingKey}")
            .setUri(streamUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(streamUri)
                    .build()
            )
            .setMediaMetadata(
                buildTrackMetadata(context, track, releaseYear, durationOverrideMs)
            )
            .build()
    }

    fun trackFromMediaItem(item: MediaItem): PlexTrack? {
        if (!item.mediaId.startsWith("track_")) return null
        val streamUrl = item.localConfiguration?.uri?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val md = item.mediaMetadata
        val title = md.title?.toString()?.trim().orEmpty().ifBlank { return null }
        val extras = md.extras
        return PlexTrack(
            ratingKey = item.mediaId.removePrefix("track_"),
            title = title,
            artistTitle = md.artist?.toString().orEmpty(),
            albumTitle = md.albumTitle?.toString().orEmpty(),
            index = 0,
            durationMs = md.durationMs?.coerceAtLeast(0L) ?: 0L,
            streamUrl = streamUrl,
            thumbUrl = null,
            parentRatingKey = extras?.getString(PlaybackTrackExtras.PARENT_RATING_KEY).orEmpty(),
            grandparentRatingKey = extras?.getString(PlaybackTrackExtras.GRANDPARENT_RATING_KEY).orEmpty(),
            playQueueItemId = extras?.getString(PlaybackTrackExtras.PLAY_QUEUE_ITEM_ID).orEmpty(),
        )
    }
}
