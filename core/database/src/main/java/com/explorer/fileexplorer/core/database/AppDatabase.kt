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
        SearchHistoryEntity::class,
        ConnectionEntity::class,
        DirectoryViewPreferenceEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun directoryViewPreferenceDao(): DirectoryViewPreferenceDao
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
        ).addMigrations(MIGRATION_2_3).build()
    }

    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideRecentFileDao(db: AppDatabase): RecentFileDao = db.recentFileDao()
    @Provides fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideConnectionDao(db: AppDatabase): ConnectionDao = db.connectionDao()
    @Provides
    fun provideDirectoryViewPreferenceDao(db: AppDatabase): DirectoryViewPreferenceDao =
        db.directoryViewPreferenceDao()
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
