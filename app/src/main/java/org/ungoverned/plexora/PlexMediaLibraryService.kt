package org.ungoverned.plexora

import android.annotation.SuppressLint
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class PlexMediaLibraryService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var plexClient: PlexClient
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentPlayQueueId: String? = null

    override fun onCreate() {
        super.onCreate()
        plexClient = PlexClient(this)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handleAudioFocus = true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlexMediaService", "Player Error: ${error.message}", error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                savePlaybackState()
                checkAndRefillQueue()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                savePlaybackState()
                checkAndRefillQueue()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    savePlaybackState()
                }
            }
        })

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            player,
            LibrarySessionCallback()
        ).build()

        val playbackPrefs = getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        currentPlayQueueId = playbackPrefs.getString("last_play_queue_id", null)

        restorePlaybackState()
    }

    private fun savePlaybackState() {
        val currentItem = player.currentMediaItem ?: return
        val prefs = getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        
        // Store current track, position, and queue metadata
        val queueIds = mutableListOf<String>()
        for (i in 0 until player.mediaItemCount) {
            player.getMediaItemAt(i).mediaId.let { queueIds.add(it) }
        }

        prefs.edit()
            .putString("last_media_id", currentItem.mediaId)
            .putLong("last_position", player.currentPosition)
            .putInt("last_index", player.currentMediaItemIndex)
            .putString("last_queue", queueIds.joinToString(","))
            .putString("last_play_queue_id", currentPlayQueueId)
            .apply()
    }

    private fun checkAndRefillQueue() {
        val playQueueId = currentPlayQueueId ?: return
        val currentIndex = player.currentMediaItemIndex
        val totalCount = player.mediaItemCount
        
        // If we're within 10 tracks of the end, fetch more
        if (totalCount - currentIndex < 10) {
            scope.launch(Dispatchers.IO) {
                try {
                    val nextQueue = plexClient.getPlayQueue(playQueueId)
                    if (nextQueue != null) {
                        val existingIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val newItems = nextQueue.tracks
                            .map { createMediaItem(it) }
                            .filter { it.mediaId !in existingIds }
                        
                        if (newItems.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                player.addMediaItems(newItems)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlexMediaService", "Failed to refill queue", e)
                }
            }
        }
    }

    private fun restorePlaybackState() {
        val prefs = getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        val lastMediaId = prefs.getString("last_media_id", null) ?: return
        val lastPosition = prefs.getLong("last_position", 0L)
        val lastIndex = prefs.getInt("last_index", 0)
        val lastQueue = prefs.getString("last_queue", null)
        val playQueueId = prefs.getString("last_play_queue_id", null)

        scope.launch(Dispatchers.IO) {
            try {
                val mediaItems = mutableListOf<MediaItem>()
                
                if (playQueueId != null) {
                    // Optimized restoration: Fetch the current queue state from server
                    val queue = plexClient.getPlayQueue(playQueueId)
                    if (queue != null) {
                        currentPlayQueueId = queue.id
                        mediaItems.addAll(queue.tracks.map { createMediaItem(it) })
                    }
                } else if (lastQueue != null) {
                    // Fallback to individual track lookup if no server-side queue exists
                    val mediaIds = lastQueue.split(",")
                    for (id in mediaIds) {
                        val ratingKey = id.removePrefix("track_")
                        val track = plexClient.getTrack(ratingKey)
                        if (track != null) {
                            mediaItems.add(createMediaItem(track))
                        }
                    }
                }

                if (mediaItems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        player.setMediaItems(mediaItems, lastIndex, lastPosition)
                        player.prepare()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlexMediaService", "Failed to restore state", e)
            }
        }
    }

    private fun createMediaItem(track: PlexTrack): MediaItem {
        return MediaItem.Builder()
            .setMediaId("track_${track.ratingKey}")
            .setUri(Uri.parse(track.streamUrl))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistTitle)
                    .setAlbumTitle(track.albumTitle)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        track.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        savePlaybackState()
        mediaLibrarySession.release()
        player.release()
        executor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        
        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            executor.submit {
                try {
                    val resolvedItems = mutableListOf<MediaItem>()
                    for (item in mediaItems) {
                        if (item.localConfiguration?.uri != null) {
                            resolvedItems.add(item)
                        } else if (item.mediaId.startsWith("track_")) {
                            val ratingKey = item.mediaId.removePrefix("track_")
                            plexClient.getTrack(ratingKey)?.let {
                                resolvedItems.add(createMediaItem(it))
                            }
                        } else {
                            resolvedItems.add(item)
                        }
                    }
                    future.set(resolvedItems)
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
            availableSessionCommands.add(SessionCommand("SET_PLAY_QUEUE_ID", android.os.Bundle.EMPTY))
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                connectionResult.availablePlayerCommands
            )
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "SET_PLAY_QUEUE_ID") {
                currentPlayQueueId = args.getString("play_queue_id")
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        @OptIn(UnstableApi::class)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Root")
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .build()
                )
                .build()
            
            return SettableFuture.create<LibraryResult<MediaItem>>().apply {
                set(LibraryResult.ofItem(rootItem, params))
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            executor.submit {
                try {
                    val items = mutableListOf<MediaItem>()
                    if (parentId == "root") {
                        items.add(
                            MediaItem.Builder()
                                .setMediaId("artists")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Artists")
                                        .setFolderType(MediaMetadata.FOLDER_TYPE_ARTISTS)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
                                        .build()
                                )
                                .build()
                        )
                        items.add(
                            MediaItem.Builder()
                                .setMediaId("playlists")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Playlists")
                                        .setFolderType(MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                                        .build()
                                )
                                .build()
                        )
                    } else if (parentId == "artists") {
                        val plexArtists = plexClient.getArtists()
                        for (artist in plexArtists) {
                            items.add(
                                MediaItem.Builder()
                                    .setMediaId("artist_${artist.ratingKey}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(artist.title)
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                                            .setIsPlayable(false)
                                            .setIsBrowsable(true)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                            .apply {
                                                artist.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    } else if (parentId == "playlists") {
                        val plexPlaylists = plexClient.getPlaylists()
                        for (playlist in plexPlaylists) {
                            items.add(
                                MediaItem.Builder()
                                    .setMediaId("playlist_${playlist.ratingKey}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(playlist.title)
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_TITLES)
                                            .setIsPlayable(false)
                                            .setIsBrowsable(true)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                            .apply {
                                                playlist.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    } else if (parentId.startsWith("playlist_")) {
                        val playlistRatingKey = parentId.removePrefix("playlist_")
                        val response = plexClient.getPlaylistTracks(playlistRatingKey)
                        for (track in response.tracks) {
                            items.add(createMediaItem(track))
                        }
                    } else if (parentId.startsWith("artist_")) {
                        val artistRatingKey = parentId.removePrefix("artist_")
                        val plexAlbums = plexClient.getAlbums(artistRatingKey)
                        for (album in plexAlbums) {
                            items.add(
                                MediaItem.Builder()
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
                                                album.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    } else if (parentId.startsWith("album_")) {
                        val albumRatingKey = parentId.removePrefix("album_")
                        val response = plexClient.getTracks(albumRatingKey)
                        for (track in response.tracks) {
                            items.add(
                                MediaItem.Builder()
                                    .setMediaId("track_${track.ratingKey}")
                                    .setUri(Uri.parse(track.streamUrl))
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(track.title)
                                            .setArtist(track.artistTitle)
                                            .setAlbumTitle(track.albumTitle)
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .apply {
                                                track.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                    
                    val result = LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    future.set(result)
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            executor.submit {
                try {
                    val item = when {
                        mediaId == "root" -> {
                            MediaItem.Builder()
                                .setMediaId("root")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Root")
                                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .build()
                                )
                                .build()
                        }
                        mediaId == "artists" -> {
                            MediaItem.Builder()
                                .setMediaId("artists")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Artists")
                                        .setFolderType(MediaMetadata.FOLDER_TYPE_ARTISTS)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
                                        .build()
                                )
                                .build()
                        }
                        mediaId == "playlists" -> {
                            MediaItem.Builder()
                                .setMediaId("playlists")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Playlists")
                                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                                        .build()
                                )
                                .build()
                        }
                        mediaId.startsWith("artist_") -> {
                            val ratingKey = mediaId.removePrefix("artist_")
                            plexClient.getArtist(ratingKey)?.let { artist ->
                                MediaItem.Builder()
                                    .setMediaId("artist_${artist.ratingKey}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(artist.title)
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                                            .setIsPlayable(false)
                                            .setIsBrowsable(true)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                            .apply {
                                                artist.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            }
                        }
                        mediaId.startsWith("album_") -> {
                            val ratingKey = mediaId.removePrefix("album_")
                            plexClient.getAlbum(ratingKey)?.let { album ->
                                MediaItem.Builder()
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
                                                album.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            }
                        }
                        mediaId.startsWith("playlist_") -> {
                            val ratingKey = mediaId.removePrefix("playlist_")
                            plexClient.getPlaylist(ratingKey)?.let { playlist ->
                                MediaItem.Builder()
                                    .setMediaId("playlist_${playlist.ratingKey}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(playlist.title)
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_TITLES)
                                            .setIsPlayable(false)
                                            .setIsBrowsable(true)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                            .apply {
                                                playlist.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                            }
                                            .build()
                                    )
                                    .build()
                            }
                        }
                        mediaId.startsWith("track_") -> {
                            val ratingKey = mediaId.removePrefix("track_")
                            plexClient.getTrack(ratingKey)?.let { createMediaItem(it) }
                        }
                        else -> null
                    }

                    if (item != null) {
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        @OptIn(UnstableApi::class)
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 100, params)
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofVoid())
        }

        @OptIn(UnstableApi::class)
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            executor.submit {
                try {
                    val items = mutableListOf<MediaItem>()

                    // Search Artists
                    plexClient.searchArtists(query).forEach { artist ->
                        items.add(
                            MediaItem.Builder()
                                .setMediaId("artist_${artist.ratingKey}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(artist.title)
                                        .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                        .apply {
                                            artist.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                        }
                                        .build()
                                )
                                .build()
                        )
                    }

                    // Search Albums
                    plexClient.searchAlbums(query).forEach { album ->
                        items.add(
                            MediaItem.Builder()
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
                                            album.thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                                        }
                                        .build()
                                )
                                .build()
                        )
                    }

                    // Search Tracks
                    plexClient.searchTracks(query).forEach { track ->
                        items.add(createMediaItem(track))
                    }

                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }
}
