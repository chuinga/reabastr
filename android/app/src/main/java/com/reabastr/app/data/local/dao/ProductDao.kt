package com.reabastr.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.reabastr.app.data.local.entity.ProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Query("SELECT * FROM products WHERE householdId = :householdId")
    fun getProductsByHousehold(householdId: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products LIMIT 1")
    suspend fun getAllProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE productId = :productId")
    suspend fun getProductById(productId: String): ProductEntity?

    @Query("SELECT * FROM products WHERE householdId = :householdId AND eans LIKE '%' || :ean || '%'")
    suspend fun getProductByEan(householdId: String, ean: String): ProductEntity?

    @Query("UPDATE products SET currentQty = currentQty + :delta WHERE productId = :productId")
    suspend fun applyDelta(productId: String, delta: Int)

    @Query("UPDATE products SET categoryId = :toCategoryId WHERE categoryId = :fromCategoryId")
    suspend fun reassignCategory(fromCategoryId: String, toCategoryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun update(product: ProductEntity)

    @Delete
    suspend fun delete(product: ProductEntity)

    @Query("DELETE FROM products WHERE productId = :productId")
    suspend fun deleteById(productId: String)

    @Query("DELETE FROM products WHERE householdId = :householdId")
    suspend fun deleteAllByHousehold(householdId: String)
}
