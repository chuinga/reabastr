# Implementation Plan: Reabastr Core

## Overview

This plan follows the prescribed build order: Bootstrap Terraform → Core Infra Terraform → Lambda handlers → API verification → Android app. Each task builds incrementally on the previous step, ensuring a working and verified backend before building the client.

## Tasks

- [x] 1. Bootstrap Terraform (one-time manual apply)
  - [x] 1.1 Create S3 state bucket and GitHub OIDC provider
    - Create `terraform/bootstrap/main.tf` with S3 bucket for state (native lockfile locking)
    - Create `terraform/bootstrap/oidc.tf` with GitHub OIDC provider + deploy role (`reabastr-github-deploy`)
    - Create `terraform/bootstrap/variables.tf` and `terraform/bootstrap/outputs.tf`
    - _Requirements: 9.7 (infrastructure for auth), tech stack (Terraform, GitHub OIDC)_

- [x] 2. Core Infrastructure Terraform
  - [x] 2.1 Create DynamoDB single-table with GSI1 and TTL
    - Create `terraform/main/dynamodb.tf` with table `reabastr-main`, PK/SK, GSI1 (GSI1PK/GSI1SK), TTL on `ttl` attribute, PAY_PER_REQUEST billing, point-in-time recovery
    - _Requirements: 12.4, 12.1, 10.2_

  - [x] 2.2 Create Cognito User Pool with Google federation
    - Create `terraform/main/cognito.tf` with User Pool (email sign-in, password policy, Cognito-native email verification), App Client (public, PKCE, no secret), Google identity provider (OIDC)
    - Token validity: access 1h, ID 1h, refresh 30d
    - Callback URLs: `reabastr://callback`, `reabastr://signout`
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x] 2.3 Create API Gateway REST API with Cognito authorizer
    - Create `terraform/main/api_gateway.tf` with REST API, Cognito authorizer (`method.request.header.Authorization`), resource paths for all endpoints, CORS configuration
    - _Requirements: 9.7, 9.8_

  - [x] 2.4 Create Lambda function definitions and IAM roles
    - Create `terraform/main/lambda.tf` with function definitions for: products, adjust, categories, households, share_code, history, sync
    - Create `terraform/main/lambda_iam.tf` with execution roles granting DynamoDB access to the main table + GSI1
    - Create `terraform/main/backend.tf`, `provider.tf`, `variables.tf`, `outputs.tf`
    - _Requirements: 9.7, 9.9_

- [x] 3. Checkpoint — Terraform plan clean
  - Ensure `terraform plan` runs cleanly with no errors, ask the user if questions arise.

- [x] 4. Lambda Handlers — Products & Categories
  - [x] 4.1 Create shared Lambda utilities module
    - Create `lambda/shared/` with: `db.py` (DynamoDB table client initialized outside handler), `auth.py` (household resolution via GSI1 `USR#<sub>` lookup), `errors.py` (structured error responses), `validators.py` (input validation helpers)
    - _Requirements: 9.9, 12.4_

  - [x] 4.2 Implement Products Lambda handler
    - Create `lambda/products/handler.py` with GET (list all products in household), POST (create product, validate name uniqueness case-insensitive, validate category exists, initialize currentQty=0), PUT (update name/idealQty/category, preserve currentQty), DELETE (remove product + all EAN mappings)
    - Enforce household membership on every operation
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 9.9_

  - [x] 4.3 Implement Categories Lambda handler
    - Create `lambda/categories/handler.py` with GET (list by sortOrder), POST (create with next sortOrder, enforce unique name), PUT (update name/sortOrder), DELETE (require product reassignment param, block if last category), PUT `/reorder` (batch update sortOrder)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [x] 4.4 Implement EAN management endpoints
    - Add to `lambda/products/handler.py` or create `lambda/eans/handler.py`: POST `/products/{productId}/eans` (add EAN, validate 8/13 digits, enforce uniqueness within household via GSI1 lookup, max 20 per product), DELETE `/products/{productId}/eans/{ean}`, GET `/eans/{ean}` (lookup product by EAN, household-scoped)
    - _Requirements: 4.5, 4.6, 4.7_

  - [x]* 4.5 Write property tests for Products and Categories (Python/hypothesis)
    - **Property 7: EAN Uniqueness Within Household** — Generate random EAN assignments; duplicate EAN within household always returns 409
    - **Validates: Requirements 4.7**

  - [x]* 4.6 Write unit tests for Products and Categories handlers
    - Test input validation, error responses, DynamoDB expressions using moto
    - Test duplicate name rejection, invalid category reference, EAN limit (20)
    - _Requirements: 5.1–5.7, 6.1–6.6, 4.5–4.7_

- [x] 5. Lambda Handlers — Stock Adjustments & History
  - [x] 5.1 Implement Adjust Lambda handler
    - Create `lambda/adjust/handler.py` with POST `/adjust`: validate household membership, apply atomic ADD delta to currentQty via UpdateExpression, ConditionExpression for negative-stock guard (`currentQty + :delta >= 0` for decrements), write History record on success with 90-day TTL, return updated currentQty
    - _Requirements: 1.4, 1.5, 2.6, 2.7, 12.1, 12.2, 12.3, 12.5, 12.6, 12.7_

  - [x] 5.2 Implement History Lambda handler
    - Create `lambda/history/handler.py` with GET `/history`: paginated (limit + cursor), reverse chronological using `ScanIndexForward=False`, household-scoped
    - _Requirements: 10.1, 10.2, 10.3_

  - [x]* 5.3 Write property tests for Adjust handler (Python/hypothesis)
    - **Property 2: Atomic Delta Commutativity** — Random permutations of delta sequences; final qty == initial + sum regardless of order
    - **Validates: Requirements 12.1, 12.3**

  - [x]* 5.4 Write property test for non-negative stock guard (Python/hypothesis)
    - **Property 3: Non-Negative Stock Invariant** — Random negative deltas against low currentQty; currentQty never goes below 0, rejection returns 409
    - **Validates: Requirements 1.5, 12.2**

  - [x]* 5.5 Write property test for history completeness (Python/hypothesis)
    - **Property 10: History Completeness** — Random delta batches; successful deltas produce exactly 1 history record each, rejected produce 0
    - **Validates: Requirements 12.6, 12.7, 10.1**

  - [x]* 5.6 Write property test for idealQty independence (Python/hypothesis)
    - **Property 9: IdealQty Independence** — Random interleaved updates to ideal and current; neither field affects the other
    - **Validates: Requirements 12.5, 5.3**

- [x] 6. Lambda Handlers — Households & Sharing
  - [x] 6.1 Implement Households Lambda handler
    - Create `lambda/households/handler.py` with GET `/household` (return household info + members, 404 if no membership), POST `/household` (create household + membership), POST `/household/leave` (remove membership)
    - _Requirements: 7.4, 7.5_

  - [x] 6.2 Implement Share Code Lambda handler
    - Create `lambda/share_code/handler.py` with POST `/household/share-code` (generate code, 24h TTL, replace existing active code for household), POST `/household/join` (redeem code: validate not expired/redeemed, add membership, invalidate code, reject if user already in a household)
    - _Requirements: 7.1, 7.2, 7.3, 7.6, 7.7_

  - [x]* 6.3 Write property test for share code lifecycle (Python/hypothesis)
    - **Property 8: Share Code Single-Use** — Random redeem/expire sequences; code is usable exactly once
    - **Validates: Requirements 7.2, 7.3**

  - [x]* 6.4 Write property test for household isolation (Python/hypothesis)
    - **Property 6: Household Isolation** — Random user/household pairs; cross-household access always returns 403
    - **Validates: Requirements 9.9, 2.6, 2.7**

- [ ] 7. Lambda Handlers — Sync
  - [ ] 7.1 Implement Sync Lambda handler
    - Create `lambda/sync/handler.py` with GET `/sync` (return full household state: products, categories, EAN mappings), POST `/sync/batch` (process multiple delta events in sequence, each with atomic ADD + history write)
    - _Requirements: 8.3, 8.6, 12.1_

- [ ] 8. Checkpoint — Backend tests pass, API ready for verification
  - Ensure all pytest tests pass (unit + property-based with moto), ask the user if questions arise.

- [ ] 9. CI/CD Pipelines
  - [ ] 9.1 Create GitHub Actions workflows
    - Create `.github/workflows/terraform.yml` (path-filtered on `terraform/main/**`, OIDC auth, plan on PR, apply on merge to main)
    - Create `.github/workflows/lambda.yml` (path-filtered on `lambda/**`, pytest on PR, zip + deploy on merge)
    - Create `.github/workflows/android.yml` (path-filtered on `android/**`, unit tests on PR, assemble release on merge)
    - _Requirements: tech stack (GitHub Actions, OIDC, path-filtered)_

- [ ] 10. Android — Project Setup & Auth
  - [ ] 10.1 Create Android project structure with dependencies
    - Initialize `android/` with Kotlin, Jetpack Compose, Material 3, Room, WorkManager, ML Kit barcode, Retrofit, Hilt (DI), EncryptedSharedPreferences
    - Set up module structure, build.gradle with all dependencies
    - _Requirements: tech stack (Kotlin, Compose, Material 3, Room, WorkManager, ML Kit)_

  - [ ] 10.2 Implement AuthRepository and sign-in flow
    - Create `AuthRepository` with Cognito sign-in (Google OAuth PKCE via Chrome Custom Tab + email/password via SRP), token storage in EncryptedSharedPreferences, proactive token refresh (5 min before expiry), refresh token handling, sign-out
    - Create Sign-In screen composable with Google and email/password options
    - _Requirements: 9.1, 9.2, 9.4, 9.5, 9.6_

  - [ ] 10.3 Implement onboarding flow (create/join household)
    - Create Onboard screen: check `GET /household` → if 404, show "Create household" or "Join with code" options
    - Wire to POST `/household` and POST `/household/join`
    - _Requirements: 7.1, 7.2_

- [ ] 11. Android — Room Database & Data Layer
  - [ ] 11.1 Create Room database entities and DAOs
    - Define `ProductEntity`, `CategoryEntity`, `OutboxEvent` entities (as specified in design)
    - Create DAOs with queries: products by household, categories by sortOrder, outbox pending/failed, insert/update/delete operations
    - Create TypeConverters for List<String> (EAN list)
    - _Requirements: 8.1, 8.2, 8.4_

  - [ ] 11.2 Implement InventoryRepository
    - Create `InventoryRepository`: local-first delta application (Room update within 200ms), product/category CRUD bridging Room and API, EAN lookup from local cache
    - _Requirements: 1.1, 1.2, 2.1, 2.3, 3.1, 3.4, 8.1_

  - [ ] 11.3 Implement SyncRepository and OutboxWorker
    - Create `SyncRepository`: enqueue delta events to outbox (within 1s), manage outbox drain via WorkManager
    - Create `OutboxWorker`: on connectivity, drain outbox chronologically, 3 retries with exponential backoff (1s, 2s, 4s), mark FAILED after exhaustion, halt at 500 pending events
    - Create `ReconcileWorker`: pull full state via GET `/sync`, reconcile Room cache preserving pending outbox deltas
    - _Requirements: 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_

  - [ ] 11.4 Implement ApiService (Retrofit)
    - Create Retrofit interface with all backend endpoints, JWT Bearer interceptor, structured error parsing
    - _Requirements: 9.4, 9.7_

- [ ] 12. Android — Home Page (Take from Stock)
  - [ ] 12.1 Implement HomeViewModel and HomePage composable
    - Create `HomeViewModel` with product list state, decrement action (local Room update + outbox enqueue), scan result handling (EAN lookup → decrement or trigger quick-create)
    - Create `HomePage` composable: product list with −1 buttons, out-of-stock guard (prevent decrement at qty 0, show message 3s), scan FAB
    - _Requirements: 1.1, 1.2, 1.3, 1.6, 1.8, 1.9_

  - [ ]* 12.2 Write property test for shopping list derivation (Kotlin/kotest-property)
    - **Property 1: Shopping List Derivation** — Random (idealQty, currentQty) pairs; buyQty == max(0, idealQty - currentQty)
    - **Validates: Requirements 3.1, 3.2**

- [ ] 13. Android — Shopping List Page
  - [ ] 13.1 Implement ShoppingListViewModel and ShoppingListPage composable
    - Create `ShoppingListViewModel`: derive shopping list as `products.filter { it.idealQty > it.currentQty }`, compute buyQty, group by category sortOrder, sort alphabetically within groups
    - Create `ShoppingListPage` composable: grouped list with +1 buttons, recompute within 100ms of data change, empty-state message, uncategorized group at end
    - Handle scan on Shopping List: increment if known EAN, error if unknown (no quick-create)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

- [ ] 14. Android — Setup Page (Products & Categories)
  - [ ] 14.1 Implement SetupViewModel and SetupPage composable
    - Create `SetupViewModel`: product CRUD operations, category CRUD, drag-to-reorder categories (batch update sortOrder within 3s)
    - Create `SetupPage` composable: product list with edit/delete, category list with drag-reorder, create product form (name 1–100 chars, idealQty 1–9999, category picker), create category form (name 1–50 chars), delete category with reassignment dialog, prevent deletion of last category
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

- [ ] 15. Android — Scanner Integration
  - [ ] 15.1 Implement ScannerService and scanner overlay
    - Create `ScannerService`: ML Kit barcode scanner for EAN-13/EAN-8, on-device only, camera lifecycle management, 10s timeout with retry/cancel
    - Create scanner overlay composable (shared between Home and Shopping List)
    - _Requirements: 4.1, 4.2, 4.4_

  - [ ] 15.2 Implement quick-create product flow
    - Create quick-create bottom sheet: pre-populated EAN, require name (1–100 chars), idealQty (1–999), category selection → POST to backend → add locally
    - _Requirements: 4.3, 1.8_

- [ ] 16. Android — Settings Page
  - [ ] 16.1 Implement SettingsViewModel and SettingsPage composable
    - Create `SettingsViewModel`: account info, language override, share code generation, history loading (paginated, 50 per page)
    - Create `SettingsPage` composable: account section, language picker (en/pt/es/fr), share code generation + display, history list (product name, signed delta, user name, relative/absolute timestamp), leave household
    - _Requirements: 7.1, 7.6, 10.3, 10.4, 10.5, 10.6, 11.2_

- [ ] 17. Android — Offline Sync & Reconciliation
  - [ ] 17.1 Wire WorkManager scheduling and connectivity monitoring
    - Register `OutboxWorker` with connectivity constraint, periodic reconciliation on app foreground
    - Handle sync capacity warning (>500 pending events), failed event notification
    - Ensure outbox persists across app restarts/reboots
    - _Requirements: 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_

  - [ ]* 17.2 Write property test for outbox eventual delivery (Kotlin/kotest-property)
    - **Property 4: Outbox Eventual Delivery** — Random event sequences + simulated failures; all events reach terminal state (uploaded or FAILED)
    - **Validates: Requirements 8.2, 8.3, 8.4, 8.5**

  - [ ]* 17.3 Write property test for reconciliation consistency (Kotlin/kotest-property)
    - **Property 5: Reconciliation Consistency** — Random server state + pending outbox; post-reconcile local == server + pending deltas
    - **Validates: Requirements 8.6**

- [ ] 18. Android — Internationalization
  - [ ] 18.1 Set up string resources and locale override mechanism
    - Create `values/strings.xml` (en), `values-pt/strings.xml`, `values-es/strings.xml`, `values-fr/strings.xml` with all UI strings
    - Implement `LocaleManager` with DataStore, apply via `AppCompatDelegate.setApplicationLocales()` (Android 13+) and `createConfigurationContext()` (pre-13)
    - Verify no hardcoded strings in composables, test 320dp minimum width
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_

- [ ] 19. Android — Material 3 Theming
  - [ ] 19.1 Implement ReabastrTheme with dark/light support
    - Create `ReabastrTheme` composable: dynamic color on Android 12+, fallback brand palette (warm green primary + neutral surfaces), dark and light color schemes, typography
    - _Requirements: tech stack (Material 3, dark + light themes)_

- [ ] 20. Final Checkpoint — All tests pass, full integration verified
  - Ensure all tests pass (Python pytest + Kotlin unit tests), ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties defined in the design
- Unit tests validate specific examples and edge cases
- The build order follows the prescribed workflow: Terraform → Lambda → API → Android
- Backend is Python 3.12+ with type hints; Android is Kotlin with Jetpack Compose
- All DynamoDB mutations use atomic ADD — never read-modify-write

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["2.3", "2.4"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 5, "tasks": ["4.5", "4.6", "5.1", "5.2"] },
    { "id": 6, "tasks": ["5.3", "5.4", "5.5", "5.6", "6.1", "6.2"] },
    { "id": 7, "tasks": ["6.3", "6.4", "7.1"] },
    { "id": 8, "tasks": ["9.1"] },
    { "id": 9, "tasks": ["10.1"] },
    { "id": 10, "tasks": ["10.2", "11.1", "19.1"] },
    { "id": 11, "tasks": ["10.3", "11.2", "11.4"] },
    { "id": 12, "tasks": ["11.3", "12.1", "15.1"] },
    { "id": 13, "tasks": ["12.2", "13.1", "15.2"] },
    { "id": 14, "tasks": ["14.1", "16.1"] },
    { "id": 15, "tasks": ["17.1", "18.1"] },
    { "id": 16, "tasks": ["17.2", "17.3"] }
  ]
}
```
