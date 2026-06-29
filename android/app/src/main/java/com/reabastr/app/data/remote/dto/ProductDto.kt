package com.reabastr.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Products ---

data class ProductResponse(
    @SerializedName("productId") val productId: String,
    @SerializedName("name") val name: String,
    @SerializedName("categoryId") val categoryId: String?,
    @SerializedName("idealQty") val idealQty: Int,
    @SerializedName("currentQty") val currentQty: Int,
    @SerializedName("eans") val eans: List<String>,
    @SerializedName("refs") val refs: List<String> = emptyList(),
    @SerializedName("createdAt") val createdAt: String? = null
)

data class CreateProductRequest(
    @SerializedName("name") val name: String,
    @SerializedName("categoryId") val categoryId: String,
    @SerializedName("idealQty") val idealQty: Int,
    @SerializedName("eans") val eans: List<String> = emptyList(),
    @SerializedName("refs") val refs: List<String> = emptyList()
)

data class UpdateProductRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("categoryId") val categoryId: String? = null,
    @SerializedName("idealQty") val idealQty: Int? = null,
    @SerializedName("refs") val refs: List<String>? = null
)

// --- EAN ---

data class AddEanRequest(
    @SerializedName("ean") val ean: String
)

data class EanLookupResponse(
    @SerializedName("productId") val productId: String,
    @SerializedName("name") val name: String,
    @SerializedName("householdId") val householdId: String? = null
)
