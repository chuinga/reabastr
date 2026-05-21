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
- Feature branches; never push directly to `main`. Open PRs for review.
- PRs trigger `terraform plan` (posted as a comment); merges to `main` apply.
