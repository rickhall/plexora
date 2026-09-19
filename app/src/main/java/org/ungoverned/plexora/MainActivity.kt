package org.ungoverned.plexora

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Navigation Screens
sealed class Screen {
    object Setup : Screen()
    object Artists : Screen()
    object AllAlbums : Screen()
    object RecentlyAdded : Screen()
    object Playlists : Screen()
    object Settings : Screen()
    data class ArtistAlbums(val artist: PlexArtist) : Screen()
    data class AlbumTracks(val album: PlexAlbum) : Screen()
    data class PlaylistTracks(val playlist: PlexPlaylist) : Screen()
}

private fun Screen.isDetailDestination(): Boolean = when (this) {
    is Screen.ArtistAlbums, is Screen.AlbumTracks, is Screen.PlaylistTracks -> true
    else -> false
}

// ViewModel to manage connection and MediaController binding
class PlexoraViewModel : ViewModel() {
    private val tag = "PlexoraVM"

    private val _uiState = MutableStateFlow<PlexUiState>(PlexUiState.Loading)
    val uiState: StateFlow<PlexUiState> = _uiState.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.Setup)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val navigationBackStack = ArrayDeque<Screen>()

    private val _mediaController = MutableStateFlow<MediaController?>(null)
    val mediaController: StateFlow<MediaController?> = _mediaController.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0L)
    val playbackProgress: StateFlow<Long> = _playbackProgress.asStateFlow()

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _currentTrack = MutableStateFlow<MediaItem?>(null)
    val currentTrack: StateFlow<MediaItem?> = _currentTrack.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var plexClient: PlexClient

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            _mediaController.value?.let { controller ->
                if (controller.isPlaying) {
                    _playbackProgress.value = controller.currentPosition
                    _playbackDuration.value = controller.duration.coerceAtLeast(0L)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun init(context: Context) {
        plexClient = PlexClient(context)
        if (plexClient.isConfigured()) {
            _currentScreen.value = Screen.Artists
            loadArtists()
        } else {
            _uiState.value = PlexUiState.ConfigNeeded
        }
        connectController(context.applicationContext)
        handler.post(progressUpdater)
    }

    fun initForAutomotiveSignIn(context: Context) {
        plexClient = PlexClient(context)
        _currentScreen.value = Screen.Setup
        _uiState.value = PlexUiState.ConfigNeeded
        connectController(context.applicationContext)
        handler.post(progressUpdater)
    }

    fun initForAutomotiveSettings(context: Context) {
        plexClient = PlexClient(context)
        _currentScreen.value = Screen.Settings
        _uiState.value = if (plexClient.isConfigured()) {
            PlexUiState.ArtistsList(emptyList())
        } else {
            PlexUiState.ConfigNeeded
        }
        connectController(context.applicationContext)
        handler.post(progressUpdater)
    }

    private fun connectController(context: Context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlexMediaLibraryService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                _mediaController.value = controller
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }

                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                        _shuffleModeEnabled.value = shuffleModeEnabled
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _currentTrack.value = mediaItem
                        _playbackProgress.value = 0L
                        _playbackDuration.value = controller.duration.coerceAtLeast(0L)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            _playbackDuration.value = controller.duration.coerceAtLeast(0L)
                        }
                    }
                })
                // Sync initial states
                _isPlaying.value = controller?.isPlaying ?: false
                _shuffleModeEnabled.value = controller?.shuffleModeEnabled ?: false
                _currentTrack.value = controller?.currentMediaItem
                _playbackProgress.value = controller?.currentPosition ?: 0L
                _playbackDuration.value = controller?.duration?.coerceAtLeast(0L) ?: 0L
            } catch (e: Exception) {
                Log.e(tag, "Failed to connect MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    fun toggleShuffle() {
        val controller = _mediaController.value ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    fun saveConfig(context: Context, url: String, token: String, sectionId: String) {
        plexClient.saveConfig(url, token, sectionId) // Note: MachineId already saved during server selection
        clearNavigationBackStack()
        _currentScreen.value = Screen.Artists
        loadArtists()
    }

    fun loadArtists() {
        _uiState.value = PlexUiState.Loading
        clearNavigationBackStack()
        _currentScreen.value = Screen.Artists
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = plexClient.getArtists()
                _uiState.value = PlexUiState.ArtistsList(list)
            } catch (e: Exception) {
                _uiState.value = PlexUiState.Error("Failed to fetch artists: ${e.message}")
            }
        }
    }

    fun loadAllAlbums() {
        _uiState.value = PlexUiState.Loading
        clearNavigationBackStack()
        _currentScreen.value = Screen.AllAlbums
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = plexClient.getAllAlbums()
                _uiState.value = PlexUiState.AlbumsList(list, "All Albums")
            } catch (e: Exception) {
                _uiState.value = PlexUiState.Error("Failed to fetch albums: ${e.message}")
            }
        }
    }

    fun loadRecentlyAdded() {
        _uiState.value = PlexUiState.Loading
        clearNavigationBackStack()
        _currentScreen.value = Screen.RecentlyAdded
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = plexClient.getRecentlyAddedAlbums()
                _uiState.value = PlexUiState.AlbumsList(list, "Recently Added")
            } catch (e: Exception) {
                _uiState.value = PlexUiState.Error("Failed to fetch recent: ${e.message}")
            }
        }
    }

    fun loadPlaylists() {
        _uiState.value = PlexUiState.Loading
        clearNavigationBackStack()
        _currentScreen.value = Screen.Playlists
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = plexClient.getPlaylists()
                _uiState.value = PlexUiState.PlaylistsList(list)
            } catch (e: Exception) {
                _uiState.value = PlexUiState.Error("Failed to fetch playlists: ${e.message}")
            }
        }
    }

    fun navigateTo(screen: Screen) {
        if (screen.isDetailDestination()) {
            navigationBackStack.addLast(_currentScreen.value)
        } else {
            clearNavigationBackStack()
        }
        _currentScreen.value = screen
    }

    fun canNavigateBack(): Boolean = navigationBackStack.isNotEmpty()

    fun navigateBack() {
        if (navigationBackStack.isEmpty()) return
        _currentScreen.value = navigationBackStack.removeLast()
    }

    private fun clearNavigationBackStack() {
        navigationBackStack.clear()
    }

    fun stopPlayback() {
        _mediaController.value?.let { controller ->
            controller.stop()
            controller.clearMediaItems()
        }
        _currentTrack.value = null
    }

    fun logout(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Tell the player to stop immediately
            withContext(Dispatchers.Main) {
                _mediaController.value?.let { controller ->
                    controller.stop()
                    controller.clearMediaItems()
                }
                _currentTrack.value = null
            }

            // 2. Revoke token on server and clear local prefs
            plexClient.signOut()

            // 3. Reset UI state
            withContext(Dispatchers.Main) {
                clearNavigationBackStack()
                _currentScreen.value = Screen.Setup
                _uiState.value = PlexUiState.ConfigNeeded
            }
        }
    }

    fun shuffleAndPlayArtist(artist: PlexArtist) {
        val machineId = plexClient.getMachineId()
        if (machineId.isEmpty()) return
        val sourceUri = "library://$machineId/item//library/metadata/${artist.ratingKey}"
        shuffleQueue(sourceUri)
    }

    fun shuffleAlbum(album: PlexAlbum) {
        val machineId = plexClient.getMachineId()
        if (machineId.isEmpty()) return
        val uri = "library://$machineId/item//library/metadata/${album.ratingKey}"
        shuffleQueue(uri)
    }

    fun shufflePlaylist(playlist: PlexPlaylist) {
        val machineId = plexClient.getMachineId()
        if (machineId.isEmpty()) return

        /**
         * PLEX API NOTE: For playlists, we MUST use the 'directory' URI prefix rather than 'item'.
         * 'item' works for Artists/Albums (Metadata tree), but Playlists are containers that
         * require 'directory' to correctly resolve and shuffle the child tracks.
         */
        val uri = "library://$machineId/directory//playlists/${playlist.ratingKey}/items"
        shuffleQueue(uri)
    }

    fun shuffleLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val playQueue = plexClient.createLibraryPlayQueue()
                if (playQueue != null && playQueue.tracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playTracks(playQueue.tracks, 0, shuffle = true, playQueueId = playQueue.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to shuffle library", e)
            }
        }
    }

    fun shuffleQueue(sourceUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val playQueue = plexClient.createPlayQueue(sourceUri, shuffle = true)
                if (playQueue != null && playQueue.tracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playTracks(playQueue.tracks, 0, shuffle = true, playQueueId = playQueue.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to create shuffle queue", e)
            }
        }
    }

    fun clearAppCaches(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Clear Metadata Cache
            plexClient.clearCaches()

            // 2. Clear Coil Caches (Image Cache)
            val imageLoader = coil.Coil.imageLoader(context)
            imageLoader.diskCache?.clear()
            imageLoader.memoryCache?.clear()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun playTracks(tracks: List<PlexTrack>, startIndex: Int, shuffle: Boolean = false, playQueueId: String? = null) {
        val controller = _mediaController.value ?: return

        controller.shuffleModeEnabled = shuffle
        // If we have a server-side queue ID, pass it to the service for persistence
        if (playQueueId != null) {
            val bundle = android.os.Bundle()
            bundle.putString("play_queue_id", playQueueId)
            controller.sendCustomCommand(androidx.media3.session.SessionCommand("SET_PLAY_QUEUE_ID", bundle), bundle)
        }

        val listToPlay = if (shuffle && playQueueId == null) tracks.shuffled() else tracks

        val mediaItems = listToPlay.map { track ->
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
        }

        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    override fun onCleared() {
        handler.removeCallbacks(progressUpdater)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}

sealed class PlexUiState {
    object Loading : PlexUiState()
    object ConfigNeeded : PlexUiState()
    data class ArtistsList(val artists: List<PlexArtist>) : PlexUiState()
    data class AlbumsList(val albums: List<PlexAlbum>, val title: String) : PlexUiState()
    data class PlaylistsList(val playlists: List<PlexPlaylist>) : PlexUiState()
    data class Error(val message: String) : PlexUiState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFE5A93B), // Plex Gold
                    background = Color(0xFF121214),
                    surface = Color(0xFF1E1E24)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: PlexoraViewModel = viewModel()
                    LaunchedEffect(Unit) {
                        viewModel.init(this@MainActivity)
                    }
                    MainScreenContent(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(viewModel: PlexoraViewModel) {
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val shuffleModeEnabled by viewModel.shuffleModeEnabled.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val duration by viewModel.playbackDuration.collectAsStateWithLifecycle()
    val controller by viewModel.mediaController.collectAsStateWithLifecycle()

    val artistsGridState = rememberLazyGridState()
    val allAlbumsGridState = rememberLazyGridState()
    val recentlyAddedGridState = rememberLazyGridState()
    val playlistsGridState = rememberLazyGridState()
    val artistAlbumsGridState = rememberLazyGridState()
    val albumTracksListState = rememberLazyListState()
    val playlistTracksListState = rememberLazyListState()

    var showFullPlayer by remember { mutableStateOf(false) }

    BackHandler(enabled = showFullPlayer || viewModel.canNavigateBack()) {
        if (showFullPlayer) {
            showFullPlayer = false
        } else {
            viewModel.navigateBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Navigation Rail
            if (currentScreen != Screen.Setup) {
                NavigationRail(
                    containerColor = Color(0xFF1E1E24),
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = currentScreen is Screen.Artists,
                        onClick = { viewModel.loadArtists() },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text("Artists") }
                    )
                    NavigationRailItem(
                        selected = currentScreen is Screen.AllAlbums,
                        onClick = { viewModel.loadAllAlbums() },
                        icon = { Icon(Icons.Default.Album, contentDescription = null) },
                        label = { Text("Albums") }
                    )
                    NavigationRailItem(
                        selected = currentScreen is Screen.RecentlyAdded,
                        onClick = { viewModel.loadRecentlyAdded() },
                        icon = { Icon(Icons.Default.NewReleases, contentDescription = null) },
                        label = { Text("Recent") }
                    )
                    NavigationRailItem(
                        selected = currentScreen is Screen.Playlists,
                        onClick = { viewModel.loadPlaylists() },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("Playlists") }
                    )
                    NavigationRailItem(
                        selected = currentScreen is Screen.Settings,
                        onClick = { viewModel.navigateTo(Screen.Settings) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                    Spacer(Modifier.weight(1f))
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Main Top Bar (hidden during Plex linking so setup uses the full content area)
                if (currentScreen != Screen.Setup) {
                TopAppBar(
                    title = {
                        Text(
                            "PLEXORA",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFE5A93B),
                            letterSpacing = 2.sp
                        )
                    },
                    actions = {
                        if (currentScreen != Screen.Settings) {
                            IconButton(onClick = {
                                when (val screen = currentScreen) {
                                    is Screen.ArtistAlbums -> viewModel.shuffleAndPlayArtist(screen.artist)
                                    is Screen.AlbumTracks -> viewModel.shuffleAlbum(screen.album)
                                    is Screen.PlaylistTracks -> viewModel.shufflePlaylist(screen.playlist)
                                    else -> viewModel.shuffleLibrary()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF121214)
                    )
                )
                }

                // Screen Selector
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    when (currentScreen) {
                        is Screen.Setup -> SetupScreen(
                            viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                        is Screen.Artists -> {
                            when (val state = uiState) {
                                is PlexUiState.Loading -> CircularLoading()
                                is PlexUiState.ArtistsList -> ArtistsGrid(state.artists, state = artistsGridState) { artist ->
                                    viewModel.navigateTo(Screen.ArtistAlbums(artist))
                                }
                                is PlexUiState.Error -> ErrorView(state.message) { viewModel.loadArtists() }
                                else -> {}
                            }
                        }
                        is Screen.AllAlbums -> {
                            when (val state = uiState) {
                                is PlexUiState.Loading -> CircularLoading()
                                is PlexUiState.AlbumsList -> AlbumsGrid(state.albums, state.title, state = allAlbumsGridState) { album ->
                                    viewModel.navigateTo(Screen.AlbumTracks(album))
                                }
                                is PlexUiState.Error -> ErrorView(state.message) { viewModel.loadAllAlbums() }
                                else -> {}
                            }
                        }
                        is Screen.RecentlyAdded -> {
                            when (val state = uiState) {
                                is PlexUiState.Loading -> CircularLoading()
                                is PlexUiState.AlbumsList -> AlbumsGrid(state.albums, state.title, state = recentlyAddedGridState) { album ->
                                    viewModel.navigateTo(Screen.AlbumTracks(album))
                                }
                                is PlexUiState.Error -> ErrorView(state.message) { viewModel.loadRecentlyAdded() }
                                else -> {}
                            }
                        }
                        is Screen.Playlists -> {
                            when (val state = uiState) {
                                is PlexUiState.Loading -> CircularLoading()
                                is PlexUiState.PlaylistsList -> PlaylistsGrid(state.playlists, state = playlistsGridState) { playlist ->
                                    viewModel.navigateTo(Screen.PlaylistTracks(playlist))
                                }
                                is PlexUiState.Error -> ErrorView(state.message) { viewModel.loadPlaylists() }
                                else -> {}
                            }
                        }
                        is Screen.ArtistAlbums -> {
                            val artist = (currentScreen as Screen.ArtistAlbums).artist
                            AlbumsScreen(artist, viewModel, state = artistAlbumsGridState)
                        }
                        is Screen.AlbumTracks -> {
                            val state = currentScreen as Screen.AlbumTracks
                            TracksScreen(state.album, viewModel, state = albumTracksListState)
                        }
                        is Screen.PlaylistTracks -> {
                            val playlist = (currentScreen as Screen.PlaylistTracks).playlist
                            PlaylistTracksScreen(playlist, viewModel, state = playlistTracksListState)
                        }
                        is Screen.Settings -> SettingsScreen(viewModel)
                    }
                }

                // Bottom Mini Player
                if (currentScreen != Screen.Setup && currentTrack != null) {
                    MiniPlayer(
                        mediaItem = currentTrack!!,
                        isPlaying = isPlaying,
                        onPlayPause = {
                            if (isPlaying) controller?.pause() else controller?.play()
                        },
                        onClear = {
                            if (!isPlaying) viewModel.stopPlayback()
                            else controller?.pause()
                        },
                        onClick = { showFullPlayer = true }
                    )
                }
            }
        }

        if (showFullPlayer && currentTrack != null) {
            FullPlayerScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f),
                mediaItem = currentTrack!!,
                isPlaying = isPlaying,
                shuffleModeEnabled = shuffleModeEnabled,
                progress = progress,
                duration = duration,
                onPlayPause = { if (isPlaying) controller?.pause() else controller?.play() },
                onNext = { controller?.seekToNext() },
                onPrevious = { controller?.seekToPrevious() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onSeek = { pos -> controller?.seekTo(pos) },
                onClose = { showFullPlayer = false }
            )
        }
    }
}

@Composable
fun SetupProgressStep(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = Color(0xFFFFE5A93B)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            subtitle,
            color = Color.Gray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: PlexoraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var sectionId by remember { mutableStateOf("") }
    var sectionsList by remember { mutableStateOf<List<PlexSection>>(emptyList()) }
    var serversList by remember { mutableStateOf<List<PlexServer>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // PIN Auth State
    var pinResponse by remember { mutableStateOf<PlexPinResponse?>(null) }
    var isFetchingServers by remember { mutableStateOf(false) }
    var isConnectingToServer by remember { mutableStateOf(false) }
    var connectingServerName by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Polling logic for PIN (5-minute timeout matches Plex PIN expiry)
    LaunchedEffect(pinResponse?.id) {
        val pin = pinResponse ?: return@LaunchedEffect
        if (pin.authToken != null) return@LaunchedEffect

        val tempClient = PlexClient(context)
        val deadline = System.currentTimeMillis() + PIN_LINK_TIMEOUT_MS
        var linkedToken: String? = null

        while (linkedToken == null) {
            if (System.currentTimeMillis() >= deadline) {
                pinResponse = null
                errorMessage = "Linking timed out. Please try again."
                return@LaunchedEffect
            }
            delay(PIN_POLL_INTERVAL_MS)
            linkedToken = withContext(Dispatchers.IO) { tempClient.checkPin(pin.id) }
        }

        // Fetch on scope that survives clearing pinResponse (LaunchedEffect key would cancel otherwise).
        val authToken = linkedToken
        coroutineScope.launch {
            pinResponse = null
            isFetchingServers = true
            errorMessage = null
            try {
                val list = withContext(Dispatchers.IO) { tempClient.getServersFromPlexTv(authToken) }
                token = authToken
                if (list.isNotEmpty()) {
                    serversList = list
                } else {
                    errorMessage = "No servers found for this account."
                }
            } catch (e: Exception) {
                errorMessage = "Could not reach Plex. Check your connection and try again."
            } finally {
                isFetchingServers = false
            }
        }
    }

    val isCompactSetupStep = pinResponse != null ||
        isFetchingServers ||
        isConnectingToServer ||
        (serversList.isEmpty() && sectionsList.isEmpty())

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        val setupPanelModifier = Modifier
            .align(Alignment.Center)
            .fillMaxHeight()
            .widthIn(max = 480.dp)
            .fillMaxWidth()

        if (isCompactSetupStep) {
            Column(
                modifier = setupPanelModifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Connect your Plex Server",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(24.dp))

        if (pinResponse != null) {
            Text("Go to plex.tv/link and enter code:", color = Color.Gray, fontSize = 16.sp)
            Text(pinResponse!!.code, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFE5A93B))
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFFFFE5A93B))
            Text("Waiting for link...", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))

            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { pinResponse = null }) {
                Text("Cancel", color = Color.Red)
            }
        } else if (isFetchingServers) {
            SetupProgressStep(
                title = "Contacting Plex",
                subtitle = "Finding Plex servers on your account..."
            )
        } else if (isConnectingToServer) {
            SetupProgressStep(
                title = "Connecting to server",
                subtitle = connectingServerName?.let { "Checking $it for music libraries..." }
                    ?: "Checking your Plex server..."
            )
        } else if (serversList.isEmpty() && sectionsList.isEmpty()) {
            Button(
                onClick = {
                    Log.d("MainActivity", "Link with Plex button clicked")
                    errorMessage = null
                    coroutineScope.launch(Dispatchers.IO) {
                        Log.d("MainActivity", "Link with Plex coroutine started")
                        val tempClient = PlexClient(context)
                        val resp = tempClient.generatePin()
                        withContext(Dispatchers.Main) {
                            if (resp != null) {
                                pinResponse = resp
                            } else {
                                errorMessage = "Failed to start linking process. Check internet."
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Link with Plex.tv")
            }
        }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(it, color = Color.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        } else if (serversList.isNotEmpty() && sectionsList.isEmpty()) {
            Column(
                modifier = setupPanelModifier,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Connect your Plex Server",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Select a Server:",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                items(serversList.size) { index ->
                    val server = serversList[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isConnectingToServer) return@clickable
                                errorMessage = null
                                connectingServerName = server.name
                                isConnectingToServer = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val tempClient = PlexClient(context)
                                    val bestUri = tempClient.findBestConnection(server)
                                    if (bestUri == null) {
                                        withContext(Dispatchers.Main) {
                                            isConnectingToServer = false
                                            connectingServerName = null
                                            errorMessage =
                                                "Could not connect to ${server.name}. Check if it is online."
                                        }
                                        return@launch
                                    }
                                    tempClient.saveConfig(bestUri, token, "", machineId = server.id)
                                    val list = tempClient.getMusicSections()
                                    withContext(Dispatchers.Main) {
                                        isConnectingToServer = false
                                        connectingServerName = null
                                        url = bestUri
                                        if (list.isNotEmpty()) {
                                            sectionsList = list
                                            sectionId = list.first().id
                                        } else {
                                            errorMessage =
                                                "Could not find Music libraries on ${server.name}."
                                            tempClient.clearConfig()
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = Color(0xFFFFE5A93B),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(server.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${server.connections.size} connection paths available", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { serversList = emptyList() }) {
                    Text("Back", color = Color(0xFFFFE5A93B))
                }
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = Color.Red, fontSize = 14.sp)
                }
            }
        } else {
            Column(
                modifier = setupPanelModifier,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Connect your Plex Server",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Select Music Section:",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    sectionsList.forEach { section ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sectionId = section.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (sectionId == section.id),
                                onClick = { sectionId = section.id }
                            )
                            Text(section.title, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.saveConfig(context, url, token, sectionId)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect and Import")
                }
                TextButton(onClick = {
                    sectionsList = emptyList()
                    errorMessage = null
                }) {
                    Text("Back", color = Color(0xFFFFE5A93B))
                }
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = Color.Red, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun browseLibraryGridColumns(): GridCells {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return if (widthDp < 600) {
        // Phones (portrait or landscape): nav rail + padding often leaves <320dp for one 160dp tile.
        GridCells.Fixed(2)
    } else {
        // Tablets and wide layouts: fit as many columns as space allows.
        GridCells.Adaptive(minSize = 160.dp)
    }
}

@Composable
fun ArtistsGrid(artists: List<PlexArtist>, state: LazyGridState = rememberLazyGridState(), onClick: (PlexArtist) -> Unit) {
    if (artists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No artists found.", color = Color.LightGray)
        }
        return
    }

    LazyVerticalGrid(
        columns = browseLibraryGridColumns(),
        contentPadding = PaddingValues(12.dp),
        state = state,
        modifier = Modifier.fillMaxSize()
    ) {
        items(artists, key = { it.ratingKey }) { artist ->
            Card(
                modifier = Modifier
                    .padding(4.dp)
                    .clickable { onClick(artist) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = artist.thumbUrl,
                        contentDescription = artist.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        text = artist.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumsGrid(
    albums: List<PlexAlbum>,
    title: String? = null,
    state: LazyGridState = rememberLazyGridState(),
    onClick: (PlexAlbum) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (title != null) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
        }
        if (albums.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No albums found.", color = Color.LightGray)
            }
        } else {
            LazyVerticalGrid(
                columns = browseLibraryGridColumns(),
                contentPadding = PaddingValues(12.dp),
                state = state,
                modifier = Modifier.weight(1f)
            ) {
                items(albums, key = { it.ratingKey }) { album ->
                    Card(
                        modifier = Modifier
                            .padding(4.dp)
                            .clickable { onClick(album) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = album.thumbUrl,
                                contentDescription = album.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Text(
                                text = album.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
                            )
                            Text(
                                text = album.artistTitle,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsGrid(playlists: List<PlexPlaylist>, state: LazyGridState = rememberLazyGridState(), onClick: (PlexPlaylist) -> Unit) {
    if (playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No playlists found.", color = Color.LightGray)
        }
        return
    }

    LazyVerticalGrid(
        columns = browseLibraryGridColumns(),
        contentPadding = PaddingValues(12.dp),
        state = state,
        modifier = Modifier.fillMaxSize()
    ) {
        items(playlists, key = { it.ratingKey }) { playlist ->
            Card(
                modifier = Modifier
                    .padding(4.dp)
                    .clickable { onClick(playlist) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlaylistGridCover(
                        playlist = playlist,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Text(
                        text = playlist.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(artist: PlexArtist, viewModel: PlexoraViewModel, state: LazyGridState = rememberLazyGridState()) {
    val context = LocalContext.current
    var albums by remember { mutableStateOf<List<PlexAlbum>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(artist.ratingKey) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val client = PlexClient(context)
            val list = client.getAlbums(artist.ratingKey)
            withContext(Dispatchers.Main) {
                albums = list
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.navigateBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(artist.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        if (isLoading) {
            CircularLoading()
        } else {
            AlbumsGrid(albums = albums, state = state) { album ->
                viewModel.navigateTo(Screen.AlbumTracks(album))
            }
        }
    }
}

@Composable
fun PlaylistTracksScreen(playlist: PlexPlaylist, viewModel: PlexoraViewModel, state: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    var tracks by remember { mutableStateOf<List<PlexTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalTracks by remember { mutableStateOf(0) }
    var isFetchingMore by remember { mutableStateOf(false) }

    fun loadMore() {
        if (isFetchingMore || tracks.size >= totalTracks) return
        isFetchingMore = true
        CoroutineScope(Dispatchers.IO).launch {
            val response = PlexClient(context).getPlaylistTracks(playlist.ratingKey, offset = tracks.size)
            withContext(Dispatchers.Main) {
                tracks = tracks + response.tracks
                isFetchingMore = false
            }
        }
    }

    LaunchedEffect(playlist.ratingKey) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val client = PlexClient(context)
            val response = client.getPlaylistTracks(playlist.ratingKey)
            withContext(Dispatchers.Main) {
                tracks = response.tracks
                totalTracks = response.totalSize
                isLoading = false
            }
        }
    }

    // Monitor scroll for infinite scroll
    val firstVisibleItemPlaylist by remember { derivedStateOf { state.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemPlaylist) {
        if (tracks.isNotEmpty() && firstVisibleItemPlaylist > tracks.size - 20) {
            loadMore()
        }
    }

    TracksListContent(
        title = playlist.title,
        tracks = tracks,
        isLoading = isLoading,
        state = state,
        showOrdinalNumber = true,
        onBack = { viewModel.navigateBack() },
        onTrackClick = { list, index ->
            viewModel.playTracks(list, index)
        }
    )
}

@Composable
fun TracksListContent(
    title: String,
    tracks: List<PlexTrack>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onTrackClick: (List<PlexTrack>, Int) -> Unit,
    state: LazyListState = rememberLazyListState(),
    showOrdinalNumber: Boolean = false
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        if (isLoading) {
            CircularLoading()
        } else if (tracks.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No tracks found.", color = Color.LightGray)
            }
        } else {
            LazyColumn(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                items(tracks.size, key = { index -> tracks[index].ratingKey }) { index ->
                    val track = tracks[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(tracks, index) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${if (showOrdinalNumber) index + 1 else track.index}.",
                            color = Color(0xFFFFE5A93B),
                            modifier = Modifier.width(32.dp),
                            fontWeight = FontWeight.Bold
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(track.artistTitle, color = Color.Gray, fontSize = 12.sp)
                        }
                        Text(
                            text = formatTime(track.durationMs),
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    HorizontalDivider(color = Color(0xFF232329))
                }
            }
        }
    }
}

@Composable
fun TracksScreen(album: PlexAlbum, viewModel: PlexoraViewModel, state: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    var tracks by remember { mutableStateOf<List<PlexTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalTracks by remember { mutableStateOf(0) }
    var isFetchingMore by remember { mutableStateOf(false) }

    fun loadMore() {
        if (isFetchingMore || tracks.size >= totalTracks) return
        isFetchingMore = true
        CoroutineScope(Dispatchers.IO).launch {
            val response = PlexClient(context).getTracks(album.ratingKey, offset = tracks.size)
            withContext(Dispatchers.Main) {
                tracks = tracks + response.tracks
                isFetchingMore = false
            }
        }
    }

    LaunchedEffect(album.ratingKey) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val client = PlexClient(context)
            val response = client.getTracks(album.ratingKey)
            withContext(Dispatchers.Main) {
                tracks = response.tracks
                totalTracks = response.totalSize
                isLoading = false
            }
        }
    }

    // Monitor scroll for infinite scroll
    val firstVisibleItemTracks by remember { derivedStateOf { state.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemTracks) {
        if (tracks.isNotEmpty() && firstVisibleItemTracks > tracks.size - 20) {
            loadMore()
        }
    }

    TracksListContent(
        title = album.title,
        tracks = tracks,
        isLoading = isLoading,
        state = state,
        showOrdinalNumber = false,
        onBack = { viewModel.navigateBack() },
        onTrackClick = { list, index ->
        viewModel.playTracks(list, index)
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    mediaItem: MediaItem,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClear: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onClear
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = mediaItem.mediaMetadata.artworkUri?.toString(),
                contentDescription = "Artwork",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mediaItem.mediaMetadata.title?.toString() ?: "Unknown Song",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onPlayPause,
                        onLongClick = onClear
                    )
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun FullPlayerScreen(
    mediaItem: MediaItem,
    isPlaying: Boolean,
    shuffleModeEnabled: Boolean,
    progress: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isPortraitLayout = configuration.screenHeightDp > configuration.screenWidthDp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121214))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}
            )
    ) {
        AsyncImage(
            model = mediaItem.mediaMetadata.artworkUri?.toString(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(40.dp)
                .alpha(0.2f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FullPlayerDismissHandle(onClose = onClose)

            if (isPortraitLayout) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    FullPlayerArtwork(
                        mediaItem = mediaItem,
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .aspectRatio(1f)
                    )
                    FullPlayerDetailsAndControls(
                        mediaItem = mediaItem,
                        isPlaying = isPlaying,
                        shuffleModeEnabled = shuffleModeEnabled,
                        progress = progress,
                        duration = duration,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onSeek = onSeek,
                        titleFontSize = 24.sp,
                        artistFontSize = 18.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .fullPlayerSwipeDownToDismiss(onClose),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    FullPlayerArtwork(
                        mediaItem = mediaItem,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    )
                    FullPlayerDetailsAndControls(
                        mediaItem = mediaItem,
                        isPlaying = isPlaying,
                        shuffleModeEnabled = shuffleModeEnabled,
                        progress = progress,
                        duration = duration,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onSeek = onSeek,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

private fun Modifier.fullPlayerSwipeDownToDismiss(onClose: () -> Unit): Modifier = composed {
    var downwardDrag by remember { mutableFloatStateOf(0f) }
    pointerInput(onClose) {
        detectVerticalDragGestures(
            onDragEnd = {
                if (downwardDrag > 96f) {
                    onClose()
                }
                downwardDrag = 0f
            },
            onVerticalDrag = { _, dragAmount ->
                if (dragAmount > 0f) {
                    downwardDrag += dragAmount
                }
            }
        )
    }
}

@Composable
private fun FullPlayerDismissHandle(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .fullPlayerSwipeDownToDismiss(onClose)
            .clickable(
                onClick = onClose,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun FullPlayerArtwork(mediaItem: MediaItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        AsyncImage(
            model = mediaItem.mediaMetadata.artworkUri?.toString(),
            contentDescription = "Artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun FullPlayerDetailsAndControls(
    mediaItem: MediaItem,
    isPlaying: Boolean,
    shuffleModeEnabled: Boolean,
    progress: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 28.sp,
    artistFontSize: androidx.compose.ui.unit.TextUnit = 20.sp
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = mediaItem.mediaMetadata.title?.toString() ?: "Unknown Track",
                color = Color.White,
                fontSize = titleFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                color = Color(0xFFFFE5A93B),
                fontSize = artistFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = progress.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color(0xFFFFE5A93B),
                    thumbColor = Color(0xFFFFE5A93B)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(progress), color = Color.Gray, fontSize = 14.sp)
                Text(formatTime(duration), color = Color.Gray, fontSize = 14.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleModeEnabled) Color(0xFFFFE5A93B) else Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFE5A93B))
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: PlexoraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val versionInfo = remember(context) {
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName ?: "Unknown"} " +
                "(${PackageInfoCompat.getLongVersionCode(packageInfo)})"
        }.getOrDefault("Unknown")
    }
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Plex Account",
                    color = Color(0xFFFFE5A93B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "Linked to your Plex server. Resetting will sign you out and clear all data.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.logout(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Unlink and Reset App", color = Color.White)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "App Cache",
                    color = Color(0xFFFFE5A93B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "Clears cached metadata and images. Use this to refresh your library view.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.clearAppCaches(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear All Caches")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "App Information",
                    color = Color(0xFFFFE5A93B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "Version $versionInfo",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun CircularLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color(0xFFFFE5A93B))
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = Color.Red, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

fun formatTime(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    return String.format("%d:%02d", minutes, seconds)
}

private const val PIN_POLL_INTERVAL_MS = 3_000L
private const val PIN_LINK_TIMEOUT_MS = 5 * 60 * 1000L
