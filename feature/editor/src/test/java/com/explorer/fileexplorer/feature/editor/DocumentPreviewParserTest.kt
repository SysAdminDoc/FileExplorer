package com.explorer.fileexplorer.feature.editor

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentPreviewParserTest {
    @Test
    fun readsDocxParagraphsFromWordXml() {
        val file = zipFile(
            ".docx",
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>First </w:t></w:r><w:r><w:t>paragraph</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
        )
        try {
            val preview = DocumentPreviewParser.parse(file.path)

            assertEquals(PreviewDocumentType.DOCX, preview.type)
            assertEquals(listOf("First paragraph", "Second paragraph"), preview.paragraphs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsXlsxSharedStringsAndSparseColumns() {
        val file = zipFile(
            ".xlsx",
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Name</t></si><si><t>Widget</t></si><si><t>Count</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>2</v></c></row>
                    <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>2</v></c></row>
                  </sheetData>
                </worksheet>
            """.trimIndent(),
        )
        try {
            val preview = DocumentPreviewParser.parse(file.path)

            assertEquals(PreviewDocumentType.XLSX, preview.type)
            assertEquals(listOf("Name", "", "Count"), preview.rows[0])
            assertEquals(listOf("Widget", "2"), preview.rows[1])
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsEpubPackageAndSpineOrder() {
        val file = zipFile(
            ".epub",
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent(),
            "OPS/package.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test book</dc:title></metadata>
                  <manifest>
                    <item id="second" href="text/second.xhtml" media-type="application/xhtml+xml"/>
                    <item id="first" href="text/first.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="first"/><itemref idref="second"/></spine>
                </package>
            """.trimIndent(),
            "OPS/text/first.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>First</h1><p>One.</p></body></html>",
            "OPS/text/second.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Second</h1><p>Two.</p></body></html>",
        )
        try {
            val preview = DocumentPreviewParser.parse(file.path)

            assertEquals(PreviewDocumentType.EPUB, preview.type)
            assertEquals("Test book", preview.title)
            assertEquals(listOf("First", "Second"), preview.epubChapters.map(EpubChapter::title))
            assertEquals(listOf("First", "One."), preview.epubChapters.first().paragraphs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsXmlDoctypeBeforeParsing() {
        val file = zipFile(
            ".docx",
            "word/document.xml" to """
                <!DOCTYPE document [<!ENTITY secret "should not load">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body>
                </w:document>
            """.trimIndent(),
        )
        try {
            assertFailsWith<Exception> { DocumentPreviewParser.parse(file.path) }
        } finally {
            file.delete()
        }
    }

    private fun zipFile(suffix: String, vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("file-explorer-preview-", suffix)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
