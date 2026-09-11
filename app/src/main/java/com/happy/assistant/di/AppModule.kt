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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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

    /**
     * One client for the whole app (spec section 3). Building an OkHttpClient per
     * request throws away the connection pool, which is most of what makes the
     * second request fast.
     */
    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Lenient on purpose: these APIs add fields without warning, and an unknown
     * key must never be the reason Happy cannot answer a question.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}
