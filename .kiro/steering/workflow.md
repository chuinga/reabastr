# Development Workflow

## Build order (do not reorder)
1. **Bootstrap Terraform** (S3 state bucket, GitHub OIDC provider + deploy role) — applied
   manually, once.
2. **Core infra Terraform** (Cognito, DynamoDB, API Gateway, Lambda skeletons).
3. **Lambda handlers** (Python): products, scan/adjust, categories, households,
   share-code redemption, history.
4. **Verify the API** end-to-end with a real Cognito JWT (curl / REST client) **before**
   building any Android UI.
5. **Android app**: auth → product list → scan flow → four pages → Room cache → outbox
   sync → i18n.

> Rationale: the offline outbox and atomic-delta sync only make sense against a working,
> verified API. Debugging the app and the backend simultaneously is the main avoidable
> time-sink.

## Backend testing
- Each Lambda must be testable in isolation with a mocked DynamoDB (moto) or a dev table.
- Confirm atomic-delta behavior explicitly: two concurrent `−1`/`+1` calls must net correctly.
- Confirm the negative-stock guard rejects an over-decrement.
- Test share-code redemption: valid, expired, already-used.

## Android testing
- Verify each of the four pages renders and its primary action works against the dev API.
- Test the **offline path** deliberately: enable airplane mode, make adjustments, confirm
  the UI updates instantly from Room and the outbox drains on reconnect.
- Check all four locales (en/pt/es/fr) render without layout breakage; verify the in-app
  language override.
- Test on both a phone and a smaller-screen viewport.

## User validation
- After each meaningful unit of work, **summarize what changed and how to verify it**, and
  ask the user to confirm before proceeding.
- For backend steps, provide the endpoint and a sample request to try.
- For app steps, describe the screen/flow to exercise.
- If the user reports an issue, fix it before moving on.

## Grounding (MCP)
- Use the **AWS Knowledge MCP** to verify current Cognito Google-IdP Terraform arguments,
  API Gateway Cognito-authorizer config, and DynamoDB atomic-update / TTL syntax before
  generating IaC. Do not generate AWS resource code from memory when the MCP can confirm it.
- Optionally add the **Terraform MCP Server** if generated HCL drifts from current provider APIs.
