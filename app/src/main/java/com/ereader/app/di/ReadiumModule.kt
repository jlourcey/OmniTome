package com.ereader.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import javax.inject.Singleton

/**
 * ReadiumModule — provides the Readium SDK components needed to open EPUB files.
 *
 * How Readium works (simplified):
 *  1. [AssetRetriever]        → opens a file (local or remote) as a generic asset
 *  2. [DefaultPublicationParser] → parses the asset into a Readium Publication object
 *  3. [PublicationOpener]     → combines the two above into a one-call "open this EPUB"
 *
 * ReaderViewModel uses [PublicationOpener] to open a book before handing it
 * to the EpubNavigatorFragment for rendering.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReadiumModule {

    @Provides
    @Singleton
    fun provideHttpClient(): DefaultHttpClient = DefaultHttpClient()

    @Provides
    @Singleton
    fun provideAssetRetriever(
        @ApplicationContext context: Context,
        httpClient: DefaultHttpClient
    ): AssetRetriever = AssetRetriever(context.contentResolver, httpClient)

    @Provides
    @Singleton
    fun providePublicationParser(
        @ApplicationContext context: Context,
        httpClient: DefaultHttpClient,
        assetRetriever: AssetRetriever
    ): DefaultPublicationParser = DefaultPublicationParser(
        context      = context,
        httpClient   = httpClient,
        assetRetriever = assetRetriever,
        pdfFactory   = null   // no PDF rendering engine bundled; pass null to disable PDF support
    )

    @Provides
    @Singleton
    fun providePublicationOpener(
        publicationParser: DefaultPublicationParser,
        assetRetriever: AssetRetriever
    ): PublicationOpener = PublicationOpener(
        publicationParser = publicationParser,
        contentProtections = emptyList()   // add DRM content protections here if needed
    )
}
