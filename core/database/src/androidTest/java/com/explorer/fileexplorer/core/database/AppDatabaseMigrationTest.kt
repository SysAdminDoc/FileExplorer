package com.explorer.fileexplorer.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrateAllVersionsPreservesRepresentativeRows() {
        migrationHelper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                "INSERT INTO bookmarks (name, path, sort_order, created_at) VALUES (?, ?, ?, ?)",
                arrayOf("Documents", "/storage/emulated/0/Documents", 4, 1_700_000_000_000L),
            )
            execSQL(
                "INSERT INTO recent_files (name, path, mime_type, size, is_directory, accessed_at) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("notes.txt", "/storage/emulated/0/notes.txt", "text/plain", 12, 0, 1_700_000_000_001L),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )

        migrated.query("SELECT name, path, sort_order FROM bookmarks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Documents", cursor.getString(0))
            assertEquals("/storage/emulated/0/Documents", cursor.getString(1))
            assertEquals(4, cursor.getInt(2))
        }
        migrated.query("SELECT name, mime_type, size FROM recent_files").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("notes.txt", cursor.getString(0))
            assertEquals("text/plain", cursor.getString(1))
            assertEquals(12L, cursor.getLong(2))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            val tables = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue("transfer_tasks" in tables)
            assertTrue("recent_locations" in tables)
            assertTrue("saved_searches" in tables)
        }
        migrated.query("PRAGMA table_info(transfer_tasks)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("intended_entries" in columns)
            assertTrue("committed_entries" in columns)
            assertTrue("recovery_policy" in columns)
            assertTrue("conflict_decisions" in columns)
            assertTrue("conflict_planned_keep_both_path" in columns)
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "migration-contract.db"
    }
}
