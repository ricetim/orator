package com.akouo.core.database

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
    fun provideDatabase(@ApplicationContext context: Context): AkouoDatabase =
        Room.databaseBuilder(context, AkouoDatabase::class.java, "akouo.db").build()

    @Provides
    fun provideBookDao(db: AkouoDatabase): BookDao = db.bookDao()

    @Provides
    fun provideChapterDao(db: AkouoDatabase): ChapterDao = db.chapterDao()

    @Provides
    fun provideBookmarkDao(db: AkouoDatabase): BookmarkDao = db.bookmarkDao()
}
