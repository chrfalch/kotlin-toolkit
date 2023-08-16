/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp

import android.content.Context
import org.readium.adapters.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.lcp.LcpService
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.UserException
import org.readium.r2.shared.asset.AssetRetriever
import org.readium.r2.shared.error.Try
import org.readium.r2.shared.publication.protection.ContentProtectionSchemeRetriever
import org.readium.r2.shared.resource.CompositeArchiveFactory
import org.readium.r2.shared.resource.CompositeResourceFactory
import org.readium.r2.shared.resource.ContentResourceFactory
import org.readium.r2.shared.resource.DefaultArchiveFactory
import org.readium.r2.shared.resource.DirectoryContainerFactory
import org.readium.r2.shared.resource.FileResourceFactory
import org.readium.r2.shared.util.archive.channel.ChannelZipArchiveFactory
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpResourceFactory
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.mediatype.MediaTypeRetriever
import org.readium.r2.streamer.PublicationFactory

/**
 * Holds the shared Readium objects and services used by the app.
 */
class Readium(context: Context) {

    private val httpClient = DefaultHttpClient()

    private val archiveFactory = CompositeArchiveFactory(
        DefaultArchiveFactory(),
        ChannelZipArchiveFactory()
    )

    private val resourceFactory = CompositeResourceFactory(
        FileResourceFactory(),
        CompositeResourceFactory(
            ContentResourceFactory(context.contentResolver),
            HttpResourceFactory(httpClient)
        )
    )

    private val containerFactory = DirectoryContainerFactory()

    private val mediaTypeRetriever = MediaTypeRetriever(
        resourceFactory,
        containerFactory,
        archiveFactory
    )

    val assetRetriever = AssetRetriever(
        resourceFactory,
        containerFactory,
        archiveFactory,
        context.contentResolver,
        MediaType.sniffers
    )

    /**
     * The LCP service decrypts LCP-protected publication and acquire publications from a
     * license file.
     */
    val lcpService = LcpService(context, mediaTypeRetriever, resourceFactory, archiveFactory)
        ?.let { Try.success(it) }
        ?: Try.failure(UserException("liblcp is missing on the classpath"))

    private val contentProtections = listOfNotNull(
        lcpService.getOrNull()?.contentProtection()
    )

    val protectionRetriever = ContentProtectionSchemeRetriever(contentProtections)

    /**
     * The PublicationFactory is used to parse and open publications.
     */
    val publicationFactory = PublicationFactory(
        context,
        contentProtections = contentProtections,
        // Only required if you want to support PDF files using the PDFium adapter.
        pdfFactory = PdfiumDocumentFactory(context)
    )
}

@OptIn(ExperimentalReadiumApi::class)
val FontFamily.Companion.LITERATA: FontFamily get() = FontFamily("Literata")
