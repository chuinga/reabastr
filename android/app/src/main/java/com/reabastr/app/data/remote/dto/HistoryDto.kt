package com.reabastr.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- History ---

data class HistoryResponse(
    @SerializedName("items") val items: List<HistoryItemDto>,
    @SerializedName("cursor") val cursor: String? = null
)

data class HistoryItemDto(
    @SerializedName("historyId") val historyId: String,
    @SerializedName("productName") val productName: String,
    @SerializedName("delta") val delta: Int,
    @SerializedName("userName") val userName: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("timestamp") val timestamp: String
)
