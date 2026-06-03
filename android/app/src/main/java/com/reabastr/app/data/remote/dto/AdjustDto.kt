package com.reabastr.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Stock Adjustment ---

data class AdjustRequest(
    @SerializedName("productId") val productId: String,
    @SerializedName("delta") val delta: Int
)

data class AdjustResponse(
    @SerializedName("productId") val productId: String,
    @SerializedName("currentQty") val currentQty: Int,
    @SerializedName("delta") val delta: Int,
    @SerializedName("historyId") val historyId: String? = null
)
