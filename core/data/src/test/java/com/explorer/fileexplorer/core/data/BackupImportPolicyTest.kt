package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.database.BookmarkEntity
import com.explorer.fileexplorer.core.database.ConnectionEntity
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupImportPolicyTest {
    @Test
    fun rejectsUnsupportedVersionsBeforeBuildingPayload() {
        assertFailsWith<BackupFormatException> {
            parse("{\"version\":${BackupManager.VERSION + 1},\"app\":\"FileExplorer\"}")
        }
    }

    @Test
    fun rejectsOversizedStreamsBeforeJsonParsing() {
        val oversized = ByteArray(BackupImportPolicy.MAX_IMPORT_BYTES + 1) { ' '.code.toByte() }

        assertFailsWith<BackupFormatException> {
            BackupImportPolicy.parse(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun enforcesRecordAndStringLimits() {
        val tooManyBookmarks = (0..BackupImportPolicy.MAX_RECORDS).joinToString(
            prefix = "{\"version\":1,\"app\":\"FileExplorer\",\"bookmarks\":[",
            postfix = "]}",
        ) { "{\"name\":\"Bookmark\",\"path\":\"/$it\"}" }
        val tooLongPath = "{\"version\":1,\"app\":\"FileExplorer\",\"bookmarks\":[" +
            "{\"name\":\"Bookmark\",\"path\":\"${"x".repeat(BackupImportPolicy.MAX_STRING_CHARS + 1)}\"}" +
            "]}"

        assertFailsWith<BackupFormatException> {
            parse(tooManyBookmarks)
        }
        assertFailsWith<BackupFormatException> { parse(tooLongPath) }
    }

    @Test
    fun rejectsCredentialFieldsInsteadOfSilentlyImportingThem() {
        assertFailsWith<BackupFormatException> {
            parse(
                """{"version":1,"app":"FileExplorer","connections":[{"name":"NAS","protocol":"smb","host":"nas.example","port":445,"password":"should-not-cross-the-boundary"}]}""",
            )
        }
    }

    @Test
    fun parsesPortableSettingsAndKeepsThemInTheImportPlan() {
        val payload = parse(
            """{
                "version":2,
                "app":"FileExplorer",
                "settings":{
                    "showHidden":true,
                    "foldersFirst":false,
                    "confirmDelete":true,
                    "defaultView":"GRID",
                    "sortField":"SIZE",
                    "sortDirection":"DESCENDING",
                    "thumbnailSize":96,
                    "thumbnailCacheSizeMb":512,
                    "thumbnailCacheLocation":"external",
                    "themeMode":"OLED",
                    "trashTtlDays":60,
                    "compactDensity":true,
                    "showDirectorySizes":true,
                    "swipeLeftAction":"DELETE",
                    "swipeRightAction":"MOVE"
                }
            }""".replace("\n", "").replace(" ", ""),
        )

        val settings = payload.settings
        assertEquals("GRID", settings?.defaultView)
        assertEquals(60, settings?.trashTtlDays)
        assertEquals("external", settings?.thumbnailCacheLocation)
        assertEquals(settings, buildBackupImportPlan(payload, emptyList(), emptyList()).settingsToApply)
    }

    @Test
    fun rejectsUnknownPortableSettingValuesAndForbiddenStores() {
        assertFailsWith<BackupFormatException> {
            parse(
                """{"version":2,"app":"FileExplorer","settings":{"showHidden":false,"foldersFirst":true,"confirmDelete":false,"defaultView":"LIST","sortField":"NAME","sortDirection":"ASCENDING","thumbnailSize":48,"thumbnailCacheSizeMb":256,"thumbnailCacheLocation":"internal","themeMode":"SYSTEM","trashTtlDays":30,"compactDensity":false,"showDirectorySizes":false,"swipeLeftAction":"FUTURE","swipeRightAction":"NONE"}}""",
            )
        }
        assertFailsWith<BackupFormatException> {
            parse("""{"version":2,"app":"FileExplorer","vault":{}}""")
        }
    }

    @Test
    fun duplicatePolicyKeepsFirstPayloadRecordAndSkipsExistingRows() {
        val payload = BackupPayload(
            bookmarks = listOf(
                BackupBookmark("First", "/new", 1),
                BackupBookmark("Second", "/new", 2),
                BackupBookmark("Existing", "/existing", 3),
            ),
            connections = listOf(
                connection(name = "First"),
                connection(name = "First"),
                connection(name = "Existing"),
            ),
        )

        val plan = buildBackupImportPlan(
            payload = payload,
            existingBookmarks = listOf(BookmarkEntity(name = "Already there", path = "/existing")),
            existingConnections = listOf(connectionEntity(name = "Existing")),
        )

        assertEquals(listOf("/new"), plan.bookmarksToInsert.map { it.path })
        assertEquals(2, plan.skippedBookmarks)
        assertEquals(listOf("First"), plan.connectionsToInsert.map { it.name })
        assertEquals(2, plan.skippedConnections)
        assertEquals("", plan.connectionsToInsert.single().toEntity().password)
        assertEquals("", plan.connectionsToInsert.single().toEntity().privateKeyPath)
    }

    private fun parse(json: String): BackupPayload =
        BackupImportPolicy.parse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))

    private fun connection(name: String): BackupConnection = BackupConnection(
        name = name,
        protocol = "smb",
        host = "nas.example",
        port = 445,
        username = "user",
        shareName = "files",
        remotePath = "/",
        useTls = false,
    )

    private fun connectionEntity(name: String): ConnectionEntity = ConnectionEntity(
        name = name,
        protocol = "smb",
        host = "nas.example",
        port = 445,
        username = "user",
        password = "enc:existing-secret",
        shareName = "files",
        remotePath = "/",
        privateKeyPath = "/keys/existing",
        useTls = false,
    )
}
