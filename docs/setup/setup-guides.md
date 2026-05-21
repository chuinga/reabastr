# Developer Setup Guides

> One-time setup steps for working on **Reabastr**. Read during onboarding.
> Place at `docs/setup/`. If you prefer, split this into
> `google-oauth-setup.md` and `android-studio-setup.md` at the section break.

---

# Part 1 — Google Cloud Console OAuth Setup

Produces the **Client ID** and **Client Secret** that Cognito uses for Google
federated sign-in. The app never talks to Google directly — **Cognito** does — so
the redirect URI you register is Cognito's, not the app's.

## Key concept
The redirect URI follows the Cognito hosted-UI format:
```
https://<prefix>.auth.<region>.amazoncognito.com/oauth2/idpresponse
```
You control `<prefix>`, so decide it now (`reabastr`) and register the URI before
Cognito physically exists. Region is `eu-west-1`.

## Steps

1. **Create a project.** Google Cloud Console → create a new project, e.g. "Reabastr Auth".

2. **Configure the consent screen** (APIs & Services → OAuth consent screen / "Audience").
   - User type: **External** (public consumer app).
   - App name: Reabastr. Add a support email and a developer contact email.
   - Add an authorized domain.

3. **Testing vs production.** A new External app starts in **testing** mode (sign-in
   limited to test users you list). Add your own Google account as a test user during
   development. When ready for anyone to sign in, go to **Audience → Publish app**.
   You only use `email`/`profile` scopes, so this should not trigger a heavy verification review.

4. **Create the OAuth client ID** (Credentials → Create credentials → OAuth client ID).
   - **Application type: "Web application"** — NOT "Android". Cognito is the web server
     performing the OAuth exchange. This is the most common mistake.
   - Name it e.g. "Reabastr Cognito".

5. **Add the authorized redirect URI** (Add URI):
   ```
   https://reabastr.auth.eu-west-1.amazoncognito.com/oauth2/idpresponse
   ```
   - Must be HTTPS (the Cognito URL is).
   - The prefix + region MUST exactly match what Cognito uses later, or you get
     `redirect_uri_mismatch`.

6. **Create and copy credentials.** Click Create. After up to ~5 minutes you're shown the
   **Client ID** and **Client Secret** — copy both now (the secret may be hidden after
   closing the dialog; it can be reset later if needed).

7. **Propagation.** Settings changes can take 5 minutes to a few hours to take effect.
   If the first sign-in fails right after setup, wait before assuming misconfiguration.

## Plugging into Terraform (do NOT commit secrets)
- Store the Client ID and Secret in **AWS Secrets Manager** (or SSM Parameter Store).
- The Cognito identity-provider Terraform resource references the secret — never paste
  the secret into `.tf` files or commit it.
- Verify the exact Cognito Google-IdP resource arguments / attribute mapping against
  current AWS docs (use the AWS Knowledge MCP) when writing the Terraform.

## Recommended order (resolves the chicken-and-egg)
1. Decide Cognito prefix now: `reabastr`.
2. Register `https://reabastr.auth.eu-west-1.amazoncognito.com/oauth2/idpresponse` in Google.
3. Put Client ID + Secret into Secrets Manager.
4. Terraform creates the User Pool with that exact domain prefix and the Google IdP
   referencing the secret. URI matches because the prefix was chosen up front.

## What you walk away with
**Client ID** + **Client Secret** → fed into Cognito's Google IdP. After that, Cognito
handles the entire OAuth flow; the app just needs a sign-in button.

---

# Part 2 — Android Studio Setup

## Install
1. Download and install **Android Studio** (latest stable) from the official site.
   It bundles the **Android SDK** and a **JDK** — you usually don't install Java separately.
2. On first launch, run the setup wizard and accept the SDK component downloads.
3. Open **SDK Manager** (Settings → Languages & Frameworks → Android SDK) and confirm a
   recent **Android SDK Platform** and **SDK Build-Tools** are installed. Note the API
   level for your `compileSdk` / `targetSdk`.

## Project basics for this app
- **Language:** Kotlin. **UI toolkit:** Jetpack Compose (Material 3).
- When creating/opening the module, ensure Compose is enabled and the Compose BOM is used
  so library versions stay aligned.
- Set a sensible **minSdk** (balance device reach vs. modern APIs; ML Kit and Compose are
  comfortable on modern API levels).

## Physical device — REQUIRED for this app
The barcode scanner (ML Kit camera) is the core interaction, and **the emulator cannot use
a real camera for live barcode scanning.** You need a physical Android phone for meaningful
testing.

1. On the phone: Settings → About phone → tap **Build number** 7× to unlock Developer options.
2. Developer options → enable **USB debugging**.
3. Connect via USB; accept the "Allow USB debugging" prompt on the phone.
4. The device appears in Android Studio's device dropdown → Run.
   (Wireless debugging also works once paired.)

The emulator is fine for everything non-camera: UI, navigation, offline/Room behavior,
the four pages, locale switching.

## Useful while developing
- **Logcat** for runtime logs.
- **Layout/Compose preview** for fast UI iteration without a full build.
- **App Inspection → Database Inspector** to inspect the **Room** cache and the sync
  outbox live on a running app — very useful when debugging offline sync.

## Verifying scanning specifically
- Run on the physical device, grant camera permission, and scan a real EAN-13 (any grocery
  barcode). Confirm the decoded number reaches the app and the adjustment applies.
- Test the offline path with airplane mode: adjustments should update instantly from Room
  and the outbox should drain on reconnect (watch Logcat + Database Inspector).

## Dependencies to wire (grab current versions from official docs)
- **Jetpack Compose** (via BOM) + Material 3.
- **Room** (runtime, compiler/ksp).
- **WorkManager** (background sync).
- **Google ML Kit Barcode Scanning** — the Gradle coordinate changes periodically; copy the
  current line from Google's ML Kit docs.
- A networking client (e.g. Retrofit/OkHttp or Ktor client) for the REST API.
- Cognito/Amplify-auth or a plain OAuth client for the hosted-UI flow (auth library choice
  is a later decision — the app only ever holds the JWT).
