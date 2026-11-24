# Varde

Mobile development management for AI-assisted developers. Varde is a native Android command center for developers who coordinate work from their phones.

See [product vision](docs/VISION.md) and [roadmap](docs/ROADMAP.md).

## Build
Use JDK 17, Android SDK 35 and Gradle 8.9. Run `GH_CLIENT_ID=your_client_id gradle :app:assembleDebug`. The public client ID is required for login; never embed a client secret. Enable Device Flow in the GitHub OAuth app. This initial client requires non-expiring access tokens; expired or revoked sessions require sign-in again.

CI builds a debug APK. Private repository access requires the broad OAuth repo scope, requested only when explicitly selected at login. Organization policies may restrict access.
