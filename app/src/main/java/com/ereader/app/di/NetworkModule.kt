package com.ereader.app.di

import com.ereader.app.BuildConfig
import com.ereader.app.data.remote.calibre.CalibreDigestAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * NetworkModule — provides the OkHttpClient used for all HTTP calls.
 *
 * OkHttpClient is expensive to create (it holds thread pools and connection pools),
 * so we create exactly one instance and share it everywhere via @Singleton.
 *
 * The singleton client includes [CalibreDigestAuthenticator] so that any component
 * injecting OkHttpClient (e.g. ReaderViewModel, Coil ImageLoader) automatically
 * handles Calibre's HTTP Digest-MD5 challenge-response on 401 responses without
 * needing to create its own auth-wrapped client.
 *
 * NOTE on Google Drive:
 *   The Drive client requires a GoogleAccountCredential after the user signs in.
 *   That credential is obtained in MainActivity (because sign-in needs an Activity)
 *   and is then passed to the Drive.Builder. We don't provide the Drive client here
 *   because it depends on runtime user state — it's created in MainActivity and
 *   stored in a singleton holder that DriveService can access.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        digestAuthenticator: CalibreDigestAuthenticator
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Handle Calibre's Digest-MD5 auth challenge (and Basic fallback) on 401.
            // The authenticator reads credentials from DataStore; it returns null
            // (stops retrying) when no username is configured or credentials are wrong.
            .authenticator(digestAuthenticator)

        // Only log HTTP traffic in debug builds — never in release
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addInterceptor(logging)
        }

        return builder.build()
    }
}
