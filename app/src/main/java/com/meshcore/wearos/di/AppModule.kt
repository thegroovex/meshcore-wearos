package com.meshcore.wearos.di

import android.content.Context
import androidx.room.Room
import com.meshcore.wearos.data.db.ChannelDao
import com.meshcore.wearos.data.db.ContactDao
import com.meshcore.wearos.data.db.MeshCoreDatabase
import com.meshcore.wearos.data.db.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-scoped dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MeshCoreDatabase {
        return Room.databaseBuilder(
            context,
            MeshCoreDatabase::class.java,
            "meshcore_wearos.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideContactDao(database: MeshCoreDatabase): ContactDao = database.contactDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: MeshCoreDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideChannelDao(database: MeshCoreDatabase): ChannelDao = database.channelDao()
}
