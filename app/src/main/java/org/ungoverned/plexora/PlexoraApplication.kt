package org.ungoverned.plexora

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class PlexoraApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        Log.d("Coil", "Initializing global ImageLoader from Application class")
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.1) // Use up to 10% of available disk space
                    .build()
            }
            .eventListener(object : coil.EventListener {
                override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                    Log.d("Coil", "Image load success: ${result.dataSource} - ${request.data}")
                }
                override fun onError(request: coil.request.ImageRequest, result: coil.request.ErrorResult) {
                    Log.e("Coil", "Image load error: ${result.throwable.message} - ${request.data}")
                }
            })
            .respectCacheHeaders(false) // Favor local disk cache
            .build()
    }
}
