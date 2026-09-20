package org.ungoverned.plexora

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stale-while-revalidate cache for Plex library list queries (memory + disk).
 */
class LibraryQueryCache(context: Context) {

    private val cacheDir = File(context.cacheDir, "library_query_cache").apply { mkdirs() }
    private val memory = mutableMapOf<String, CacheEnvelope>()

    fun artistsKey(sectionId: String): String = "artists:$sectionId"
    fun allAlbumsKey(sectionId: String): String = "albums:all:$sectionId"
    fun recentlyAddedKey(sectionId: String): String = "albums:recent:$sectionId"
    fun playlistsKey(): String = "playlists"
    fun artistAlbumsKey(artistRatingKey: String): String = "albums:artist:$artistRatingKey"
    fun albumTracksKey(albumRatingKey: String): String = "tracks:album:$albumRatingKey"
    fun playlistTracksKey(playlistRatingKey: String): String = "tracks:playlist:$playlistRatingKey"

    fun getArtists(key: String): List<PlexArtist>? = getList(key)?.artists?.asStaleCacheData()
    fun getAlbums(key: String): List<PlexAlbum>? = getList(key)?.albums?.asStaleCacheData()
    fun getPlaylists(key: String): List<PlexPlaylist>? = getList(key)?.playlists?.asStaleCacheData()
    fun getTracks(key: String): CachedTracks? = getList(key)?.tracks?.asStaleCacheData()

    fun putArtists(key: String, artists: List<PlexArtist>) {
        if (artists.isEmpty()) return
        put(key, CacheEnvelope(artists = artists, fingerprint = fingerprint(artists.map { it.ratingKey })))
    }

    fun putAlbums(key: String, albums: List<PlexAlbum>) {
        if (albums.isEmpty()) return
        put(key, CacheEnvelope(albums = albums, fingerprint = fingerprint(albums.map { it.ratingKey })))
    }

    fun putPlaylists(key: String, playlists: List<PlexPlaylist>) {
        if (playlists.isEmpty()) return
        put(key, CacheEnvelope(playlists = playlists, fingerprint = fingerprint(playlists.map { it.ratingKey })))
    }

    fun putTracks(key: String, tracks: List<PlexTrack>, totalSize: Int) {
        if (tracks.isEmpty()) return
        put(
            key,
            CacheEnvelope(
                tracks = CachedTracks(tracks, totalSize),
                fingerprint = fingerprint(tracks.map { it.ratingKey }) + ":$totalSize"
            )
        )
    }

    fun sameArtists(cached: List<PlexArtist>?, fresh: List<PlexArtist>): Boolean =
        cached != null && fingerprint(cached.map { it.ratingKey }) == fingerprint(fresh.map { it.ratingKey })

    fun sameAlbums(cached: List<PlexAlbum>?, fresh: List<PlexAlbum>): Boolean =
        cached != null && fingerprint(cached.map { it.ratingKey }) == fingerprint(fresh.map { it.ratingKey })

    fun samePlaylists(cached: List<PlexPlaylist>?, fresh: List<PlexPlaylist>): Boolean =
        cached != null && fingerprint(cached.map { it.ratingKey }) == fingerprint(fresh.map { it.ratingKey })

    fun sameTracks(cached: CachedTracks?, fresh: List<PlexTrack>, totalSize: Int): Boolean =
        cached != null &&
            cached.totalSize == totalSize &&
            fingerprint(cached.tracks.map { it.ratingKey }) == fingerprint(fresh.map { it.ratingKey })

    fun clearAll() {
        memory.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private data class CacheEnvelope(
        val artists: List<PlexArtist>? = null,
        val albums: List<PlexAlbum>? = null,
        val playlists: List<PlexPlaylist>? = null,
        val tracks: CachedTracks? = null,
        val fingerprint: String
    )

    data class CachedTracks(val tracks: List<PlexTrack>, val totalSize: Int)

    private fun fingerprint(ratingKeys: List<String>): String = ratingKeys.joinToString("\u0001")

    private fun getList(key: String): CacheEnvelope? {
        memory[key]?.let { return it }
        val file = fileForKey(key)
        if (!file.exists()) return null
        return runCatching {
            val envelope = parseEnvelope(JSONObject(file.readText()))
            if (envelope.isEmptyContent()) {
                file.delete()
                null
            } else {
                envelope.also { memory[key] = it }
            }
        }.getOrNull()
    }

    private fun CacheEnvelope.isEmptyContent(): Boolean = when {
        artists != null -> artists.isEmpty()
        albums != null -> albums.isEmpty()
        playlists != null -> playlists.isEmpty()
        tracks != null -> tracks.tracks.isEmpty()
        else -> true
    }

    private fun put(key: String, envelope: CacheEnvelope) {
        memory[key] = envelope
        runCatching {
            fileForKey(key).writeText(serializeEnvelope(envelope).toString())
        }
    }

    private fun fileForKey(key: String): File {
        val safeName = key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(cacheDir, "$safeName.json")
    }

    private fun serializeEnvelope(envelope: CacheEnvelope): JSONObject {
        val json = JSONObject()
        json.put("fingerprint", envelope.fingerprint)
        envelope.artists?.let { json.put("artists", serializeArtists(it)) }
        envelope.albums?.let { json.put("albums", serializeAlbums(it)) }
        envelope.playlists?.let { json.put("playlists", serializePlaylists(it)) }
        envelope.tracks?.let { json.put("tracks", serializeTracks(it.tracks)); json.put("totalSize", it.totalSize) }
        return json
    }

    private fun parseEnvelope(json: JSONObject): CacheEnvelope {
        val fingerprint = json.optString("fingerprint", "")
        val artists = if (json.has("artists")) parseArtists(json.getJSONArray("artists")) else null
        val albums = if (json.has("albums")) parseAlbums(json.getJSONArray("albums")) else null
        val playlists = if (json.has("playlists")) parsePlaylists(json.getJSONArray("playlists")) else null
        val tracks = if (json.has("tracks")) {
            CachedTracks(
                parseTracks(json.getJSONArray("tracks")),
                json.optInt("totalSize", 0)
            )
        } else {
            null
        }
        return CacheEnvelope(artists, albums, playlists, tracks, fingerprint)
    }

    private fun serializeArtists(artists: List<PlexArtist>): JSONArray {
        val array = JSONArray()
        artists.forEach { artist ->
            array.put(
                JSONObject()
                    .put("ratingKey", artist.ratingKey)
                    .put("title", artist.title)
                    .put("thumbUrl", artist.thumbUrl)
            )
        }
        return array
    }

    private fun parseArtists(array: JSONArray): List<PlexArtist> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(
                PlexArtist(
                    ratingKey = o.getString("ratingKey"),
                    title = o.getString("title"),
                    thumbUrl = o.optString("thumbUrl").ifEmpty { null }
                )
            )
        }
    }

    private fun serializeAlbums(albums: List<PlexAlbum>): JSONArray {
        val array = JSONArray()
        albums.forEach { album ->
            array.put(
                JSONObject()
                    .put("ratingKey", album.ratingKey)
                    .put("artistRatingKey", album.artistRatingKey)
                    .put("title", album.title)
                    .put("artistTitle", album.artistTitle)
                    .put("thumbUrl", album.thumbUrl)
            )
        }
        return array
    }

    private fun parseAlbums(array: JSONArray): List<PlexAlbum> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(
                PlexAlbum(
                    ratingKey = o.getString("ratingKey"),
                    artistRatingKey = o.optString("artistRatingKey", ""),
                    title = o.getString("title"),
                    artistTitle = o.optString("artistTitle", ""),
                    thumbUrl = o.optString("thumbUrl").ifEmpty { null }
                )
            )
        }
    }

    private fun serializePlaylists(playlists: List<PlexPlaylist>): JSONArray {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("ratingKey", playlist.ratingKey)
                    .put("title", playlist.title)
                    .put("thumbUrl", playlist.thumbUrl)
            )
        }
        return array
    }

    private fun parsePlaylists(array: JSONArray): List<PlexPlaylist> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(
                PlexPlaylist(
                    ratingKey = o.getString("ratingKey"),
                    title = o.getString("title"),
                    thumbUrl = o.optString("thumbUrl").ifEmpty { null }
                )
            )
        }
    }

    private fun serializeTracks(tracks: List<PlexTrack>): JSONArray {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("ratingKey", track.ratingKey)
                    .put("title", track.title)
                    .put("artistTitle", track.artistTitle)
                    .put("albumTitle", track.albumTitle)
                    .put("index", track.index)
                    .put("durationMs", track.durationMs)
                    .put("streamUrl", track.streamUrl)
                    .put("thumbUrl", track.thumbUrl)
                    .put("parentRatingKey", track.parentRatingKey)
            )
        }
        return array
    }

    private fun parseTracks(array: JSONArray): List<PlexTrack> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(
                PlexTrack(
                    ratingKey = o.getString("ratingKey"),
                    title = o.getString("title"),
                    artistTitle = o.optString("artistTitle", ""),
                    albumTitle = o.optString("albumTitle", ""),
                    index = o.optInt("index", i + 1),
                    durationMs = o.optLong("durationMs", 0L),
                    streamUrl = o.getString("streamUrl"),
                    thumbUrl = o.optString("thumbUrl").ifEmpty { null },
                    parentRatingKey = o.optString("parentRatingKey", "")
                )
            )
        }
    }
}

data class CachedResource<T>(
    val data: T? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null
) {
    val showFullScreenLoading: Boolean get() = data == null && error == null
}

/** Stale cache entries with zero items are ignored for display. */
@Suppress("UNCHECKED_CAST")
fun <T> T?.asStaleCacheData(): T? = when (this) {
    null -> null
    is List<*> -> if (isEmpty()) null else this as T
    is LibraryQueryCache.CachedTracks -> if (tracks.isEmpty()) null else this as T
    else -> this
}

fun <T> CachedResource<T>.showTabLoading(): Boolean {
    if (error != null) return false
    val payload = data ?: return true
    return when (payload) {
        is List<*> -> payload.isEmpty() && isRefreshing
        is LibraryQueryCache.CachedTracks -> payload.tracks.isEmpty() && isRefreshing
        else -> false
    }
}

