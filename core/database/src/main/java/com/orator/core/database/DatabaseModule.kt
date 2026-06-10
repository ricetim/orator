package com.orator.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OratorDatabase =
        Room.databaseBuilder(context, OratorDatabase::class.java, "orator.db").build()

    @Provides
    fun provideBookDao(db: OratorDatabase): BookDao = db.bookDao()

    @Provides
    fun provideChapterDao(db: OratorDatabase): ChapterDao = db.chapterDao()

    @Provides
    fun provideBookmarkDao(db: OratorDatabase): BookmarkDao = db.bookmarkDao()
}
