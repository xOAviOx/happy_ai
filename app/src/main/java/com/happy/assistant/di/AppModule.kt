package com.happy.assistant.di

import android.content.Context
import androidx.room.Room
import com.happy.assistant.data.HappyDatabase
import com.happy.assistant.data.LogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HappyDatabase =
        Room.databaseBuilder(context, HappyDatabase::class.java, "happy.db")
            // A debug diary is not worth a migration path; losing it on a schema
            // bump is fine.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideLogDao(db: HappyDatabase): LogDao = db.logDao()
}
