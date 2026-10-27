package com.openbubbles.wear

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder

/**
 * Coil loader shared by chat thumbnails and the full-screen viewer. Wear OS 3+
 * exposes ImageDecoder, which animates GIFs and animated WebPs in place.
 */
class WearApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        WatchRepository.attach(this)
        WatchNotifications.createChannel(this)
        WatchSurfaces.attach(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ImageDecoderDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
