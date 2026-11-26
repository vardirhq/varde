# Varde

Mobile development management for AI-assisted developers. Varde is a native Android command center for developers who coordinate work from their phones.

See [product vision](docs/VISION.md) and [roadmap](docs/ROADMAP.md).

## Build
Use JDK 17, Android SDK 35 and Gradle 8.9. Run `GH_CLIENT_ID=your_client_id gradle :app:assembleDebug`. The public client ID is required for login; never embed a client secret. Enable Device Flow in the GitHub OAuth app. This initial client requires non-expiring access tokens; expired or revoked sessions require sign-in again.

CI builds a debug APK. Private repository access requires the broad OAuth repo scope, requested only when explicitly selected at login. Organization policies may restrict access.

## Mobile workspace

Open a repository for recent commits, open issues and pull requests, changed files, conversation and review comments, check runs, workflow runs, jobs, logs, and repository files. Large file and log content is truncated. The attention feed gathers assigned work, review requests, failing PR checks, and the latest observed run of each workflow; GitHub permission and rate limits can leave the feed incomplete. Settings can exclude repositories. Pins, groups, and cached responses stay in app-private storage per account. Cached screens show when they were saved; logs are not cached. Optional background checks (Settings → Notifications) poll GitHub about every 30 minutes over WorkManager, using conditional requests; the first check only records existing items, and nothing is sent anywhere except GitHub. Optional on-device assistance (Settings → On-device assistance, off by default) uses Gemini Nano through ML Kit on supported phones to draft handover tasks, rewrite review comments and suggest log lines; repository instructions and tracking IDs never pass through the model, and ML Kit is only started while the feature is on.

## Debug APK updates

CI signs debug APKs with the public debug-only key in `app/debug-keystore.b64`. This allows subsequent APKs from this workflow to be installed as updates while retaining local data and the encrypted GitHub session. APKs signed with an older runner-generated key cannot be upgraded in place; uninstalling once removes local data and requires a fresh sign-in. The included key is public and is never appropriate for release signing.
