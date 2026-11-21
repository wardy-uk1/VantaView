// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import android.app.Application
import android.content.ComponentCallbacks2
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size
import com.vantaview.app.data.LowMemory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VantaApp : Application(), ImageLoaderFactory {
    @javax.inject.Inject lateinit var trakt: com.vantaview.app.data.trakt.TraktSync
    @javax.inject.Inject lateinit var stremio: com.vantaview.app.data.stremio.StremioSync
    @javax.inject.Inject lateinit var okHttp: okhttp3.OkHttpClient

    override fun onCreate() {
        // Before anything else: other parts read it as they start.
        LowMemory.init(this)
        super.onCreate()
        // Profiles linked to Trakt keep their Watchlist, watched and Continue watching in step.
        trakt.start()
        // Profiles signed in to Stremio keep add-ons, Library and Continue watching in step with it.
        stremio.start()
        // Topics, Importance and versions in Live TV (downloaded only when one of them is used).
        com.vantaview.app.data.catalog.ChannelCatalog.init(this, okHttp)
        // Certificates the user chose to trust (Sources › Add-ons), read before any connection.
        com.vantaview.app.data.security.CertPins.init(this)
        // Switching low-memory mode rebuilds the picture loader with the new sizes.
        LowMemory.onChange { Coil.setImageLoader(newImageLoader()) }
    }

    /**
     * The picture loader for posters, logos and backdrops. In low-memory mode it keeps a smaller cache,
     * decodes pictures at three-quarters of the size they're shown (in 16-bit colour where there's no
     * transparency), and doesn't fade them in.
     */
    override fun newImageLoader(): ImageLoader {
        val low = LowMemory.active
        return ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (low) 0.12 else 0.25).build() }
            .allowRgb565(low)
            .crossfade(!low)
            .apply { if (low) components { add(SmallerPictures) } }
            .build()
    }

    /** Low-memory mode: decode pictures at 75% of their drawn size (hardly visible on a TV across the room). */
    private object SmallerPictures : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            fun d(x: Dimension) = if (x is Dimension.Pixels) Dimension.Pixels((x.px * 3 / 4).coerceAtLeast(1)) else x
            val s = chain.size
            return chain.withSize(Size(d(s.width), d(s.height))).proceed(chain.request)
        }
    }

    /** Android is short of memory: give back the cached pictures (they reload from disk when needed). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val loader = Coil.imageLoader(this)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> loader.memoryCache?.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && LowMemory.active -> loader.memoryCache?.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> loader.memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
    }
}
