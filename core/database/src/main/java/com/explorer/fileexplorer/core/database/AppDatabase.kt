package com.explorer.fileexplorer.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun connectionDao(): ConnectionDao
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
        ).fallbackToDestructiveMigration().build()
    }

    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideRecentFileDao(db: AppDatabase): RecentFileDao = db.recentFileDao()
    @Provides fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideConnectionDao(db: AppDatabase): ConnectionDao = db.connectionDao()
}
