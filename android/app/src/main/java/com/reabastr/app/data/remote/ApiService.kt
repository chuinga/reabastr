package com.reabastr.app.data.remote

import com.reabastr.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface defining all Reabastr backend API endpoints.
 * All requests are authenticated via [AuthInterceptor] injecting the JWT Bearer token.
 *
 * Returns [Response] wrappers to allow structured error parsing at the call site.
 */
interface ApiService {

    // --- Household ---

    @GET("household")
    suspend fun getHousehold(): Response<HouseholdResponse>

    @POST("household")
    suspend fun createHousehold(
        @Body request: CreateHouseholdRequest
    ): Response<HouseholdResponse>

    @POST("household/share-code")
    suspend fun generateShareCode(): Response<ShareCodeResponse>

    @POST("household/join")
    suspend fun joinHousehold(
        @Body request: JoinHouseholdRequest
    ): Response<HouseholdResponse>

    @POST("household/leave")
    suspend fun leaveHousehold(): Response<Unit>

    // --- Products ---

    @GET("products")
    suspend fun getProducts(): Response<List<ProductResponse>>

    @POST("products")
    suspend fun createProduct(
        @Body request: CreateProductRequest
    ): Response<ProductResponse>

    @PUT("products/{productId}")
    suspend fun updateProduct(
        @Path("productId") productId: String,
        @Body request: UpdateProductRequest
    ): Response<ProductResponse>

    @DELETE("products/{productId}")
    suspend fun deleteProduct(
        @Path("productId") productId: String
    ): Response<Unit>

    // --- EAN ---

    @POST("products/{productId}/eans")
    suspend fun addEan(
        @Path("productId") productId: String,
        @Body request: AddEanRequest
    ): Response<ProductResponse>

    @DELETE("products/{productId}/eans/{ean}")
    suspend fun removeEan(
        @Path("productId") productId: String,
        @Path("ean") ean: String
    ): Response<Unit>

    @GET("eans/{ean}")
    suspend fun lookupEan(
        @Path("ean") ean: String
    ): Response<EanLookupResponse>

    // --- Categories ---

    @GET("categories")
    suspend fun getCategories(): Response<List<CategoryResponse>>

    @POST("categories")
    suspend fun createCategory(
        @Body request: CreateCategoryRequest
    ): Response<CategoryResponse>

    @PUT("categories/{categoryId}")
    suspend fun updateCategory(
        @Path("categoryId") categoryId: String,
        @Body request: UpdateCategoryRequest
    ): Response<CategoryResponse>

    @DELETE("categories/{categoryId}")
    suspend fun deleteCategory(
        @Path("categoryId") categoryId: String,
        @Query("reassignTo") reassignTo: String
    ): Response<Unit>

    @PUT("categories/reorder")
    suspend fun reorderCategories(
        @Body request: ReorderCategoriesRequest
    ): Response<Unit>

    // --- Stock Adjustment ---

    @POST("adjust")
    suspend fun adjust(
        @Body request: AdjustRequest
    ): Response<AdjustResponse>

    // --- History ---

    @GET("history")
    suspend fun getHistory(
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): Response<HistoryResponse>

    // --- Sync ---

    @GET("sync")
    suspend fun getFullSync(): Response<SyncResponse>

    @POST("sync/batch")
    suspend fun syncBatch(
        @Body request: SyncBatchRequest
    ): Response<SyncBatchResponse>
}
