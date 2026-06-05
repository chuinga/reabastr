# Continue From Here

## Current State

The Android app fails to build with a KSP/JDK compatibility issue.

**Versions:**
- Kotlin: 2.2.10
- KSP: 2.2.10-2.0.2
- Gradle: 9.5.0
- AGP: 9.2.1
- JDK on machine: 22

**Error:** `[ksp] java.lang.IllegalStateException: unexpected jvm signature V`

## What Needs To Happen

1. In Android Studio: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM criteria → Version: 21**
2. Sync and build
3. If still failing, install JDK 21 on the machine (JDK 22 may not be compatible with this KSP version)
4. Once building, run on the emulator — should see Sign-In screen

## Infrastructure Status

- ✅ Terraform deployed (Cognito, DynamoDB, API Gateway, Lambda)
- ✅ Cognito User Pool: `eu-west-1_ZRswNSk8l`
- ✅ App Client ID: `4fm03v1ivocpi0qdc42sblfgg5`
- ✅ API Gateway: `https://ii7iuqvzek.execute-api.eu-west-1.amazonaws.com/v1/`
- ✅ AuthConfig.kt and ApiConfig.kt updated with real values
- ✅ GitHub secrets configured: AWS_ACCOUNT_ID, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET

## GitHub Secrets (Repository secrets)

- `AWS_ACCOUNT_ID`: 058264503354
- `GOOGLE_CLIENT_ID`: (set by user)
- `GOOGLE_CLIENT_SECRET`: (set by user)

## AWS Profile

- Profile name: `miguel`
- Region: eu-west-1
- Account: 058264503354

## What Was Completed This Session

1. Task 17.3 — Reconciliation consistency property test (ReconcileConsistencyPropertyTest.kt)
2. Fixed Terraform workflow (version constraint, Google OAuth secrets passthrough)
3. Fixed IAM deploy role (added Cognito domain permissions)
4. Infrastructure deployed via GitHub Actions pipeline
5. Updated Android AuthConfig.kt and ApiConfig.kt with real deployed values
6. Fixed KSP version from 2.3.2 → 2.2.10-2.0.2 (matching Kotlin 2.2.10)
