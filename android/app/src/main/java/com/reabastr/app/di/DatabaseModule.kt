package com.reabastr.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /**
     * v1 → v2: adds the `refs` column (free-form product reference codes), stored
     * as a JSON array string like `eans`. Defaults to an empty list for existing rows.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE products ADD COLUMN refs TEXT NOT NULL DEFAULT '[]'")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ReabastrDatabase {
        return Room.databaseBuilder(
            context,
            ReabastrDatabase::class.java,
            "reabastr.db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
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
