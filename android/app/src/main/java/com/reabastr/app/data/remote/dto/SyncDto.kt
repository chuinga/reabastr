package com.reabastr.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Sync ---

data class SyncResponse(
    @SerializedName("products") val products: List<ProductResponse>,
    @SerializedName("categories") val categories: List<CategoryResponse>
)

data class SyncBatchRequest(
    @SerializedName("events") val events: List<SyncBatchEvent>
)

data class SyncBatchEvent(
    @SerializedName("productId") val productId: String,
    @SerializedName("delta") val delta: Int,
    @SerializedName("timestamp") val timestamp: String
)

data class SyncBatchResponse(
    @SerializedName("results") val results: List<SyncBatchResult>
)

data class SyncBatchResult(
    @SerializedName("productId") val productId: String,
    @SerializedName("success") val success: Boolean,
    @SerializedName("currentQty") val currentQty: Int? = null,
    @SerializedName("error") val error: String? = null
)
