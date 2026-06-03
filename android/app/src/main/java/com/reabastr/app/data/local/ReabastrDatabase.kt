package com.reabastr.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.reabastr.app.data.local.converter.Converters
import com.reabastr.app.data.local.dao.CategoryDao
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.dao.ProductDao
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.data.local.entity.ProductEntity

@Database(
    entities = [ProductEntity::class, CategoryEntity::class, OutboxEvent::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ReabastrDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun outboxDao(): OutboxDao
}
