package com.reabastr.app.data.repository

import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.HouseholdResponse
import com.reabastr.app.data.remote.dto.JoinHouseholdRequest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HouseholdResult {
    data class Success(val household: HouseholdResponse) : HouseholdResult
    data object NoHousehold : HouseholdResult
    data class Error(val message: String) : HouseholdResult
}

/**
 * Mediates household operations between the UI and the backend API.
 */
@Singleton
class HouseholdRepository @Inject constructor(
    private val apiService: ApiService
) {

    /**
     * Checks if the current user belongs to a household.
     * Returns [HouseholdResult.NoHousehold] if the user has no household (404).
     */
    suspend fun checkHousehold(): HouseholdResult {
        return try {
            val response = apiService.getHousehold()
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body != null) {
                        HouseholdResult.Success(body)
                    } else {
                        HouseholdResult.NoHousehold
                    }
                }
                response.code() == 404 -> HouseholdResult.NoHousehold
                response.code() == 403 -> HouseholdResult.NoHousehold
                else -> HouseholdResult.Error(
                    "Failed to check household (${response.code()})"
                )
            }
        } catch (e: Exception) {
            HouseholdResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Creates a new household for the current user.
     */
    suspend fun createHousehold(): HouseholdResult {
        return try {
            val response = apiService.createHousehold(
                com.reabastr.app.data.remote.dto.CreateHouseholdRequest(name = "My Household")
            )
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body != null) {
                        HouseholdResult.Success(body)
                    } else {
                        HouseholdResult.Error("Empty response from server")
                    }
                }
                else -> {
                    HouseholdResult.Error(
                        "Failed to create household (${response.code()})"
                    )
                }
            }
        } catch (e: Exception) {
            HouseholdResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Joins an existing household using a share code.
     */
    suspend fun joinHousehold(code: String): HouseholdResult {
        return try {
            val response = apiService.joinHousehold(JoinHouseholdRequest(code = code))
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body != null) {
                        HouseholdResult.Success(body)
                    } else {
                        HouseholdResult.Error("Empty response from server")
                    }
                }
                response.code() == 409 -> {
                    HouseholdResult.Error("Invalid or expired share code")
                }
                response.code() == 404 -> {
                    HouseholdResult.Error("Share code not found")
                }
                else -> {
                    HouseholdResult.Error(
                        "Failed to join household (${response.code()})"
                    )
                }
            }
        } catch (e: Exception) {
            HouseholdResult.Error(e.message ?: "Network error")
        }
    }
}
