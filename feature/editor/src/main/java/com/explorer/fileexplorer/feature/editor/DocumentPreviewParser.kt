package com.explorer.fileexplorer.feature.editor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

enum class PreviewDocumentType {
    PDF,
    DOCX,
    XLSX,
}

data class DocumentPreviewData(
    val type: PreviewDocumentType,
    val paragraphs: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
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

    private val SHEET_ENTRY_PATTERN = Regex("xl/worksheets/sheet\\d+\\.xml")
}
