# Coding Standards

## Kotlin / Android
- Functional, composable UI; prefer stateless composables with state hoisted upward.
- Use `ViewModel` + unidirectional data flow (state down, events up).
- Coroutines + `Flow` for async; never block the main thread.
- Repository layer mediates between Room (local) and the REST API (remote); UI talks to
  the repository, never directly to the network.
- String resources for **all** user-facing text — no hardcoded strings in composables.
  Maintain `values/` (en), `values-pt/`, `values-es/`, `values-fr/`.
- Material 3 theming via design tokens; support dark and light.

## Python (Lambda)
- Python 3.12+, type hints on all function signatures.
- One handler per logical route; keep handlers thin, push logic into testable helpers.
- Use `boto3` resource/client clients initialized **outside** the handler (warm reuse).
- All DynamoDB quantity mutations use **atomic `ADD` (UpdateExpression)** — never
  read-modify-write, never absolute `SET` on `currentQty`.
- Guard against negative stock with a `ConditionExpression`.
- Return structured JSON; never leak stack traces to the client.

## Data model rules (critical — do not violate)
- Partition by **household** (`LIST#<listId>`), not by individual user.
- Derive the shopping list (`idealQty − currentQty`); never store it as editable state.
- Every stock change is an **atomic delta event**, also written to history.
- `currentQty` may exceed `idealQty`; `idealQty` is preserved independently.

## General
- No `any`-equivalent shortcuts; be explicit with types in both Kotlin and Python.
- Small, single-purpose functions. Comment the *why*, not the *what*.
- Validate the caller's household membership on every list-scoped endpoint.

## Git & GitHub
- Code is hosted on GitHub; all changes pushed to the remote.
- Atomic commits, one logical change each; imperative-mood messages
  (e.g., "Add scan endpoint with atomic decrement").
- **Push directly to `main`** — solo developer, no branches or PRs until the product is
  feature-complete. Keep it simple.
- **Commit and push after every completed task** — never accumulate uncommitted work.
  Each task = one commit + push.
- CI workflows will be configured later once the product stabilizes.

## User Testing
- As soon as something is testable (even basic smoke tests), **stop and ask the user to
  verify** before moving on. Don't wait for a checkpoint task.
- **At every step that touches UI, ask the user to test it on-device** — describe exactly
  what screen to open, what action to perform, and what result to expect. Do not proceed
  to the next task until the user confirms the UI works as intended.
- For backend: provide the endpoint, sample curl command, or test instructions.
- For Android: describe the screen/flow to exercise, including how to trigger the new UI
  (e.g., "scan an unknown barcode on the Home page to see the quick-create sheet").
- Prefer early, small verification loops over late, large ones.
