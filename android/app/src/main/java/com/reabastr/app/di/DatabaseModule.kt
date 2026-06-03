package com.reabastr.app.di

import android.content.Context
import androidx.room.Room
import com.reabastr.app.data.local.ReabastrDatabase
import com.reabastr.app.data.local.dao.CategoryDao
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.dao.ProductDao
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
    fun provideDatabase(@ApplicationContext context: Context): ReabastrDatabase {
        return Room.databaseBuilder(
            context,
            ReabastrDatabase::class.java,
            "reabastr.db"
        ).build()
    }

    @Provides
    fun provideProductDao(database: ReabastrDatabase): ProductDao {
        return database.productDao()
    }

    @Provides
    fun provideCategoryDao(database: ReabastrDatabase): CategoryDao {
        return database.categoryDao()
    }

    @Provides
    fun provideOutboxDao(database: ReabastrDatabase): OutboxDao {
        return database.outboxDao()
    }
}
