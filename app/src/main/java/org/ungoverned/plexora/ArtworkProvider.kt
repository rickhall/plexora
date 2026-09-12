package org.ungoverned.plexora

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ArtworkProvider : ContentProvider() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        val imageUrl = uri.getQueryParameter("url") ?: return "image/jpeg"
        val extension = MimeTypeMap.getFileExtensionFromUrl(imageUrl)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val imageUrl = uri.getQueryParameter("url") ?: return null
        val cacheFile = cacheFileFor(context, imageUrl)
        if (!cacheFile.exists()) {
            downloadImage(imageUrl, cacheFile)
        }
        if (!cacheFile.exists()) return null
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cacheFileFor(context: android.content.Context, imageUrl: String): File {
        val directory = File(context.cacheDir, "artwork")
        directory.mkdirs()
        val fileName = imageUrl.hashCode().toUInt().toString(16) + ".img"
        return File(directory, fileName)
    }

    private fun downloadImage(imageUrl: String, destination: File) {
        val request = Request.Builder()
            .url(imageUrl)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body ?: return
            FileOutputStream(destination).use { output ->
                body.byteStream().copyTo(output)
            }
        }
    }
}
