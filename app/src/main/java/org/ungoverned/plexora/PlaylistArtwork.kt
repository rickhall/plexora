package org.ungoverned.plexora

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlaylistGridCover(
    playlist: PlexPlaylist,
    modifier: Modifier = Modifier
) {
    var useTrackMosaic by remember(playlist.ratingKey) {
        mutableStateOf(playlist.thumbUrl.isNullOrBlank())
    }

    when {
        useTrackMosaic -> PlaylistTrackMosaic(
            playlistRatingKey = playlist.ratingKey,
            modifier = modifier
        )
        !playlist.thumbUrl.isNullOrBlank() -> SubcomposeAsyncImage(
            model = playlist.thumbUrl,
            contentDescription = playlist.title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            loading = {
                PlaylistArtPlaceholder(modifier = Modifier.fillMaxSize(), showProgress = true)
            },
            success = {
                SubcomposeAsyncImageContent()
            },
            error = {
                LaunchedEffect(playlist.ratingKey) {
                    useTrackMosaic = true
                }
                PlaylistArtPlaceholder(modifier = Modifier.fillMaxSize(), showProgress = true)
            }
        )
        else -> PlaylistTrackMosaic(
            playlistRatingKey = playlist.ratingKey,
            modifier = modifier
        )
    }
}

@Composable
private fun PlaylistTrackMosaic(
    playlistRatingKey: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbUrls by remember(playlistRatingKey) { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(playlistRatingKey) {
        thumbUrls = withContext(Dispatchers.IO) {
            val tracks = PlexClient(context).getPlaylistTracks(playlistRatingKey, limit = 100).tracks
            distinctAlbumArtUrls(tracks, maxAlbums = 4)
        }
    }

    when {
        thumbUrls == null -> PlaylistArtPlaceholder(modifier = modifier, showProgress = true)
        thumbUrls.isNullOrEmpty() -> PlaylistArtPlaceholder(modifier = modifier, showProgress = false)
        else -> PlaylistAlbumMosaic(urls = thumbUrls!!, modifier = modifier)
    }
}

/** One thumb per album, in playlist order, up to [maxAlbums] unique entries. */
internal fun distinctAlbumArtUrls(tracks: List<PlexTrack>, maxAlbums: Int = 4): List<String> {
    val seenAlbumKeys = linkedSetOf<String>()
    val urls = mutableListOf<String>()
    for (track in tracks) {
        val albumKey = track.parentRatingKey.ifEmpty {
            "${track.artistTitle}::${track.albumTitle}"
        }
        if (!seenAlbumKeys.add(albumKey)) continue
        val thumb = track.thumbUrl ?: continue
        urls.add(thumb)
        if (urls.size >= maxAlbums) break
    }
    return urls
}

@Composable
private fun PlaylistAlbumMosaic(urls: List<String>, modifier: Modifier = Modifier) {
    when (urls.size) {
        1 -> MosaicImage(urls[0], modifier)
        2 -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            MosaicCell(urls[0], Modifier.weight(1f).fillMaxHeight())
            MosaicCell(urls[1], Modifier.weight(1f).fillMaxHeight())
        }
        3 -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                MosaicCell(urls[0], Modifier.weight(1f))
                MosaicCell(urls[1], Modifier.weight(1f))
            }
            MosaicCell(urls[2], Modifier.weight(1f).fillMaxWidth())
        }
        else -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                MosaicCell(urls[0], Modifier.weight(1f))
                MosaicCell(urls[1], Modifier.weight(1f))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                MosaicCell(urls[2], Modifier.weight(1f))
                MosaicCell(urls[3], Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MosaicImage(url: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        error = { PlaylistArtPlaceholder(modifier = Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() },
        loading = { PlaylistArtPlaceholder(modifier = Modifier.fillMaxSize(), showProgress = true) }
    )
}

@Composable
private fun MosaicCell(imageUrl: String?, modifier: Modifier = Modifier) {
    if (imageUrl.isNullOrBlank()) {
        Box(
            modifier = modifier
                .fillMaxHeight()
                .background(Color(0xFF2A2A32))
        )
    } else {
        SubcomposeAsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = modifier.fillMaxHeight(),
            contentScale = ContentScale.Crop,
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2A2A32))
                )
            },
            success = { SubcomposeAsyncImageContent() },
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2A2A32))
                )
            }
        )
    }
}

@Composable
fun PlaylistArtPlaceholder(
    modifier: Modifier = Modifier,
    showProgress: Boolean = false
) {
    Box(
        modifier = modifier.background(Color(0xFF2A2A32)),
        contentAlignment = Alignment.Center
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color(0xFFFFE5A93B),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = Color(0xFFFFE5A93B),
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
