package org.ungoverned.plexora

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService

object AutomotiveBrowseItems {
    const val ROOT_ID = "root"
    const val ARTISTS_ID = "artists"
    const val ALBUMS_ID = "all_albums"
    const val RECENT_ID = "recently_added"
    const val PLAYLISTS_ID = "playlists"

    const val SHUFFLE_LIBRARY_ID = "shuffle::library"
    const val SHUFFLE_ALL_ALBUMS_ID = "shuffle::all_albums"
    const val SHUFFLE_RECENTLY_ADDED_ID = "shuffle::recently_added"
    const val SHUFFLE_ARTIST_PREFIX = "shuffle::artist::"
    const val SHUFFLE_ALBUM_PREFIX = "shuffle::album::"
    const val SHUFFLE_PLAYLIST_PREFIX = "shuffle::playlist::"

    @OptIn(UnstableApi::class)
    fun rootLibraryParams(): MediaLibraryService.LibraryParams {
        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        return MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
    }

    fun rootItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Plexora")
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .build()
            )
            .build()
    }

    fun folderItem(
        mediaId: String,
        title: String,
        mediaType: Int,
        folderType: Int,
        artworkUri: Uri? = null
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setFolderType(folderType)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .apply { artworkUri?.let { setArtworkUri(it) } }
                    .build()
            )
            .build()
    }

    fun shuffleItem(title: String = "Shuffle"): MediaItem {
        return MediaItem.Builder()
            .setMediaId(SHUFFLE_LIBRARY_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    fun contextualShuffleItem(mediaId: String, title: String = "Shuffle"): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    fun artistItem(context: Context, artist: PlexArtist): MediaItem {
        return folderItem(
            mediaId = "artist_${artist.ratingKey}",
            title = artist.title,
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            folderType = MediaMetadata.FOLDER_TYPE_ALBUMS,
            artworkUri = ArtworkUris.forPlexUrl(context, artist.thumbUrl)
        )
    }

    fun albumItem(context: Context, album: PlexAlbum): MediaItem {
        return MediaItem.Builder()
            .setMediaId("album_${album.ratingKey}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(album.title)
                    .setArtist(album.artistTitle)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_TITLES)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                    .apply {
                        ArtworkUris.forPlexUrl(context, album.thumbUrl)?.let { setArtworkUri(it) }
                    }
                    .build()
            )
            .build()
    }

    fun playlistItem(context: Context, playlist: PlexPlaylist): MediaItem {
        return folderItem(
            mediaId = "playlist_${playlist.ratingKey}",
            title = playlist.title,
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            folderType = MediaMetadata.FOLDER_TYPE_TITLES,
            artworkUri = ArtworkUris.forPlexUrl(context, playlist.thumbUrl)
        )
    }

    fun trackItem(context: Context, track: PlexTrack): MediaItem {
        return MediaItem.Builder()
            .setMediaId("track_${track.ratingKey}")
            .setUri(Uri.parse(track.streamUrl))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistTitle)
                    .setAlbumTitle(track.albumTitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        ArtworkUris.forPlexUrl(context, track.thumbUrl)?.let { setArtworkUri(it) }
                    }
                    .build()
            )
            .build()
    }
}
