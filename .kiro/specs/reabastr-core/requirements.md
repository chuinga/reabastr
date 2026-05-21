# Requirements Document

## Introduction

Reabastr is an inventory-driven household shopping app. Users track stock levels of household products and the shopping list is derived automatically from the gap between ideal and current quantities. The core interaction loop is scanning a barcode (or tapping +/−) to consume stock (which grows the shopping list) or restock (which shrinks it). Multiple users share a single household via a share code. The app is offline-first: local state updates immediately and syncs to the backend when connectivity is available.

## Glossary

- **App**: The Reabastr Android client application
- **Backend**: The serverless AWS infrastructure (API Gateway, Lambda, DynamoDB)
- **Household**: A shared inventory context; all members see the same products and quantities
- **Product**: A named item tracked in a household's inventory, belonging to one Category
- **Category**: A grouping label for products with a sortOrder that determines shopping-list display order
- **EAN**: European Article Number barcode (EAN-13 or EAN-8) mapped to a Product
- **currentQty**: The present stock level of a Product (whole units, may exceed idealQty)
- **idealQty**: The desired stock level of a Product (whole units, independently preserved)
- **buyQty**: The derived shopping quantity: max(0, idealQty − currentQty)
- **Shopping_List**: The derived set of Products where buyQty > 0, grouped by Category sortOrder
- **Share_Code**: A code that grants membership to a Household
- **Delta_Event**: An atomic +N or −N stock change recorded against a Product
- **History**: An activity log of Delta_Events attributed to a user, with a 90-day TTL
- **Sync_Outbox**: A local queue of pending Delta_Events awaiting upload to the Backend
- **Scanner**: The on-device ML Kit barcode reader
- **Room_Cache**: The local Room database serving as the offline source of truth for the UI

---

## Requirements

### Requirement 1: Take from Stock

**User Story:** As a household member, I want to scan a barcode or tap −1 on the Home page to record consumption, so that the item appears on the shopping list when stock drops below ideal.

#### Acceptance Criteria

1. WHEN a user scans a recognized EAN on the Home page, THE App SHALL decrement the associated Product's currentQty by 1 in the Room_Cache within 200 milliseconds of scan recognition.
2. WHEN a user taps the minus button on a Product on the Home page, THE App SHALL decrement that Product's currentQty by 1 in the Room_Cache within 200 milliseconds of the tap.
3. WHEN a decrement Delta_Event is created, THE App SHALL enqueue the Delta_Event in the Sync_Outbox for upload to the Backend within 1 second of the local decrement.
4. WHEN the Backend receives a decrement Delta_Event, THE Backend SHALL apply an atomic ADD −1 to the Product's currentQty using a DynamoDB UpdateExpression with a ConditionExpression ensuring currentQty remains >= 0.
5. IF a decrement would reduce currentQty below 0, THEN THE Backend SHALL reject the operation with a conditional-check-failed error and leave the Product's currentQty unchanged.
6. IF the Product's currentQty is 0 at the time of a decrement attempt, THEN THE App SHALL prevent the local decrement, leave currentQty unchanged, and display an out-of-stock message for at least 3 seconds.
7. WHEN a Delta_Event is persisted by the Backend, THE Backend SHALL write a History record containing the product identifier, the delta value, and the acting user's identifier, with a 90-day TTL.
8. WHEN a user scans an EAN that is not associated with any Product in the household, THE App SHALL initiate the inline quick-create flow for a new Product mapped to that EAN.
9. WHEN a local decrement causes a Product's currentQty to drop below its idealQty, THE App SHALL display that Product on the Shopping List page with a buy quantity equal to idealQty minus currentQty.

### Requirement 2: Restock from Shopping List

**User Story:** As a household member, I want to scan a barcode or tap +1 on the Shopping List page to record restocking, so that purchased items disappear from the shopping list.

#### Acceptance Criteria

1. WHEN a user scans a recognized EAN on the Shopping List page, THE App SHALL increment the associated Product's currentQty by 1 in the Room_Cache within 200 milliseconds of scan completion.
2. WHEN a user scans an EAN that is not associated with any Product in the household list, THE App SHALL display an error indication stating the barcode is unrecognized and SHALL NOT create a Delta_Event.
3. WHEN a user taps the plus button on a Product on the Shopping List page, THE App SHALL increment that Product's currentQty by 1 in the Room_Cache within 200 milliseconds of the tap.
4. WHEN an increment causes a Product's currentQty to equal or exceed its idealQty, THE App SHALL remove that Product from the Shopping List view within the same UI update cycle.
5. WHEN an increment Delta_Event is created, THE App SHALL enqueue the Delta_Event in the Sync_Outbox for upload to the Backend within 1 second of the local Room_Cache update.
6. WHEN the Backend receives an increment Delta_Event, THE Backend SHALL apply an atomic ADD +1 to the Product's currentQty using a DynamoDB UpdateExpression and SHALL validate that the requesting user is a member of the Product's household before applying the update.
7. IF the Backend fails to validate household membership for an increment Delta_Event, THEN THE Backend SHALL reject the request with an authorization error indication and SHALL NOT modify the Product's currentQty.
8. THE Backend SHALL allow currentQty to exceed idealQty without error.
9. WHEN a Delta_Event is persisted by the Backend, THE Backend SHALL write a History record attributed to the acting user with a 90-day TTL.

### Requirement 3: Derived Shopping List

**User Story:** As a household member, I want the shopping list to show exactly what I need to buy, so that I never manually maintain a separate list.

#### Acceptance Criteria

1. THE App SHALL compute buyQty for each Product as max(0, idealQty − currentQty), where idealQty and currentQty are whole non-negative integers.
2. THE App SHALL display on the Shopping_List only Products where buyQty is greater than 0, showing the Product name, buyQty, and Category name for each item.
3. THE App SHALL group Shopping_List items by their Category and sort groups by Category sortOrder ascending, and sort Products alphabetically within each group.
4. WHEN currentQty or idealQty changes for any Product, THE App SHALL recompute and re-render the Shopping_List within 100 milliseconds.
5. THE Backend SHALL NOT store the Shopping_List as a persisted or editable entity; the client SHALL derive it from current Product data on every render.
6. IF a Product has no assigned Category, THEN THE App SHALL display that Product at the end of the Shopping_List in an "Uncategorized" group.
7. IF the derived Shopping_List contains zero items, THEN THE App SHALL display an empty-state message indicating no items need to be purchased.

### Requirement 4: Barcode Scanning and EAN Management

**User Story:** As a household member, I want to scan product barcodes for quick stock adjustments, so that tracking inventory is frictionless.

#### Acceptance Criteria

1. THE Scanner SHALL decode EAN-13 and EAN-8 barcodes on-device using ML Kit without sending image data to the Backend.
2. WHEN the Scanner decodes an EAN that maps to an existing Product, THE App SHALL apply the contextual action (decrement currentQty by 1 on Home, increment currentQty by 1 on Shopping List) using an atomic delta operation.
3. WHEN the Scanner decodes an EAN that does not map to any Product, THE App SHALL present an inline quick-create form pre-populated with the scanned EAN, requiring at minimum a product name (1–100 characters), an idealQty (whole number, 1–999), and a category selection before submission.
4. IF the Scanner fails to decode a barcode within 10 seconds of activation, THEN THE App SHALL display a message indicating the scan was unsuccessful and allow the user to retry or cancel.
5. THE Backend SHALL support mapping multiple EANs (up to 20 per Product) to a single Product, storing each EAN as a 8-digit or 13-digit string.
6. WHEN a new EAN is added to an existing Product, THE Backend SHALL persist the mapping without altering the Product's currentQty or idealQty.
7. IF a user attempts to add an EAN that is already mapped to another Product within the same household, THEN THE Backend SHALL reject the request with an error indicating the EAN is already in use and identify the existing Product.

### Requirement 5: Product Management

**User Story:** As a household member, I want to create, read, update, and delete products on the Setup page, so that my household's inventory reflects what we actually use.

#### Acceptance Criteria

1. WHEN a user creates a Product, THE App SHALL require a name (1 to 100 characters), a Category selected from the Household's existing categories, and an idealQty (whole number, minimum 1, maximum 9999).
2. WHEN a user creates a Product, THE Backend SHALL initialize currentQty to 0 and return the created Product with its generated identifier.
3. WHEN a user updates a Product's idealQty, THE Backend SHALL preserve the existing currentQty unchanged.
4. WHEN a user deletes a Product, THE Backend SHALL remove the Product and all associated EAN mappings from the Household.
5. IF a user creates or renames a Product with a name that already exists within the same Household (case-insensitive comparison), THEN THE Backend SHALL reject the request with an error indicating a duplicate product name.
6. IF a user attempts to create a Product referencing a Category that does not exist in the Household, THEN THE Backend SHALL reject the request with an error indicating an invalid category.
7. WHEN a user reads the Product list, THE Backend SHALL return all Products belonging to the user's Household, each including name, category, idealQty, currentQty, and associated EAN codes.

### Requirement 6: Category Management

**User Story:** As a household member, I want to manage categories and reorder them on the Setup page, so that the shopping list matches my store's aisle layout.

#### Acceptance Criteria

1. WHEN a user creates a Category, THE App SHALL require a name between 1 and 50 characters and assign the next available sortOrder value (one greater than the current maximum sortOrder within the Household, or 1 if no Categories exist).
2. WHEN a user reorders categories via drag-and-drop, THE App SHALL update sortOrder values for all affected Categories to reflect the new sequence and persist the updated order to the Backend within 3 seconds.
3. WHEN a user initiates deletion of a Category that contains one or more Products, THE App SHALL prompt the user to select a destination Category and reassign all Products from the deleted Category to the selected destination Category before completing the deletion.
4. IF a user attempts to delete a Category when it is the only Category in the Household, THEN THE App SHALL prevent the deletion and display an error message indicating that at least one Category must exist.
5. THE Backend SHALL enforce that Category names are unique (case-insensitive) within a Household, rejecting creation or rename requests that would produce a duplicate with an error message indicating the name is already in use.
6. IF a user submits a Category name that is empty or exceeds 50 characters, THEN THE App SHALL reject the input and display an error message indicating the name length constraint.

### Requirement 7: Household Sharing

**User Story:** As a household member, I want to invite others via a share code, so that my household can collaboratively manage inventory.

#### Acceptance Criteria

1. WHEN a user requests a share code on the Settings page, THE Backend SHALL generate a unique Share_Code for the user's Household with a time-to-live of 24 hours from generation.
2. WHEN a new user redeems a valid, non-expired Share_Code, THE Backend SHALL add that user as a member of the associated Household and invalidate the Share_Code so it cannot be reused.
3. IF a user redeems a Share_Code that is expired or already redeemed, THEN THE Backend SHALL reject the request with an error message indicating whether the code is expired or already used, and SHALL NOT modify Household membership.
4. THE Backend SHALL enforce a single, equal permission level for all Household members.
5. THE Backend SHALL NOT impose a maximum member count per Household.
6. IF a user who already belongs to a Household attempts to redeem a Share_Code for a different Household, THEN THE Backend SHALL reject the request with an error message indicating the user must leave their current Household first.
7. WHEN a Share_Code is generated for a Household that already has an active (non-expired, non-redeemed) Share_Code, THE Backend SHALL replace the previous code with the newly generated one, invalidating the old code.

### Requirement 8: Offline-First Sync

**User Story:** As a household member, I want the app to work fully offline, so that I can adjust stock in areas with no connectivity and trust that changes sync later.

#### Acceptance Criteria

1. THE App SHALL apply all stock adjustments to the Room_Cache immediately, regardless of network availability, such that the UI reflects the updated currentQty within 200 milliseconds of user action.
2. WHILE the device is offline, THE App SHALL queue all Delta_Events in the Sync_Outbox in the order they were created, preserving the event type, product identifier, quantity delta, timestamp, and originating user identifier.
3. WHEN network connectivity is restored, THE App SHALL drain the Sync_Outbox by uploading pending Delta_Events to the Backend via WorkManager in chronological order within 30 seconds of detecting connectivity.
4. THE App SHALL preserve Sync_Outbox contents across app restarts and device reboots by persisting the outbox in the Room database.
5. IF a synced Delta_Event is rejected by the Backend, THEN THE App SHALL mark the event as failed, retain it in the Sync_Outbox, and surface a notification to the user indicating which product and action failed.
6. WHEN the App comes online, THE App SHALL pull the latest Household state from the Backend and reconcile the Room_Cache by applying the server state while preserving any unsent Delta_Events still queued in the Sync_Outbox.
7. IF the Sync_Outbox contains more than 500 pending Delta_Events, THEN THE App SHALL halt queuing new events and display a warning to the user indicating that sync capacity has been reached.
8. WHEN a Delta_Event upload fails due to a transient network error, THE App SHALL retry the upload up to 3 times with exponential backoff before marking the event as failed.

### Requirement 9: Authentication

**User Story:** As a user, I want to sign in with Google or email/password, so that my household data is secure and accessible across devices.

#### Acceptance Criteria

1. THE App SHALL support sign-in via Google federation through the Cognito User Pool.
2. THE App SHALL support sign-in via email and password through the Cognito User Pool.
3. WHEN a new user registers with email and password, THE Backend SHALL require email verification via Cognito-native email before allowing access to any Household-scoped endpoint.
4. THE App SHALL store the Cognito-issued JWT locally and refresh it before expiration; the App SHALL NOT hold AWS credentials at any time.
5. IF the locally stored JWT has expired and the refresh token is still valid, THEN THE App SHALL obtain a new JWT using the refresh token without requiring the user to re-enter credentials.
6. IF the locally stored JWT has expired and the refresh token is also expired or invalid, THEN THE App SHALL redirect the user to the sign-in screen.
7. THE Backend SHALL validate the Cognito JWT on every API request via the API Gateway Cognito authorizer.
8. IF a request arrives with a missing, malformed, or expired JWT, THEN THE Backend SHALL reject the request with an authentication error response and SHALL NOT execute the requested operation.
9. THE Backend SHALL validate the caller's Household membership on every Household-scoped endpoint by confirming the authenticated user's identity exists as a member of the target Household before processing the request.

### Requirement 10: History and Activity Log

**User Story:** As a household member, I want to view a history of stock changes, so that I can understand consumption patterns and see who adjusted what.

#### Acceptance Criteria

1. WHEN a Delta_Event is processed, THE Backend SHALL record a History entry containing: user identity (Cognito sub and display name), Product name, delta value (signed integer), and timestamp (ISO 8601 UTC).
2. THE Backend SHALL set a 90-day TTL on all History entries using DynamoDB TTL.
3. WHEN a user navigates to the History section on the Settings page, THE App SHALL display History entries in reverse chronological order, loading a maximum of 50 entries per page with pagination to load older entries.
4. THE App SHALL attribute each History entry to the display name of the user who performed the action.
5. IF a History request fails due to network error, THEN THE App SHALL display an error message indicating the history could not be loaded and allow the user to retry.
6. WHEN displaying a History entry, THE App SHALL show the product name, the signed delta value (e.g., "+1" or "−1"), the attributed user name, and a human-readable timestamp relative to the current time for entries less than 24 hours old, or a date format (dd/MM/yyyy HH:mm) for older entries.

### Requirement 11: Internationalization

**User Story:** As a user, I want the app in my preferred language, so that I can navigate without language barriers.

#### Acceptance Criteria

1. THE App SHALL provide localized UI strings for English, Portuguese, Spanish, and French using Android string resource directories `values/` (en), `values-pt/`, `values-es/`, and `values-fr/`.
2. WHEN a user selects a language override in Settings, THE App SHALL apply the chosen locale to all user-facing text within 1 second without requiring an app restart.
3. IF no language override is selected in Settings, THEN THE App SHALL default to the device's system locale when it matches a supported language (en, pt, es, fr), or fall back to English otherwise.
4. THE App SHALL NOT translate user-entered data (product names, category names).
5. THE App SHALL use Android string resources for all user-facing text with no hardcoded strings in composables.
6. THE App SHALL render all four supported locales without text truncation or layout overflow on screens with a minimum width of 320dp.

### Requirement 12: Data Integrity and Atomicity

**User Story:** As the system operator, I want all quantity mutations to be atomic and safe under concurrency, so that no stock data is lost or corrupted.

#### Acceptance Criteria

1. THE Backend SHALL use DynamoDB atomic ADD in UpdateExpressions for all currentQty mutations — never read-modify-write.
2. IF an atomic ADD on currentQty would result in a value below 0, THEN THE Backend SHALL reject the mutation with an error response indicating insufficient stock, and SHALL leave currentQty unchanged.
3. WHEN two concurrent Delta_Events target the same Product, THE Backend SHALL resolve both atomically such that the final currentQty equals the prior value plus the sum of both deltas.
4. THE Backend SHALL partition all data by Household (partition key LIST#<listId>), not by individual user.
5. THE Backend SHALL treat idealQty and currentQty as independent fields; modifying one SHALL NOT alter the other.
6. WHEN a currentQty mutation is rejected due to the negative-stock guard, THEN THE Backend SHALL return an error response within 2 seconds indicating the current quantity cannot go below zero, and SHALL NOT write a history record for the rejected operation.
7. WHEN a currentQty mutation succeeds, THE Backend SHALL write a corresponding history record within the same operation, attributed to the requesting user, with a 90-day TTL.
