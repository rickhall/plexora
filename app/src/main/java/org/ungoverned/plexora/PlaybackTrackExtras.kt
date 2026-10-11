package org.ungoverned.plexora

/** Bundled on [androidx.media3.common.MediaMetadata] for playback enrichment. */
object PlaybackTrackExtras {
    const val PARENT_RATING_KEY = "plex.parent_rating_key"
    const val GRANDPARENT_RATING_KEY = "plex.grandparent_rating_key"
    const val PLAY_QUEUE_ITEM_ID = "plex.play_queue_item_id"
}

data class ResolvedTrackPlayback(
    val track: PlexTrack,
    val releaseYear: Int = 0,
)
