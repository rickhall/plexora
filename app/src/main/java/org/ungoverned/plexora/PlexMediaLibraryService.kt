package org.ungoverned.plexora

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
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
    @Volatile
    private var lastBrowseParentId: String? = null

    private val libraryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlexIntents.ACTION_LIBRARY_CONFIGURED -> refreshLibraryBrowsers()
                PlexIntents.ACTION_LIBRARY_CLEARED -> refreshLibraryBrowsers()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        plexClient = PlexClient(this)

        val libraryFilter = IntentFilter().apply {
            addAction(PlexIntents.ACTION_LIBRARY_CONFIGURED)
            addAction(PlexIntents.ACTION_LIBRARY_CLEARED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(libraryStateReceiver, libraryFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(libraryStateReceiver, libraryFilter)
        }

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
        return AutomotiveBrowseItems.trackItem(this, track)
    }

    private fun resolveShufflePlayQueue(mediaId: String): PlexPlayQueue? {
        val machineId = plexClient.getMachineId()
        if (machineId.isEmpty()) return null
        return when {
            mediaId == AutomotiveBrowseItems.SHUFFLE_LIBRARY_ID ->
                plexClient.createLibraryPlayQueue()
            mediaId == AutomotiveBrowseItems.SHUFFLE_ALL_ALBUMS_ID -> {
                val sectionId = plexClient.getLibrarySection()
                plexClient.createPlayQueue(
                    "library://$machineId/directory//library/sections/$sectionId/all?type=9"
                )
            }
            mediaId == AutomotiveBrowseItems.SHUFFLE_RECENTLY_ADDED_ID -> {
                val sectionId = plexClient.getLibrarySection()
                plexClient.createPlayQueue(
                    "library://$machineId/directory//library/sections/$sectionId/recentlyAdded?type=9"
                )
            }
            mediaId.startsWith(AutomotiveBrowseItems.SHUFFLE_ARTIST_PREFIX) -> {
                val key = mediaId.removePrefix(AutomotiveBrowseItems.SHUFFLE_ARTIST_PREFIX)
                plexClient.createPlayQueue("library://$machineId/item//library/metadata/$key")
            }
            mediaId.startsWith(AutomotiveBrowseItems.SHUFFLE_ALBUM_PREFIX) -> {
                val key = mediaId.removePrefix(AutomotiveBrowseItems.SHUFFLE_ALBUM_PREFIX)
                plexClient.createPlayQueue("library://$machineId/item//library/metadata/$key")
            }
            mediaId.startsWith(AutomotiveBrowseItems.SHUFFLE_PLAYLIST_PREFIX) -> {
                val key = mediaId.removePrefix(AutomotiveBrowseItems.SHUFFLE_PLAYLIST_PREFIX)
                plexClient.createPlayQueue("library://$machineId/directory//playlists/$key/items")
            }
            else -> null
        }
    }

    private fun applyPlaybackQueue(playQueueId: String, shuffle: Boolean) {
        currentPlayQueueId = playQueueId
        Handler(Looper.getMainLooper()).post {
            player.shuffleModeEnabled = shuffle
        }
    }

    private data class BrowsePage(val offset: Int, val limit: Int)

    private fun browsePage(page: Int, pageSize: Int, pageZeroPrefixCount: Int): BrowsePage {
        if (page == 0) {
            return BrowsePage(0, (pageSize - pageZeroPrefixCount).coerceAtLeast(0))
        }
        return BrowsePage(pageZeroPrefixCount + (page - 1) * pageSize, pageSize)
    }

    private fun contextSourceUri(trackKey: String, browseParentId: String?): String? {
        val machineId = plexClient.getMachineId()
        if (machineId.isEmpty()) return null
        when {
            browseParentId?.startsWith("playlist_") == true -> {
                val playlistKey = browseParentId.removePrefix("playlist_")
                return "library://$machineId/directory//playlists/$playlistKey/items"
            }
            browseParentId?.startsWith("album_") == true -> {
                val albumKey = browseParentId.removePrefix("album_")
                val track = plexClient.getTrack(trackKey)
                if (track == null || track.parentRatingKey.isEmpty() || track.parentRatingKey == albumKey) {
                    return "library://$machineId/item//library/metadata/$albumKey"
                }
            }
            else -> {
                val track = plexClient.getTrack(trackKey) ?: return null
                if (track.parentRatingKey.isNotEmpty()) {
                    return "library://$machineId/item//library/metadata/${track.parentRatingKey}"
                }
            }
        }
        return null
    }

    private fun resolveTrackQueue(
        trackKey: String,
        shuffle: Boolean
    ): MediaSession.MediaItemsWithStartPosition? {
        val sourceUri = contextSourceUri(trackKey, lastBrowseParentId) ?: return null
        val playQueue = plexClient.createPlayQueue(
            sourceUri,
            shuffle = shuffle,
            startRatingKey = trackKey
        ) ?: return null
        applyPlaybackQueue(playQueue.id, shuffle)
        val mediaItems = playQueue.tracks.map { createMediaItem(it) }
        val startIndex = playQueue.tracks.indexOfFirst { it.ratingKey == trackKey }.let { if (it >= 0) it else 0 }
        return MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, 0L)
    }

    private fun resolveSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): MediaSession.MediaItemsWithStartPosition {
        if (mediaItems.isEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(
                emptyList(),
                C.INDEX_UNSET,
                C.TIME_UNSET
            )
        }
        val first = mediaItems.first()
        when {
            first.mediaId.startsWith("shuffle::") -> {
                val playQueue = resolveShufflePlayQueue(first.mediaId)
                if (playQueue != null && playQueue.tracks.isNotEmpty()) {
                    applyPlaybackQueue(playQueue.id, shuffle = true)
                    return MediaSession.MediaItemsWithStartPosition(
                        playQueue.tracks.map { createMediaItem(it) },
                        0,
                        0L
                    )
                }
            }
            first.mediaId.startsWith("track_") -> {
                val trackKey = first.mediaId.removePrefix("track_")
                resolveTrackQueue(trackKey, shuffle = false)?.let { return it }
                plexClient.getTrack(trackKey)?.let { track ->
                    return MediaSession.MediaItemsWithStartPosition(
                        listOf(createMediaItem(track)),
                        0,
                        0L
                    )
                }
            }
            else -> {
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    when {
                        item.localConfiguration?.uri != null -> resolved.add(item)
                        item.mediaId.startsWith("track_") -> {
                            plexClient.getTrack(item.mediaId.removePrefix("track_"))?.let {
                                resolved.add(createMediaItem(it))
                            }
                        }
                        else -> resolved.add(item)
                    }
                }
                if (resolved.isNotEmpty()) {
                    return MediaSession.MediaItemsWithStartPosition(
                        resolved,
                        startIndex,
                        startPositionMs
                    )
                }
            }
        }
        return MediaSession.MediaItemsWithStartPosition(
            emptyList(),
            C.INDEX_UNSET,
            C.TIME_UNSET
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        savePlaybackState()
        unregisterReceiver(libraryStateReceiver)
        mediaLibrarySession.release()
        player.release()
        executor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshLibraryBrowsers() {
        mediaLibrarySession.notifyChildrenChanged(AutomotiveBrowseItems.ROOT_ID, 5, null)
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        @OptIn(UnstableApi::class)
        private fun authenticationRequiredParams(): LibraryParams {
            val signInIntent = Intent(this@PlexMediaLibraryService, SignInActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                this@PlexMediaLibraryService,
                0,
                signInIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val extras = Bundle().apply {
                putString(
                    MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                    "Sign in to Plex"
                )
                putParcelable(
                    MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                    pendingIntent
                )
                putParcelable(
                    MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT_COMPAT,
                    pendingIntent
                )
            }
            return LibraryParams.Builder().setExtras(extras).build()
        }

        @OptIn(UnstableApi::class)
        private fun authenticationRequiredChildrenResult(
            params: LibraryParams? = null
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return com.google.common.util.concurrent.Futures.immediateFuture(
                LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                    params ?: authenticationRequiredParams()
                )
            )
        }
        
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
                        when {
                            item.localConfiguration?.uri != null -> resolvedItems.add(item)
                            item.mediaId.startsWith("shuffle::") -> {
                                val playQueue = resolveShufflePlayQueue(item.mediaId)
                                if (playQueue != null && playQueue.tracks.isNotEmpty()) {
                                    applyPlaybackQueue(playQueue.id, shuffle = true)
                                    resolvedItems.addAll(playQueue.tracks.map { createMediaItem(it) })
                                }
                            }
                            item.mediaId.startsWith("track_") -> {
                                val ratingKey = item.mediaId.removePrefix("track_")
                                plexClient.getTrack(ratingKey)?.let {
                                    resolvedItems.add(createMediaItem(it))
                                }
                            }
                            else -> resolvedItems.add(item)
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
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            executor.submit {
                try {
                    future.set(resolveSetMediaItems(mediaItems, startIndex, startPositionMs))
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
            val availablePlayerCommands = connectionResult.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .build()
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                availablePlayerCommands
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
            // AAOS requires a valid root here. Authentication is handled in onGetChildren.
            val libraryParams = params ?: AutomotiveBrowseItems.rootLibraryParams()
            return SettableFuture.create<LibraryResult<MediaItem>>().apply {
                set(LibraryResult.ofItem(AutomotiveBrowseItems.rootItem(), libraryParams))
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
            if (!plexClient.isConfigured()) {
                return authenticationRequiredChildrenResult()
            }

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            executor.submit {
                try {
                    if (parentId.startsWith("album_") || parentId.startsWith("playlist_")) {
                        lastBrowseParentId = parentId
                    } else {
                        lastBrowseParentId = null
                    }

                    val items = mutableListOf<MediaItem>()
                    when (parentId) {
                        AutomotiveBrowseItems.ROOT_ID -> {
                            items.add(AutomotiveBrowseItems.shuffleItem("Shuffle Library"))
                            items.add(
                                AutomotiveBrowseItems.folderItem(
                                    AutomotiveBrowseItems.ARTISTS_ID,
                                    "Artists",
                                    MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                    MediaMetadata.FOLDER_TYPE_ARTISTS
                                )
                            )
                            items.add(
                                AutomotiveBrowseItems.folderItem(
                                    AutomotiveBrowseItems.ALBUMS_ID,
                                    "Albums",
                                    MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                    MediaMetadata.FOLDER_TYPE_ALBUMS
                                )
                            )
                            items.add(
                                AutomotiveBrowseItems.folderItem(
                                    AutomotiveBrowseItems.RECENT_ID,
                                    "Recently Added",
                                    MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                    MediaMetadata.FOLDER_TYPE_ALBUMS
                                )
                            )
                            items.add(
                                AutomotiveBrowseItems.folderItem(
                                    AutomotiveBrowseItems.PLAYLISTS_ID,
                                    "Playlists",
                                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                    MediaMetadata.FOLDER_TYPE_PLAYLISTS
                                )
                            )
                        }
                        AutomotiveBrowseItems.ARTISTS_ID -> {
                            val browse = browsePage(page, pageSize, pageZeroPrefixCount = 0)
                            plexClient.getArtistsPaged(browse.offset, browse.limit).items.forEach {
                                items.add(AutomotiveBrowseItems.artistItem(this@PlexMediaLibraryService, it))
                            }
                        }
                        AutomotiveBrowseItems.ALBUMS_ID -> {
                            val prefixCount = 1
                            if (page == 0) {
                                items.add(
                                    AutomotiveBrowseItems.contextualShuffleItem(
                                        AutomotiveBrowseItems.SHUFFLE_ALL_ALBUMS_ID,
                                        "Shuffle Albums"
                                    )
                                )
                            }
                            val browse = browsePage(page, pageSize, pageZeroPrefixCount = prefixCount)
                            plexClient.getAllAlbumsPaged(browse.offset, browse.limit).items.forEach {
                                items.add(AutomotiveBrowseItems.albumItem(this@PlexMediaLibraryService, it))
                            }
                        }
                        AutomotiveBrowseItems.RECENT_ID -> {
                            val prefixCount = 1
                            if (page == 0) {
                                items.add(
                                    AutomotiveBrowseItems.contextualShuffleItem(
                                        AutomotiveBrowseItems.SHUFFLE_RECENTLY_ADDED_ID,
                                        "Shuffle Recently Added"
                                    )
                                )
                            }
                            val browse = browsePage(page, pageSize, pageZeroPrefixCount = prefixCount)
                            plexClient.getRecentlyAddedAlbumsPaged(browse.offset, browse.limit).items.forEach {
                                items.add(AutomotiveBrowseItems.albumItem(this@PlexMediaLibraryService, it))
                            }
                        }
                        AutomotiveBrowseItems.PLAYLISTS_ID -> {
                            val browse = browsePage(page, pageSize, pageZeroPrefixCount = 0)
                            plexClient.getPlaylistsPaged(browse.offset, browse.limit).items.forEach {
                                items.add(AutomotiveBrowseItems.playlistItem(this@PlexMediaLibraryService, it))
                            }
                        }
                        else -> when {
                            parentId.startsWith("playlist_") -> {
                                val playlistRatingKey = parentId.removePrefix("playlist_")
                                val prefixCount = 1
                                if (page == 0) {
                                    items.add(
                                        AutomotiveBrowseItems.contextualShuffleItem(
                                            "${AutomotiveBrowseItems.SHUFFLE_PLAYLIST_PREFIX}$playlistRatingKey"
                                        )
                                    )
                                }
                                val browse = browsePage(page, pageSize, pageZeroPrefixCount = prefixCount)
                                plexClient.getPlaylistTracks(playlistRatingKey, browse.offset, browse.limit)
                                    .tracks.forEach { items.add(createMediaItem(it)) }
                            }
                            parentId.startsWith("artist_") -> {
                                val artistRatingKey = parentId.removePrefix("artist_")
                                val prefixCount = 1
                                if (page == 0) {
                                    items.add(
                                        AutomotiveBrowseItems.contextualShuffleItem(
                                            "${AutomotiveBrowseItems.SHUFFLE_ARTIST_PREFIX}$artistRatingKey"
                                        )
                                    )
                                }
                                val browse = browsePage(page, pageSize, pageZeroPrefixCount = prefixCount)
                                plexClient.getAlbumsPaged(artistRatingKey, browse.offset, browse.limit).items.forEach {
                                    items.add(AutomotiveBrowseItems.albumItem(this@PlexMediaLibraryService, it))
                                }
                            }
                            parentId.startsWith("album_") -> {
                                val albumRatingKey = parentId.removePrefix("album_")
                                val prefixCount = 1
                                if (page == 0) {
                                    items.add(
                                        AutomotiveBrowseItems.contextualShuffleItem(
                                            "${AutomotiveBrowseItems.SHUFFLE_ALBUM_PREFIX}$albumRatingKey"
                                        )
                                    )
                                }
                                val browse = browsePage(page, pageSize, pageZeroPrefixCount = prefixCount)
                                plexClient.getTracks(albumRatingKey, browse.offset, browse.limit)
                                    .tracks.forEach { items.add(createMediaItem(it)) }
                            }
                        }
                    }
                    
                    val result = LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    future.set(result)
                } catch (e: Exception) {
                    android.util.Log.e("PlexMediaService", "Failed to load children for $parentId", e)
                    future.set(
                        LibraryResult.ofError(
                            LibraryResult.RESULT_ERROR_IO,
                            params
                        )
                    )
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
            if (!plexClient.isConfigured()) {
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    LibraryResult.ofError(
                        LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        authenticationRequiredParams()
                    )
                )
            }

            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            executor.submit {
                try {
                    val item = when {
                        mediaId == AutomotiveBrowseItems.ROOT_ID ->
                            AutomotiveBrowseItems.rootItem()
                        mediaId == AutomotiveBrowseItems.ARTISTS_ID ->
                            AutomotiveBrowseItems.folderItem(
                                AutomotiveBrowseItems.ARTISTS_ID,
                                "Artists",
                                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                MediaMetadata.FOLDER_TYPE_ARTISTS
                            )
                        mediaId == AutomotiveBrowseItems.ALBUMS_ID ->
                            AutomotiveBrowseItems.folderItem(
                                AutomotiveBrowseItems.ALBUMS_ID,
                                "Albums",
                                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                MediaMetadata.FOLDER_TYPE_ALBUMS
                            )
                        mediaId == AutomotiveBrowseItems.RECENT_ID ->
                            AutomotiveBrowseItems.folderItem(
                                AutomotiveBrowseItems.RECENT_ID,
                                "Recently Added",
                                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                MediaMetadata.FOLDER_TYPE_ALBUMS
                            )
                        mediaId == AutomotiveBrowseItems.PLAYLISTS_ID ->
                            AutomotiveBrowseItems.folderItem(
                                AutomotiveBrowseItems.PLAYLISTS_ID,
                                "Playlists",
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                MediaMetadata.FOLDER_TYPE_PLAYLISTS
                            )
                        mediaId == AutomotiveBrowseItems.SHUFFLE_LIBRARY_ID ->
                            AutomotiveBrowseItems.shuffleItem("Shuffle Library")
                        mediaId == AutomotiveBrowseItems.SHUFFLE_ALL_ALBUMS_ID ->
                            AutomotiveBrowseItems.contextualShuffleItem(mediaId, "Shuffle Albums")
                        mediaId == AutomotiveBrowseItems.SHUFFLE_RECENTLY_ADDED_ID ->
                            AutomotiveBrowseItems.contextualShuffleItem(mediaId, "Shuffle Recently Added")
                        mediaId.startsWith(AutomotiveBrowseItems.SHUFFLE_ARTIST_PREFIX) ||
                            mediaId.startsWith(AutomotiveBrowseItems.SHUFFLE_ALBUM_PREFIX) ||
                            mediaId.startsWith(AutomotiveBrowseItems.SHUFFLE_PLAYLIST_PREFIX) ->
                            AutomotiveBrowseItems.contextualShuffleItem(mediaId)
                        mediaId.startsWith("artist_") -> {
                            val ratingKey = mediaId.removePrefix("artist_")
                            plexClient.getArtist(ratingKey)?.let { AutomotiveBrowseItems.artistItem(this@PlexMediaLibraryService, it) }
                        }
                        mediaId.startsWith("album_") -> {
                            val ratingKey = mediaId.removePrefix("album_")
                            plexClient.getAlbum(ratingKey)?.let { AutomotiveBrowseItems.albumItem(this@PlexMediaLibraryService, it) }
                        }
                        mediaId.startsWith("playlist_") -> {
                            val ratingKey = mediaId.removePrefix("playlist_")
                            plexClient.getPlaylist(ratingKey)?.let { AutomotiveBrowseItems.playlistItem(this@PlexMediaLibraryService, it) }
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
                    android.util.Log.e("PlexMediaService", "Failed to load item $mediaId", e)
                    future.set(
                        LibraryResult.ofError(
                            LibraryResult.RESULT_ERROR_IO,
                            null
                        )
                    )
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
            if (!plexClient.isConfigured()) {
                return authenticationRequiredChildrenResult()
            }

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            executor.submit {
                try {
                    lastBrowseParentId = null
                    val items = mutableListOf<MediaItem>()

                    plexClient.searchArtists(query).forEach { artist ->
                        items.add(AutomotiveBrowseItems.artistItem(this@PlexMediaLibraryService, artist))
                    }

                    plexClient.searchAlbums(query).forEach { album ->
                        items.add(AutomotiveBrowseItems.albumItem(this@PlexMediaLibraryService, album))
                    }

                    // Search Tracks
                    plexClient.searchTracks(query).forEach { track ->
                        items.add(createMediaItem(track))
                    }

                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    android.util.Log.e("PlexMediaService", "Search failed for $query", e)
                    future.set(
                        LibraryResult.ofError(
                            LibraryResult.RESULT_ERROR_IO,
                            params
                        )
                    )
                }
            }
            return future
        }
    }
}
