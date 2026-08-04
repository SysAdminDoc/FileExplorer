package com.explorer.fileexplorer.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        BookmarkEntity::class,
        RecentFileEntity::class,
        RecentLocationEntity::class,
        SearchHistoryEntity::class,
        SavedSearchEntity::class,
        ConnectionEntity::class,
        DirectoryViewPreferenceEntity::class,
        IntegrityEntryEntity::class,
        TagEntity::class,
        FileTagEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun recentLocationDao(): RecentLocationDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun directoryViewPreferenceDao(): DirectoryViewPreferenceDao
    abstract fun integrityDao(): IntegrityDao
    abstract fun tagDao(): TagDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "file_explorer.db"
        ).addMigrations(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        ).build()
    }

    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideRecentFileDao(db: AppDatabase): RecentFileDao = db.recentFileDao()
    @Provides fun provideRecentLocationDao(db: AppDatabase): RecentLocationDao = db.recentLocationDao()
    @Provides fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideSavedSearchDao(db: AppDatabase): SavedSearchDao = db.savedSearchDao()
    @Provides fun provideConnectionDao(db: AppDatabase): ConnectionDao = db.connectionDao()
    @Provides
    fun provideDirectoryViewPreferenceDao(db: AppDatabase): DirectoryViewPreferenceDao =
        db.directoryViewPreferenceDao()
    @Provides fun provideIntegrityDao(db: AppDatabase): IntegrityDao = db.integrityDao()
    @Provides fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `directory_view_preferences` (
                `path` TEXT NOT NULL,
                `view_mode` TEXT NOT NULL,
                `sort_field` TEXT NOT NULL,
                `sort_direction` TEXT NOT NULL,
                `folders_first` INTEGER NOT NULL,
                `visible_columns` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`path`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `integrity_entries` (
                `path` TEXT NOT NULL,
                `sha256` TEXT NOT NULL,
                `size` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                `is_directory` INTEGER NOT NULL,
                `added_at` INTEGER NOT NULL,
                `last_checked_at` INTEGER,
                `status` TEXT NOT NULL,
                `last_error` TEXT,
                PRIMARY KEY(`path`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tags` (
                `name` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`name`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `file_tags` (
                `path` TEXT NOT NULL,
                `tag_name` TEXT NOT NULL,
                PRIMARY KEY(`path`, `tag_name`),
                FOREIGN KEY(`tag_name`) REFERENCES `tags`(`name`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_tags_tag_name` ON `file_tags` (`tag_name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_tags_path` ON `file_tags` (`path`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_searches` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `query` TEXT NOT NULL,
                `scope_path` TEXT NOT NULL,
                `use_regex` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_searches_name` ON `saved_searches` (`name`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recent_locations` (
                `path` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `visited_at` INTEGER NOT NULL,
                PRIMARY KEY(`path`)
            )
            """.trimIndent(),
        )
    }
}
