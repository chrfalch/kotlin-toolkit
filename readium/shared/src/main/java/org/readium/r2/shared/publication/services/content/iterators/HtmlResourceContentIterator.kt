/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.publication.services.content.iterators

import org.jsoup.Jsoup
import org.jsoup.internal.StringUtil
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.error.getOrThrow
import org.readium.r2.shared.extensions.tryOrNull
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.PublicationServicesHolder
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.Content.Attribute
import org.readium.r2.shared.publication.services.content.Content.AttributeKey
import org.readium.r2.shared.publication.services.content.Content.AudioElement
import org.readium.r2.shared.publication.services.content.Content.ImageElement
import org.readium.r2.shared.publication.services.content.Content.TextElement
import org.readium.r2.shared.publication.services.content.Content.VideoElement
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.resource.Resource
import org.readium.r2.shared.resource.readAsString
import org.readium.r2.shared.util.Href
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.use

// FIXME: Support custom skipped elements?

/**
 * Iterates an HTML [resource], starting from the given [locator].
 *
 * If you want to start mid-resource, the [locator] must contain a `cssSelector` key in its
 * [Locator.Locations] object.
 *
 * If you want to start from the end of the resource, the [locator] must have a `progression` of 1.0.
 *
 * Locators will contain a `before` context of up to `beforeMaxLength` characters.
 */
@ExperimentalReadiumApi
public class HtmlResourceContentIterator internal constructor(
    private val resource: Resource,
    private val totalProgressionRange: ClosedRange<Double>?,
    private val locator: Locator,
    private val beforeMaxLength: Int = 50
) : Content.Iterator {

    public class Factory : ResourceContentIteratorFactory {
        override suspend fun create(
            manifest: Manifest,
            servicesHolder: PublicationServicesHolder,
            readingOrderIndex: Int,
            resource: Resource,
            locator: Locator
        ): Content.Iterator? {
            if (resource.mediaType().getOrNull()?.matchesAny(MediaType.HTML, MediaType.XHTML) != true) {
                return null
            }

            val positions = servicesHolder.positionsByReadingOrder()
            return HtmlResourceContentIterator(
                resource,
                totalProgressionRange = positions.getOrNull(readingOrderIndex)
                    ?.firstOrNull()?.locations?.totalProgression
                    ?.let { start ->
                        val end = positions.getOrNull(readingOrderIndex + 1)
                            ?.firstOrNull()?.locations?.totalProgression
                            ?: 1.0

                        start..end
                    },
                locator = locator
            )
        }
    }

    /**
     * [Content.Element] loaded with [hasPrevious] or [hasNext], associated with the move delta.
     */
    private data class ElementWithDelta(
        val element: Content.Element,
        val delta: Int
    )

    private var currentElement: ElementWithDelta? = null

    override suspend fun hasPrevious(): Boolean {
        if (currentElement?.delta == -1) return true

        val elements = elements()
        val index = (currentIndex ?: elements.startIndex) - 1

        val content = elements.elements.getOrNull(index)
            ?: return false

        currentIndex = index
        currentElement = ElementWithDelta(content, -1)
        return true
    }

    override fun previous(): Content.Element =
        currentElement
            ?.takeIf { it.delta == -1 }?.element
            ?.also { currentElement = null }
            ?: throw IllegalStateException("Called previous() without a successful call to hasPrevious() first")

    override suspend fun hasNext(): Boolean {
        if (currentElement?.delta == +1) return true

        val elements = elements()
        val index = (currentIndex ?: (elements.startIndex - 1)) + 1

        val content = elements.elements.getOrNull(index)
            ?: return false

        currentIndex = index
        currentElement = ElementWithDelta(content, +1)
        return true
    }

    override fun next(): Content.Element =
        currentElement
            ?.takeIf { it.delta == +1 }?.element
            ?.also { currentElement = null }
            ?: throw IllegalStateException("Called next() without a successful call to hasNext() first")

    private var currentIndex: Int? = null

    private suspend fun elements(): ParsedElements =
        parsedElements
            ?: parseElements().also { parsedElements = it }

    private var parsedElements: ParsedElements? = null

    private suspend fun parseElements(): ParsedElements {
        val document = resource.use { res ->
            val html = res.readAsString().getOrThrow()
            Jsoup.parse(html)
        }

        val contentParser = ContentParser(
            baseLocator = locator,
            startElement = locator.locations.cssSelector?.let {
                tryOrNull { document.selectFirst(it) }
            },
            beforeMaxLength = beforeMaxLength
        )
        NodeTraversor.traverse(contentParser, document.body())
        val elements = contentParser.result()
        val elementCount = elements.elements.size
        if (elementCount == 0) {
            return elements
        }

        return elements.copy(
            elements = elements.elements.mapIndexed { index, element ->
                val progression = index.toDouble() / elementCount
                element.copy(
                    progression = progression,
                    totalProgression = totalProgressionRange?.let {
                        totalProgressionRange.start + progression * (totalProgressionRange.endInclusive - totalProgressionRange.start)
                    }
                )
            }
        )
    }

    private fun Content.Element.copy(progression: Double?, totalProgression: Double?): Content.Element {
        fun Locator.update(): Locator =
            copyWithLocations(
                progression = progression,
                totalProgression = totalProgression
            )

        return when (this) {
            is TextElement -> copy(
                locator = locator.update(),
                segments = segments.map {
                    it.copy(locator = it.locator.update())
                }
            )
            is AudioElement -> copy(locator = locator.update())
            is VideoElement -> copy(locator = locator.update())
            is ImageElement -> copy(locator = locator.update())
            else -> this
        }
    }

    /**
     * Holds the result of parsing the HTML resource into a list of [Content.Element].
     *
     * The [startIndex] will be calculated from the element matched by the base [locator], if
     * possible. Defaults to 0.
     */
    public data class ParsedElements(
        val elements: List<Content.Element>,
        val startIndex: Int,
    )

    private class ContentParser(
        private val baseLocator: Locator,
        private val startElement: Element?,
        private val beforeMaxLength: Int
    ) : NodeVisitor {

        fun result() = ParsedElements(
            elements = elements,
            startIndex = if (baseLocator.locations.progression == 1.0) elements.size
            else startIndex
        )

        private val elements = mutableListOf<Content.Element>()
        private var startIndex = 0

        /** Segments accumulated for the current element. */
        private val segmentsAcc = mutableListOf<TextElement.Segment>()
        /** Text since the beginning of the current segment, after coalescing whitespaces. */
        private var textAcc = StringBuilder()
        /** Text content since the beginning of the resource, including whitespaces. */
        private var wholeRawTextAcc: String? = null
        /** Text content since the beginning of the current element, including whitespaces. */
        private var elementRawTextAcc: String = ""
        /** Text content since the beginning of the current segment, including whitespaces. */
        private var rawTextAcc: String = ""
        /** Language of the current segment. */
        private var currentLanguage: String? = null
        /** CSS selector of the current element. */
        private var currentCssSelector: String? = null

        /** LIFO stack of the current element's block ancestors. */
        private val breadcrumbs = mutableListOf<Element>()

        override fun head(node: Node, depth: Int) {
            if (node is Element) {
                if (node.isBlock) {
                    breadcrumbs.add(node)
                }

                val tag = node.normalName()

                val elementLocator: Locator by lazy {
                    baseLocator.copy(
                        locations = Locator.Locations(
                            otherLocations = buildMap {
                                put("cssSelector", node.cssSelector() as Any)
                            }
                        )
                    )
                }

                when {
                    tag == "br" -> {
                        flushText()
                    }

                    tag == "img" -> {
                        flushText()

                        node.srcRelativeToHref(baseLocator.href)?.let { href ->
                            elements.add(
                                ImageElement(
                                    locator = elementLocator,
                                    embeddedLink = Link(href = href),
                                    caption = null, // FIXME: Get the caption from figcaption
                                    attributes = buildList {
                                        val alt = node.attr("alt").takeIf { it.isNotBlank() }
                                        if (alt != null) {
                                            add(Attribute(AttributeKey.ACCESSIBILITY_LABEL, alt))
                                        }
                                    }
                                )
                            )
                        }
                    }

                    tag == "audio" || tag == "video" -> {
                        flushText()

                        val href = node.srcRelativeToHref(baseLocator.href)
                        val link: Link? =
                            if (href != null) {
                                Link(href = href)
                            } else {
                                val sources = node.select("source")
                                    .mapNotNull { source ->
                                        source.srcRelativeToHref(baseLocator.href)?.let { href ->
                                            Link(href = href, type = source.attr("type").takeUnless { it.isBlank() })
                                        }
                                    }

                                sources.firstOrNull()?.copy(alternates = sources.drop(1))
                            }

                        if (link != null) {
                            when (tag) {
                                "audio" -> elements.add(AudioElement(locator = elementLocator, embeddedLink = link, attributes = emptyList()))
                                "video" -> elements.add(VideoElement(locator = elementLocator, embeddedLink = link, attributes = emptyList()))
                                else -> {}
                            }
                        }
                    }

                    node.isBlock -> {
                        flushText()
                        currentCssSelector = node.cssSelector()
                    }
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is TextNode && node.wholeText.isNotBlank()) {
                val language = node.language
                if (currentLanguage != language) {
                    flushSegment()
                    currentLanguage = language
                }

                val text = Parser.unescapeEntities(node.wholeText, false)
                rawTextAcc += text
                appendNormalisedText(text)
            } else if (node is Element) {
                if (node.isBlock) {
                    assert(breadcrumbs.last() == node)
                    flushText()
                    breadcrumbs.removeLast()
                }
            }
        }

        private fun appendNormalisedText(text: String) {
            StringUtil.appendNormalisedWhitespace(textAcc, text, lastCharIsWhitespace())
        }

        private fun lastCharIsWhitespace(): Boolean =
            textAcc.lastOrNull() == ' '

        private fun flushText() {
            flushSegment()

            if (startIndex == 0 && startElement != null && breadcrumbs.lastOrNull() == startElement) {
                startIndex = elements.size
            }

            if (segmentsAcc.isEmpty()) return

            // Trim the end of the last segment's text to get a cleaner output for the TextElement.
            // Only whitespaces between the segments are meaningful.
            segmentsAcc[segmentsAcc.size - 1] = segmentsAcc.last().run { copy(text = text.trimEnd()) }

            elements.add(
                TextElement(
                    locator = baseLocator.copy(
                        locations = Locator.Locations(
                            otherLocations = buildMap {
                                currentCssSelector?.let {
                                    put("cssSelector", it as Any)
                                }
                            }
                        ),
                        text = Locator.Text.trimmingText(
                            elementRawTextAcc,
                            before = segmentsAcc.firstOrNull()?.locator?.text?.before
                        )
                    ),
                    role = TextElement.Role.Body,
                    segments = segmentsAcc.toList()
                )
            )
            elementRawTextAcc = ""
            segmentsAcc.clear()
        }

        private fun flushSegment() {
            var text = textAcc.toString()
            val trimmedText = text.trim()

            if (text.isNotBlank()) {
                if (segmentsAcc.isEmpty()) {
                    text = text.trimStart()

                    val whitespaceSuffix = text.lastOrNull()
                        ?.takeIf { it.isWhitespace() }
                        ?: ""

                    text = trimmedText + whitespaceSuffix
                }

                segmentsAcc.add(
                    TextElement.Segment(
                        locator = baseLocator.copy(
                            locations = Locator.Locations(
                                otherLocations = buildMap {
                                    currentCssSelector?.let {
                                        put("cssSelector", it as Any)
                                    }
                                }
                            ),
                            text = Locator.Text.trimmingText(
                                rawTextAcc,
                                before = wholeRawTextAcc?.takeLast(beforeMaxLength)
                            )
                        ),
                        text = text,
                        attributes = buildList {
                            currentLanguage?.let {
                                add(Attribute(AttributeKey.LANGUAGE, Language(it)))
                            }
                        },
                    )
                )
            }

            if (rawTextAcc != "") {
                wholeRawTextAcc = (wholeRawTextAcc ?: "") + rawTextAcc
                elementRawTextAcc += rawTextAcc
            }
            rawTextAcc = ""
            textAcc.clear()
        }
    }
}

private fun Locator.Text.Companion.trimmingText(text: String, before: String?): Locator.Text {
    val leadingWhitespace = text.takeWhile { it.isWhitespace() }
    val trailingWhitespace = text.takeLastWhile { it.isWhitespace() }
    return Locator.Text(
        before = ((before ?: "") + leadingWhitespace).takeUnless { it.isBlank() },
        highlight = text.substring(leadingWhitespace.length, text.length - trailingWhitespace.length),
        after = trailingWhitespace.takeUnless { it.isBlank() }
    )
}

private val Node.language: String? get() =
    attr("xml:lang").takeUnless { it.isBlank() }
        ?: attr("lang").takeUnless { it.isBlank() }
        ?: parent()?.language

private fun Node.srcRelativeToHref(baseHref: String): String? =
    attr("src")
        .takeIf { it.isNotBlank() }
        ?.let { Href(it, baseHref).string }
