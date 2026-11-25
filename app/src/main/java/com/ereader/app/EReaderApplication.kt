package com.ereader.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ereader.app.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class — the very first thing Android creates when the app launches.
 *
 * @HiltAndroidApp tells Hilt to generate all the dependency injection code for
 * the whole app. Without this annotation, nothing in the DI graph works.
 *
 * Implements [ImageLoaderFactory] so that Coil's singleton ImageLoader uses
 * the same OkHttpClient that is wired with [CalibreDigestAuthenticator].
 * Without this, Coil's default client has no authenticator and all cover image
 * requests to the password-protected Calibre server return 401 and fail silently.
 */
@HiltAndroidApp
class EReaderApplication : Application(), ImageLoaderFactory {

    /**
     * The singleton OkHttpClient provided by NetworkModule, which already has
     * CalibreDigestAuthenticator set as its authenticator.
     *
     * Hilt injects this field before onCreate() is called.
     */
    @Inject lateinit var okHttpClient: OkHttpClient

    /** Sends notes / page / Read status to Calibre automatically when back online. */
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Set up Timber logging.
        // In debug builds we get full stack traces printed to Logcat.
        // In release builds we plant nothing — logs are stripped entirely.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Queue any changes still waiting for Calibre, and watch for the
        // phone getting back online (e.g. joining your home Wi-Fi).
        syncScheduler.start()

        // Refresh the home-screen widget (if one has been added).
        com.ereader.app.widget.ContinueReadingWidget.updateAll(this)

        Timber.d("EReaderApplication started")
    }
}
