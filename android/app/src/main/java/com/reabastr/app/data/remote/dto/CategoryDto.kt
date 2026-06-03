package com.reabastr.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Categories ---

data class CategoryResponse(
    @SerializedName("categoryId") val categoryId: String,
    @SerializedName("name") val name: String,
    @SerializedName("sortOrder") val sortOrder: Int
)

data class CreateCategoryRequest(
    @SerializedName("name") val name: String
)

data class UpdateCategoryRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("sortOrder") val sortOrder: Int? = null
)

data class ReorderCategoriesRequest(
    @SerializedName("order") val order: List<CategoryOrderItem>
)

data class CategoryOrderItem(
    @SerializedName("categoryId") val categoryId: String,
    @SerializedName("sortOrder") val sortOrder: Int
)
