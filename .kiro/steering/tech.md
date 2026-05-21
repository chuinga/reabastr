# Tech Stack

> Persistent context for Kiro. This project is a native Android app with a
> serverless AWS backend. Do not introduce technologies outside this list
> without flagging the deviation first.

## Android client
- **Language:** Kotlin (no Java).
- **UI:** Jetpack Compose with **Material 3**. Dark + light themes, modern design.
- **Local storage:** Room (offline cache + sync outbox).
- **Background sync:** WorkManager.
- **Barcode scanning:** Google ML Kit, on-device, EAN-13 / EAN-8.
- **Auth on device:** Cognito-issued JWT only. The app never holds AWS credentials.

## Backend
- **Compute:** AWS Lambda, **Python** (3.12+).
- **API:** Amazon API Gateway (REST) with a **Cognito authorizer**.
- **Data:** Amazon DynamoDB, **single-table design**, partitioned by household list.
- **Auth:** Amazon Cognito User Pool with **Google federation** and **email/password**
  (email verification via Cognito-native email — no SES).
- **Region:** eu-west-1 (Ireland).

## Infrastructure as Code
- **Terraform only.** Do **not** use AWS Amplify, CDK, or CloudFormation directly.
- Remote state in **S3 with native lockfile locking**.
- A one-time **bootstrap** Terraform (state bucket + GitHub OIDC provider + deploy role)
  is applied manually; the main pipeline depends on it and must not recreate it.

## CI/CD
- **GitHub Actions.** Authenticate to AWS via **GitHub OIDC** — never long-lived access
  keys in secrets.
- Monorepo with **path-filtered** workflows (Android changes don't trigger Terraform, etc.).

## Explicitly NOT used
- ❌ AWS Amplify (hosting, DataStore, or libraries) — ruled out deliberately.
- ❌ CDK / CloudFormation as the primary IaC.
- ❌ AppSync / GraphQL — the API is plain REST.
- ❌ Sending camera frames to the backend — barcodes are decoded on-device.
- ❌ SES — sharing uses share codes/links, not email invites.
