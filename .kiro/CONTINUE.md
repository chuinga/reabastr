# Continue From Here

## Current State

The Android app **builds and runs on the emulator**. Sign-in works (Google OAuth +
email/password). The four pages are wired behind a bottom navigation bar.

**Build:** `cd android && gradlew.bat assembleDebug` (JDK 17 / Corretto on PATH).
**Install:** `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`
(`adb` is not on PATH — use the full path above.)

**Versions:** Kotlin 2.2.10, KSP 2.2.10-2.0.2, Gradle 9.5.0, AGP 9.2.1, JDK 17.
The earlier JDK 22 / KSP "unexpected jvm signature V" issue is resolved (JDK 17 active).

## Recent Work (this session)

1. Fixed OAuth callback handling — `OAuthCallbackHolder` is now Compose-observable
   and the token exchange runs in a `LaunchedEffect` (previously the code was never
   exchanged, so sign-in appeared to do nothing).
2. Added bottom navigation (`nav/MainScaffold.kt`) wiring all four pages:
   Take (Home), Buy (Shopping list), Setup, Settings.
3. New auth flow: `WelcomeScreen` (logo + Log in / Sign up) → `SignInScreen` (with back)
   or `SignUpScreen` (email/password registration + email-code confirmation via Cognito
   SignUp/ConfirmSignUp/ResendConfirmationCode in `CognitoAuthService`).
4. Home page now supports both **−1 (take)** and **+1 (put)** per product, plus a
   manual **Add product** action (top-bar + and empty-state button). Quick-create sheet
   now allows an optional/typed EAN (manual entry, not just camera scan).
5. 16 KB page-size compatibility: bumped CameraX 1.3.4 → 1.4.2 and DataStore 1.1.1 → 1.2.1.
   Verified all bundled `.so` files are now `p_align=16384`.

## Infrastructure Status

- Cognito User Pool: `eu-west-1_ZRswNSk8l`
- App Client ID: `4fm03v1ivocpi0qdc42sblfgg5` (USER_PASSWORD_AUTH + Google enabled)
- API Gateway: `https://ii7iuqvzek.execute-api.eu-west-1.amazonaws.com/v1/`
- AWS profile: `miguel`, region eu-west-1, account 058264503354

## Known Notes

- Chrome on this emulator image crashes intermittently (`SIGILL`), which can make the
  Google Custom Tab flow flaky. Email/password sign-in avoids the browser entirely.
- Task 18 (i18n strings for pt/es/fr) still pending — new UI strings were added to the
  default `values/strings.xml`; translations not yet added for the new keys.

## Remaining Spec Tasks

- 18.1 — Internationalization string resources (pt/es/fr) for all UI incl. new strings.
- 20 — Final checkpoint (all tests pass, full integration verified).
