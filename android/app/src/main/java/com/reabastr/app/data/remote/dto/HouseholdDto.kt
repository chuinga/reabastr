package com.reabastr.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Household ---

data class HouseholdResponse(
    @SerializedName("householdId") val householdId: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("members") val members: List<MemberDto>? = null
)

data class MemberDto(
    @SerializedName("userId") val userId: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("joinedAt") val joinedAt: String
)

data class CreateHouseholdRequest(
    @SerializedName("name") val name: String
)

data class ShareCodeResponse(
    @SerializedName("code") val code: String,
    @SerializedName("expiresAt") val expiresAt: String
)

data class JoinHouseholdRequest(
    @SerializedName("code") val code: String
)
