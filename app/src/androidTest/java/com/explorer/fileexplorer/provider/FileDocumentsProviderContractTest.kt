package com.explorer.fileexplorer.provider

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class FileDocumentsProviderContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var provider: FileDocumentsProvider
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        testRoot = File(
            context.getExternalFilesDir("documents-provider-contract") ?: context.filesDir,
            "run-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        val providerInfo = context.packageManager.resolveContentProvider(
            "${context.packageName}.documents",
            0,
        ) ?: error("DocumentsProvider manifest entry is missing")
        provider = FileDocumentsProvider(testRoot).also { it.attachInfo(context, providerInfo) }
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun rootsAndCrudOperationsExposeSafContract() {
        val rootId = queryRootId()
        queryRoots().use { cursor ->
            assertTrue(cursor.moveToFirst())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_LOCAL_ONLY != 0)
            assertEquals(
                testRoot.canWrite(),
                flags and Root.FLAG_SUPPORTS_CREATE != 0,
            )
            assertTrue(flags and Root.FLAG_SUPPORTS_SEARCH != 0)
            assertTrue(flags and Root.FLAG_SUPPORTS_RECENTS != 0)
            assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_SUMMARY)).isNotBlank())
        }

        val testRootId = rootId
        assertTrue(testRoot.isDirectory() && testRoot.canWrite(), "Test root is not writable: ${testRoot.canonicalPath}")
        val sourceDirectoryId = provider.createDocument(testRootId, Document.MIME_TYPE_DIR, "source")
        val targetDirectoryId = provider.createDocument(testRootId, Document.MIME_TYPE_DIR, "target")
        val destinationDirectoryId = provider.createDocument(testRootId, Document.MIME_TYPE_DIR, "destination")
        val fileId = provider.createDocument(sourceDirectoryId, "text/plain", "alpha.txt")
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(fileId, "w", null)).use { output ->
            output.write("provider payload".toByteArray())
        }

        queryDocument(fileId).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("alpha.txt", cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            assertEquals("text/plain", cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)))
            assertEquals(16L, cursor.getLong(cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            assertTrue(flags and Document.FLAG_SUPPORTS_WRITE != 0)
            assertTrue(flags and Document.FLAG_SUPPORTS_RENAME != 0)
            assertTrue(flags and Document.FLAG_SUPPORTS_COPY != 0)
            assertTrue(flags and Document.FLAG_SUPPORTS_MOVE != 0)
        }

        queryChildDocuments(sourceDirectoryId).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("alpha.txt", cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
        }
        assertTrue(provider.isChildDocument(rootId, sourceDirectoryId))
        assertFalse(provider.isChildDocument(sourceDirectoryId, sourceDirectoryId))

        val renamedId = provider.renameDocument(fileId, "renamed.txt")
        assertNotEquals(fileId, renamedId)
        val copiedId = provider.copyDocument(renamedId, targetDirectoryId)
        val movedId = provider.moveDocument(copiedId, targetDirectoryId, destinationDirectoryId)
        assertTrue(provider.isChildDocument(testRootId, movedId))
        assertTrue(File(testRoot, "source/renamed.txt").exists())

        provider.querySearchDocuments(rootId, "renamed", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("renamed.txt", cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
        }
        provider.queryRecentDocuments(rootId, null).use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            }
            assertTrue("renamed.txt" in names)
        }

        provider.deleteDocument(movedId)
        assertFalse(File(testRoot, "renamed.txt").exists())
        provider.deleteDocument(sourceDirectoryId)
        provider.deleteDocument(targetDirectoryId)
        provider.deleteDocument(destinationDirectoryId)
        assertTrue(testRoot.deleteRecursively())
    }

    @Test
    fun providerRejectsOutsidePathsRootDeletionAndUnsafeNames() {
        val rootId = queryRootId()
        assertFailsWith<FileNotFoundException> {
            provider.deleteDocument(rootId)
        }
        assertFailsWith<FileNotFoundException> {
            provider.createDocument(rootId, Document.MIME_TYPE_DIR, "unsafe/name")
        }

        val outside = File(context.filesDir, "documents-provider-outside-${UUID.randomUUID()}")
        assertFailsWith<FileNotFoundException> {
            provider.queryDocument(DocumentIdCodec.encode(outside.canonicalPath), null)
        }
    }

    private fun queryRootId(): String = queryRoots().use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID))
    }

    private fun queryRoots(): Cursor = provider.queryRoots(null)

    private fun queryDocument(documentId: String): Cursor = provider.queryDocument(documentId, null)

    @Suppress("DEPRECATION")
    private fun queryChildDocuments(parentDocumentId: String): Cursor =
        provider.queryChildDocuments(parentDocumentId, null, null as String?)
}
