package org.ungoverned.plexora

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

object ArtworkUris {
    fun forPlexUrl(context: Context, imageUrl: String?): Uri? {
        if (imageUrl.isNullOrBlank()) return null
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority("${context.packageName}.artwork")
            .appendPath("image")
            .appendQueryParameter("url", imageUrl)
            .build()
    }
}
