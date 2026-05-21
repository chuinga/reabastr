# Product & Structure

## Product name: Reabastr

## What this app is
An **inventory-driven** household shopping app. Users track stock levels of household
products; the shopping list is **derived** (`idealQty − currentQty`), never edited
directly. The core interaction is scanning a barcode (or tapping +/−) to take an item
from stock (→ adds to shopping list) or restock it (→ removes from list). Multiple users
share a household via a share code. Offline-first: the UI always feels connected.

## The four pages (each is one verb)
1. **Home / Take from stock** — scan or −1; flows items onto the shopping list.
   Unknown barcode → inline quick-create.
2. **Shopping list** — derived buy list, grouped by category `sortOrder` (store-aisle order);
   scan or +1 at the store to restock.
3. **Setup** — CRUD products & categories; drag-to-reorder categories.
4. **Settings / Profile** — account, login, language override, household share code, history.

## Repository layout (monorepo)
```
/android        # Kotlin/Compose app
/lambda         # Python handlers
/terraform
  /bootstrap    # one-time: state bucket + OIDC provider + deploy role (manual apply)
  /main         # Cognito, DynamoDB, API Gateway, Lambda, IAM
/.github/workflows   # path-filtered Actions pipelines
/.kiro/steering      # these files
/spec           # the specification document
```

## Non-negotiable product rules
- Quantities are whole units.
- Buy quantity = exact gap, `idealQty − currentQty` (zero if non-positive).
- `currentQty` may exceed `idealQty`; ideal is preserved.
- A product may map to **multiple EANs**.
- Categories carry a `sortOrder` driving shopping-list grouping.
- One permission level per household; no member cap.
- History: activity log, attributed to user, 90-day TTL.

## Out of scope for v1
- Default seed data (hook exists, seeds nothing yet).
- Translating user-entered data (interface is localized; data is stored as typed).
- Per-member permissions, aggregate analytics, external product-DB lookup, email/SES invites.
