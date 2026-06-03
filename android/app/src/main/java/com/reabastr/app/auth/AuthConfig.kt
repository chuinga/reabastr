package com.reabastr.app.auth

/**
 * Cognito configuration. Values are from the Terraform-deployed User Pool.
 * In production these would come from BuildConfig or a remote config;
 * for now they are compile-time constants matching the deployed infra.
 */
object AuthConfig {
    const val REGION = "eu-west-1"
    const val USER_POOL_ID = "eu-west-1_PLACEHOLDER" // Replace after terraform apply
    const val CLIENT_ID = "PLACEHOLDER" // Replace after terraform apply
    const val DOMAIN = "reabastr.auth.eu-west-1.amazoncognito.com"
    const val REDIRECT_URI = "reabastr://callback"
    const val SIGN_OUT_URI = "reabastr://signout"
    const val SCOPES = "openid email profile"

    // Token refresh buffer — refresh 5 minutes before expiry
    const val TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1000L
}
