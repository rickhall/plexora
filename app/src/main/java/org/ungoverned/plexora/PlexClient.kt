package org.ungoverned.plexora

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PlexSection(
    val id: String,
    val title: String
)

data class PlexConnection(
    val uri: String,
    val local: Boolean
)

data class PlexServer(
    val id: String,
    val name: String,
    val connections: List<PlexConnection>
)

data class PlexPinResponse(
    val id: Int,
    val code: String,
    val authToken: String? = null
)

data class PlexArtist(
    val ratingKey: String,
    val title: String,
    val thumbUrl: String?
)

data class PlexAlbum(
    val ratingKey: String,
    val artistRatingKey: String,
    val title: String,
    val artistTitle: String,
    val thumbUrl: String?
)

data class PlexPlaylist(
    val ratingKey: String,
    val title: String,
    val thumbUrl: String?
)

data class PlexTrack(
    val ratingKey: String,
    val title: String,
    val artistTitle: String,
    val albumTitle: String,
    val index: Int,
    val durationMs: Long,
    val streamUrl: String,
    val thumbUrl: String?
)

data class PlexTrackResponse(
    val tracks: List<PlexTrack>,
    val totalSize: Int
)

data class PlexPlayQueue(
    val id: String,
    val tracks: List<PlexTrack>
)

class PlexClient(private val context: Context) {
    
    private val tag = "PlexClient"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .cache(okhttp3.Cache(
            directory = java.io.File(context.cacheDir, "http_cache"),
            maxSize = 50L * 1024L * 1024L // 50 MB metadata cache
        ))
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            
            // Force caching for 1 hour for all successful library queries
            response.newBuilder()
                .header("Cache-Control", "public, max-age=3600")
                .removeHeader("Pragma")
                .build()
        }
        .build()

    private val prefs = context.getSharedPreferences("plex_prefs", Context.MODE_PRIVATE)

    fun getServerUrl(): String = prefs.getString("server_url", "") ?: ""
    fun getPlexToken(): String = prefs.getString("plex_token", "") ?: ""
    fun getLibrarySection(): String = prefs.getString("section_id", "") ?: ""
    fun getMachineId(): String = prefs.getString("machine_id", "") ?: ""

    fun getClientId(): String {
        var id = prefs.getString("client_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("client_id", id).apply()
        }
        return id
    }

    fun saveConfig(serverUrl: String, token: String, sectionId: String, machineId: String = "") {
        val cleanUrl = if (serverUrl.endsWith("/")) serverUrl.substring(0, serverUrl.length - 1) else serverUrl
        val editor = prefs.edit()
            .putString("server_url", cleanUrl)
            .putString("plex_token", token)
            .putString("section_id", sectionId)
        
        if (machineId.isNotEmpty()) {
            editor.putString("machine_id", machineId)
        }
        editor.apply()
    }

    fun isConfigured(): Boolean {
        return getServerUrl().isNotEmpty() && getPlexToken().isNotEmpty() && getLibrarySection().isNotEmpty()
    }

    fun clearConfig() {
        prefs.edit()
            .remove("server_url")
            .remove("plex_token")
            .remove("section_id")
            .remove("machine_id")
            .apply()
    }

    fun clearPlaybackState() {
        context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun clearCaches() {
        try {
            client.cache?.evictAll()
        } catch (e: Exception) {
            Log.e(tag, "Error clearing metadata cache", e)
        }
    }

    fun signOut(): Boolean {
        val token = getPlexToken()
        if (token.isEmpty()) {
            clearConfig()
            clearPlaybackState()
            return true
        }
        val clientId = getClientId()

        try {
            val devicesUrl = "https://plex.tv/api/v2/devices"
            val getRequest = Request.Builder()
                .url(devicesUrl)
                .addHeader("Accept", "application/json")
                .addHeader("X-Plex-Token", token)
                .addHeader("X-Plex-Client-Identifier", clientId)
                .build()

            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val array = org.json.JSONArray(body)
                    for (i in 0 until array.length()) {
                        val device = array.getJSONObject(i)
                        if (device.getString("clientIdentifier") == clientId) {
                            val numericId = device.getInt("id")
                            val deleteUrl = "https://plex.tv/api/v2/devices/$numericId"
                            val deleteRequest = Request.Builder()
                                .url(deleteUrl)
                                .delete()
                                .addHeader("X-Plex-Token", token)
                                .addHeader("X-Plex-Client-Identifier", clientId)
                                .build()
                            client.newCall(deleteRequest).execute().close()
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to remove device", e)
        }

        val signOutUrl = "https://plex.tv/api/v2/users/signout"
        val request = Request.Builder()
            .url(signOutUrl)
            .delete()
            .addHeader("X-Plex-Token", token)
            .addHeader("X-Plex-Client-Identifier", clientId)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                clearConfig()
                clearPlaybackState()
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(tag, "Error during sign out", e)
            clearConfig()
            clearPlaybackState()
            false
        }
    }

    fun getServersFromPlexTv(token: String): List<PlexServer> {
        val url = "https://plex.tv/api/v2/resources?includeHttps=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("X-Plex-Token", token)
            .addHeader("X-Plex-Client-Identifier", getClientId())
            .build()

        val jsonStr = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val servers = mutableListOf<PlexServer>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val device = array.getJSONObject(i)
                if (device.optString("provides", "").contains("server")) {
                    val name = device.getString("name")
                    val id = device.getString("clientIdentifier")
                    val connectionsArray = device.getJSONArray("connections")
                    val connections = mutableListOf<PlexConnection>()
                    for (j in 0 until connectionsArray.length()) {
                        val conn = connectionsArray.getJSONObject(j)
                        connections.add(PlexConnection(conn.getString("uri"), conn.optBoolean("local", false)))
                    }
                    servers.add(PlexServer(id, name, connections))
                }
            }
        } catch (e: Exception) {}
        return servers
    }

    fun generatePin(): PlexPinResponse? {
        val url = "https://plex.tv/api/v2/pins"
        val body = okhttp3.FormBody.Builder().build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Accept", "application/json")
            .addHeader("X-Plex-Product", "Plexora")
            .addHeader("X-Plex-Client-Identifier", getClientId())
            .addHeader("X-Plex-Device", android.os.Build.MODEL)
            .addHeader("X-Plex-Platform", "Android")
            .addHeader("X-Plex-Version", "1.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val json = JSONObject(response.body?.string() ?: "")
                    PlexPinResponse(json.getInt("id"), json.getString("code"))
                }
            }
        } catch (e: Exception) { null }
    }

    fun checkPin(pinId: Int): String? {
        val url = "https://plex.tv/api/v2/pins/$pinId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("X-Plex-Client-Identifier", getClientId())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.optString("authToken", null).takeIf { it != "null" && it.isNotEmpty() }
                }
            }
        } catch (e: Exception) { null }
    }

    fun buildUrl(path: String, queryParams: Map<String, String> = emptyMap()): String {
        val base = getServerUrl()
        val token = getPlexToken()
        val builder = Uri.parse(base).buildUpon()
            .encodedPath(path)
            .appendQueryParameter("X-Plex-Token", token)
        for ((key, value) in queryParams) {
            builder.appendQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    fun getImageUrl(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return buildUrl(if (path.startsWith("/")) path else "/$path")
    }

    private fun executeGetRequest(url: String): String? {
        val request = Request.Builder().url(url).addHeader("Accept", "application/json").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: IOException) { null }
    }

    fun getMusicSections(): List<PlexSection> {
        val jsonStr = executeGetRequest(buildUrl("/library/sections")) ?: return emptyList()
        val sections = mutableListOf<PlexSection>()
        try {
            val root = JSONObject(jsonStr)
            val mediaContainer = root.getJSONObject("MediaContainer")
            val directory = mediaContainer.optJSONArray("Directory") ?: return emptyList()
            for (i in 0 until directory.length()) {
                val obj = directory.getJSONObject(i)
                if (obj.optString("type") == "artist") {
                    sections.add(PlexSection(obj.getString("key"), obj.getString("title")))
                }
            }
        } catch (e: Exception) {}
        return sections
    }

    fun getArtists(): List<PlexArtist> {
        return getArtistsByUrl(buildUrl("/library/sections/${getLibrarySection()}/all", mapOf("type" to "8")))
    }

    fun getArtist(artistRatingKey: String): PlexArtist? {
        return getArtistsByUrl(buildUrl("/library/metadata/$artistRatingKey")).firstOrNull()
    }

    fun getRecentlyAddedAlbums(): List<PlexAlbum> {
        return getAlbumsByUrl(buildUrl("/library/sections/${getLibrarySection()}/recentlyAdded", mapOf("type" to "9")))
    }

    fun getAllAlbums(): List<PlexAlbum> {
        return getAlbumsByUrl(buildUrl("/library/sections/${getLibrarySection()}/all", mapOf("type" to "9", "sort" to "titleSort")))
    }

    fun getAlbum(albumRatingKey: String): PlexAlbum? {
        return getAlbumsByUrl(buildUrl("/library/metadata/$albumRatingKey")).firstOrNull()
    }

    fun getPlaylists(): List<PlexPlaylist> {
        val jsonStr = executeGetRequest(buildUrl("/playlists")) ?: return emptyList()
        val playlists = mutableListOf<PlexPlaylist>()
        try {
            val array = JSONObject(jsonStr).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: return emptyList()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("playlistType") == "audio") {
                    playlists.add(PlexPlaylist(obj.getString("ratingKey"), obj.getString("title"), getImageUrl(obj.optString("thumb", null))))
                }
            }
        } catch (e: Exception) {}
        return playlists
    }

    fun getPlaylist(playlistRatingKey: String): PlexPlaylist? {
        val jsonStr = executeGetRequest(buildUrl("/playlists/$playlistRatingKey")) ?: return null
        try {
            val array = JSONObject(jsonStr).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: return null
            if (array.length() > 0) {
                val obj = array.getJSONObject(0)
                return PlexPlaylist(obj.getString("ratingKey"), obj.getString("title"), getImageUrl(obj.optString("thumb", null)))
            }
        } catch (e: Exception) {}
        return null
    }

    fun searchArtists(query: String): List<PlexArtist> {
        return getArtistsByUrl(buildUrl("/library/sections/${getLibrarySection()}/search", mapOf("type" to "8", "query" to query)))
    }

    fun searchAlbums(query: String): List<PlexAlbum> {
        return getAlbumsByUrl(buildUrl("/library/sections/${getLibrarySection()}/search", mapOf("type" to "9", "query" to query)))
    }

    fun searchTracks(query: String): List<PlexTrack> {
        return getTracksByUrl(buildUrl("/library/sections/${getLibrarySection()}/search", mapOf("type" to "10", "query" to query)))
    }

    private fun getArtistsByUrl(url: String): List<PlexArtist> {
        val jsonStr = executeGetRequest(url) ?: return emptyList()
        val artists = mutableListOf<PlexArtist>()
        try {
            val array = JSONObject(jsonStr).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: return emptyList()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                artists.add(PlexArtist(obj.getString("ratingKey"), obj.getString("title"), getImageUrl(obj.optString("thumb", null))))
            }
        } catch (e: Exception) {}
        return artists
    }

    private fun getAlbumsByUrl(url: String): List<PlexAlbum> {
        val jsonStr = executeGetRequest(url) ?: return emptyList()
        val albums = mutableListOf<PlexAlbum>()
        try {
            val array = JSONObject(jsonStr).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: return emptyList()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                albums.add(PlexAlbum(obj.getString("ratingKey"), obj.optString("parentRatingKey", ""), obj.getString("title"), obj.optString("parentTitle", "Unknown Artist"), getImageUrl(obj.optString("thumb", null))))
            }
        } catch (e: Exception) {}
        return albums
    }

    fun getAlbums(artistRatingKey: String): List<PlexAlbum> {
        return getAlbumsByUrl(buildUrl("/library/metadata/$artistRatingKey/children"))
    }

    fun getTracks(albumRatingKey: String, offset: Int = 0, limit: Int = 100): PlexTrackResponse {
        return getTracksPaged(buildUrl("/library/metadata/$albumRatingKey/children", mapOf(
            "X-Plex-Container-Start" to offset.toString(),
            "X-Plex-Container-Size" to limit.toString()
        )))
    }

    fun getTrack(trackRatingKey: String): PlexTrack? {
        return getTracksByUrl(buildUrl("/library/metadata/$trackRatingKey")).firstOrNull()
    }

    fun getArtistTracks(artistRatingKey: String): List<PlexTrack> {
        return getTracksByUrl(buildUrl("/library/metadata/$artistRatingKey/allLeaves"))
    }

    fun getPlaylistTracks(playlistRatingKey: String, offset: Int = 0, limit: Int = 100): PlexTrackResponse {
        return getTracksPaged(buildUrl("/playlists/$playlistRatingKey/items", mapOf(
            "X-Plex-Container-Start" to offset.toString(),
            "X-Plex-Container-Size" to limit.toString()
        )))
    }

    private fun getTracksPaged(url: String): PlexTrackResponse {
        val jsonStr = executeGetRequest(url) ?: return PlexTrackResponse(emptyList(), 0)
        try {
            val container = JSONObject(jsonStr).getJSONObject("MediaContainer")
            val totalSize = container.optInt("totalSize", 0)
            val tracks = parseTracksFromContainer(container)
            return PlexTrackResponse(tracks, totalSize)
        } catch (e: Exception) {
            Log.e(tag, "Error parsing paged tracks", e)
        }
        return PlexTrackResponse(emptyList(), 0)
    }

    private fun getTracksByUrl(url: String): List<PlexTrack> {
        val jsonStr = executeGetRequest(url) ?: return emptyList()
        try {
            val container = JSONObject(jsonStr).getJSONObject("MediaContainer")
            return parseTracksFromContainer(container)
        } catch (e: Exception) {}
        return emptyList()
    }

    fun createPlayQueue(sourceUri: String, shuffle: Boolean = true): PlexPlayQueue? {
        Log.d(tag, "Creating play queue for URI: $sourceUri (shuffle=$shuffle)")
        val url = buildUrl("/playQueues", mapOf(
            "type" to "audio",
            "uri" to sourceUri,
            "shuffle" to if (shuffle) "1" else "0",
            "repeat" to "0",
            "includeChapters" to "1"
        ))
        
        val request = Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .addHeader("Accept", "application/json")
            .addHeader("X-Plex-Client-Identifier", getClientId())
            .addHeader("X-Plex-Device", android.os.Build.MODEL)
            .addHeader("X-Plex-Platform", "Android")
            .build()

        val jsonStr = try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(tag, "Queue creation failed. Code: ${response.code}, Body: $body")
                    null
                } else {
                    body
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Queue creation network error", e)
            null
        } ?: return null

        return parsePlayQueue(jsonStr)
    }

    fun createLibraryPlayQueue(): PlexPlayQueue? {
        val machineId = getMachineId()
        val sectionId = getLibrarySection()
        if (machineId.isEmpty() || sectionId.isEmpty()) {
            Log.e(tag, "Missing config for library shuffle: machineId='$machineId', sectionId='$sectionId'")
            return null
        }
        
        // This URI format is confirmed to work for music library shuffles
        val sourceUri = "library://$machineId/directory//library/sections/$sectionId/all"
        return createPlayQueue(sourceUri)
    }

    fun getPlayQueue(playQueueId: String): PlexPlayQueue? {
        val jsonStr = executeGetRequest(buildUrl("/playQueues/$playQueueId", mapOf("window" to "50"))) ?: return null
        return parsePlayQueue(jsonStr)
    }

    private fun parsePlayQueue(jsonStr: String): PlexPlayQueue? {
        try {
            val root = JSONObject(jsonStr)
            val container = root.optJSONObject("MediaContainer") ?: return null
            val id = container.optString("playQueueID")
            val tracks = parseTracksFromContainer(container)
            
            if (id.isNotEmpty() && tracks.isNotEmpty()) {
                return PlexPlayQueue(id, tracks)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing play queue JSON", e)
        }
        return null
    }

    private fun parseTracksFromContainer(container: JSONObject): List<PlexTrack> {
        val tracks = mutableListOf<PlexTrack>()
        val array = container.optJSONArray("Metadata") ?: container.optJSONArray("items") ?: return emptyList()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val media = obj.optJSONArray("Media")?.optJSONObject(0)
            val part = media?.optJSONArray("Part")?.optJSONObject(0)
            val streamUrl = part?.optString("key")?.let { buildUrl(it) } ?: ""
            if (streamUrl.isNotEmpty()) {
                tracks.add(PlexTrack(obj.getString("ratingKey"), obj.getString("title"), obj.optString("grandparentTitle", obj.optString("parentTitle", "Unknown Artist")), obj.optString("parentTitle", "Unknown Album"), obj.optInt("index", i + 1), obj.optLong("duration", 0L), streamUrl, getImageUrl(obj.optString("thumb", null))))
            }
        }
        return tracks
    }

    fun findBestConnection(server: PlexServer): String? {
        for (conn in server.connections) {
            val request = Request.Builder().url("${conn.uri}/identity?X-Plex-Token=${getPlexToken()}").build()
            try { client.newCall(request).execute().use { if (it.isSuccessful) return conn.uri } } catch (e: Exception) {}
        }
        return null
    }
}
