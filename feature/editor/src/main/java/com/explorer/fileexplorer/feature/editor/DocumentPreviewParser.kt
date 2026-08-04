package com.explorer.fileexplorer.feature.editor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

enum class PreviewDocumentType {
    PDF,
    DOCX,
    XLSX,
    EPUB,
}

data class EpubChapter(
    val title: String,
    val paragraphs: List<String>,
)

data class DocumentPreviewData(
    val type: PreviewDocumentType,
    val paragraphs: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val title: String = "",
    val epubChapters: List<EpubChapter> = emptyList(),
)

object DocumentPreviewParser {
    private const val MAX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_PARAGRAPHS = 2_000
    private const val MAX_ROWS = 500
    private const val MAX_COLUMNS = 50

    fun parse(path: String): DocumentPreviewData {
        val extension = File(path).extension.lowercase()
        return ZipFile(path).use { zip ->
            when (extension) {
                "docx" -> {
                    val document = zip.readXml("word/document.xml")
                        ?: error("DOCX document.xml is missing")
                    DocumentPreviewData(
                        type = PreviewDocumentType.DOCX,
                        paragraphs = paragraphs(document),
                    )
                }

                "xlsx" -> {
                    val sheetEntry = zip.entries().asSequence()
                        .firstOrNull { it.name.matches(SHEET_ENTRY_PATTERN) }
                        ?: error("XLSX worksheet is missing")
                    val sharedStrings = zip.getEntry("xl/sharedStrings.xml")
                        ?.let { zip.readXml(it) }
                        .orEmptySharedStrings()
                    val sheet = zip.readXml(sheetEntry)
                        ?: error("XLSX worksheet is unreadable")
                    DocumentPreviewData(
                        type = PreviewDocumentType.XLSX,
                        rows = rows(sheet, sharedStrings),
                    )
                }

                "epub" -> parseEpub(zip)

                else -> error("Unsupported preview format: $extension")
            }
        }
    }

    private fun paragraphs(document: Document): List<String> = descendants(document, "p")
        .asSequence()
        .map { element -> collectText(element).replace('\u00a0', ' ').trim() }
        .filter(String::isNotEmpty)
        .take(MAX_PARAGRAPHS)
        .toList()

    private fun rows(document: Document, sharedStrings: List<String>): List<List<String>> = descendants(document, "row")
        .asSequence()
        .take(MAX_ROWS)
        .map { row ->
            val values = mutableListOf<String>()
            descendants(row, "c").forEach { cell ->
                val column = columnIndex(cell.getAttribute("r")) ?: values.size
                while (values.size <= column && values.size < MAX_COLUMNS) values += ""
                if (column >= MAX_COLUMNS) return@forEach
                values[column] = cellValue(cell, sharedStrings)
            }
            values.take(MAX_COLUMNS)
        }
        .filter { row -> row.any(String::isNotEmpty) }
        .toList()

    private fun parseEpub(zip: ZipFile): DocumentPreviewData {
        val container = zip.readXml("META-INF/container.xml")
            ?: error("EPUB container.xml is missing")
        val packagePath = descendants(container, "rootfile")
            .firstOrNull()
            ?.getAttribute("full-path")
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeZipPath)
            ?: error("EPUB package document is missing")
        val packageDocument = zip.readXml(packagePath)
            ?: error("EPUB package document is missing")
        val packageDirectory = packagePath.substringBeforeLast('/', "")
        val manifest = descendants(packageDocument, "item").associateBy(
            keySelector = { it.getAttribute("id") },
            valueTransform = { item ->
                EpubManifestItem(
                    href = item.getAttribute("href"),
                    mediaType = item.getAttribute("media-type"),
                )
            },
        )
        val spineItems = descendants(packageDocument, "itemref")
            .mapNotNull { manifest[it.getAttribute("idref")] }
            .filter { it.mediaType in EPUB_HTML_MEDIA_TYPES && it.href.isNotBlank() }
        val fallbackItems = manifest.values
            .filter { it.mediaType in EPUB_HTML_MEDIA_TYPES && it.href.isNotBlank() }
        val orderedItems = (spineItems.ifEmpty { fallbackItems }).take(MAX_EPUB_CHAPTERS)
        val chapters = orderedItems.mapIndexedNotNull { index, item ->
            val chapterPath = resolveZipPath(packageDirectory, item.href)
            val chapterDocument = zip.readXml(chapterPath) ?: return@mapIndexedNotNull null
            val blocks = epubBlocks(chapterDocument)
            if (blocks.isEmpty()) return@mapIndexedNotNull null
            val heading = blocks.firstOrNull { it.first }?.second
                ?: "Chapter ${index + 1}"
            EpubChapter(
                title = heading,
                paragraphs = blocks.map { it.second },
            )
        }
        require(chapters.isNotEmpty()) { "EPUB contains no readable chapters" }
        return DocumentPreviewData(
            type = PreviewDocumentType.EPUB,
            title = descendants(packageDocument, "title")
                .firstOrNull()
                ?.textContent
                ?.trim()
                .orEmpty(),
            epubChapters = chapters,
        )
    }

    private fun epubBlocks(document: Document): List<Pair<Boolean, String>> {
        val blockNames = setOf("h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "blockquote", "pre")
        val blocks = descendants(document, "*")
            .asSequence()
            .filter { localName(it) in blockNames }
            .mapNotNull { element ->
                val text = collectText(element).replace('\u00a0', ' ').trim()
                text.takeIf(String::isNotEmpty)?.let { localName(element).startsWith("h") to it }
            }
            .take(MAX_EPUB_PARAGRAPHS)
            .toList()
        if (blocks.isNotEmpty()) return blocks
        val fallback = descendants(document, "body")
            .firstOrNull()
            ?.let(::collectText)
            ?.replace('\u00a0', ' ')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return fallback?.let { listOf(false to it) }.orEmpty()
    }

    private fun cellValue(cell: Element, sharedStrings: List<String>): String {
        val type = cell.getAttribute("t")
        return when (type) {
            "s" -> descendants(cell, "v").firstOrNull()?.textContent?.trim()?.toIntOrNull()
                ?.let(sharedStrings::getOrNull).orEmpty()
            "inlineStr" -> descendants(cell, "is").firstOrNull()?.let(::collectText).orEmpty()
            "b" -> if (descendants(cell, "v").firstOrNull()?.textContent?.trim() == "1") "TRUE" else "FALSE"
            else -> descendants(cell, "v").firstOrNull()?.textContent?.trim().orEmpty()
        }
    }

    private fun columnIndex(reference: String): Int? {
        if (reference.isBlank()) return null
        var value = 0
        var found = false
        for (character in reference) {
            if (!character.isLetter()) break
            found = true
            value = value * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return if (found) value - 1 else null
    }

    private fun collectText(node: Node): String {
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            return node.nodeValue.orEmpty()
        }
        val output = StringBuilder()
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            when (localName(child)) {
                "tab" -> output.append('\t')
                "br", "cr" -> output.append('\n')
                else -> output.append(collectText(child))
            }
        }
        return output.toString()
    }

    private fun descendants(document: Document, localName: String): List<Element> =
        (0 until document.getElementsByTagNameNS("*", localName).length)
            .map { document.getElementsByTagNameNS("*", localName).item(it) as Element }

    private fun descendants(element: Element, localName: String): List<Element> =
        (0 until element.getElementsByTagNameNS("*", localName).length)
            .map { element.getElementsByTagNameNS("*", localName).item(it) as Element }

    private fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')

    private fun ZipFile.readXml(name: String): Document? = getEntry(name)?.let { entry -> readXml(entry) }

    private fun ZipFile.readXml(entry: java.util.zip.ZipEntry): Document {
        return getInputStream(entry).use { input -> parseXml(input.readBounded()) }
    }

    private fun InputStream.readBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_XML_BYTES) { "Preview XML exceeds ${MAX_XML_BYTES / (1024 * 1024)} MiB" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Document?.orEmptySharedStrings(): List<String> = this?.let { document ->
        descendants(document, "si").map { collectText(it) }
    }.orEmpty()

    private fun resolveZipPath(baseDirectory: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#'), Charsets.UTF_8.name())
            .replace('\\', '/')
        return normalizeZipPath(listOf(baseDirectory, decoded).filter(String::isNotBlank).joinToString("/"))
    }

    private fun normalizeZipPath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> require(parts.isNotEmpty()) { "EPUB path escapes archive" }.also { parts.removeAt(parts.lastIndex) }
                else -> parts += part
            }
        }
        return parts.joinToString("/").also { require(it.isNotBlank()) { "EPUB path is empty" } }
    }

    private data class EpubManifestItem(
        val href: String,
        val mediaType: String,
    )

    private val SHEET_ENTRY_PATTERN = Regex("xl/worksheets/sheet\\d+\\.xml")
    private val EPUB_HTML_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")
    private const val MAX_EPUB_CHAPTERS = 100
    private const val MAX_EPUB_PARAGRAPHS = 10_000
}
