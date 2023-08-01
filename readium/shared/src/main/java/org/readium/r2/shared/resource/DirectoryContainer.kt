/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.resource

import android.content.ContentResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.error.Try
import org.readium.r2.shared.extensions.addPrefix
import org.readium.r2.shared.extensions.isParentOf
import org.readium.r2.shared.extensions.tryOr
import org.readium.r2.shared.util.Url

/**
 * A file system directory as a [Container].
 */
internal class DirectoryContainer(
    private val root: File,
    private val entries: List<File>
) : Container {

    private inner class FileEntry(file: File)
        : Container.Entry, Resource by FileResource(file, mediaType = null) {

        override val path: String =
            file.relativeTo(root).path.addPrefix("/")

        override suspend fun close() {}
    }

    override suspend fun entries(): List<Container.Entry> =
        entries.map { FileEntry(it) }.toList()

    override suspend fun get(path: String): Container.Entry {
        val file = File(root, path.removePrefix("/"))

        return if (!root.isParentOf(file))
            FailureResource(Resource.Exception.NotFound()).toEntry(path)
        else
            FileEntry(file)
    }

    override suspend fun close() {}
}

public class DirectoryContainerFactory : ContainerFactory {

    override suspend fun create(url: Url): Try<Container, ContainerFactory.Error> {
        if (url.scheme != ContentResolver.SCHEME_FILE) {
            return Try.failure(ContainerFactory.Error.SchemeNotSupported(url.scheme))
        }

        val file = File(url.path)

        if (!tryOr(false) { file.isDirectory }) {
            return Try.failure(ContainerFactory.Error.NotAContainer(url))
        }

        return create(file)
    }

    // Internal for testing purpose
    internal suspend fun create(file: File): Try<Container, ContainerFactory.Error> {
        val entries =
            try {
                withContext(Dispatchers.IO) {
                    file.walk()
                        .filter { it.isFile }
                        .toList()
                }
            } catch (e: Exception) {
                return Try.failure(ContainerFactory.Error.Forbidden(e))
            }

        val container = DirectoryContainer(file, entries)

        return Try.success(container)
    }
}
