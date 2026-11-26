# Roadmap

1. Foundation: Android 8+, Compose, GitHub Device Flow, secure session, paginated repository list, owner filter, repository links, CI APK.
2. Repository workspace: offline cache, details, commits, issues, PRs, file browser, workflow status and logs.
3. Development inbox: cross-repository attention queue, review diffs, rerun workflows, explicit merge confirmation.
4. Portfolio: product grouping, priorities, lifecycle, notes, milestones, pinned projects and idea incubator.
5. Agent handovers: gather context, edit and share briefs, track linked PRs, optional provider integrations.
6. Background sync and notifications: periodic local checks with WorkManager for failing checks, review requests and delegation progress; conditional requests to respect rate limits; no backend.
7. Optional on-device assistance: off by default and offered only on supported devices, with every feature working without it. Draft handover briefs from a short request and the gathered evidence (task, acceptance criteria, selected log lines), rewrite review comments, and suggest relevant log lines. Repository instructions (AGENTS.md, CLAUDE.md) and the tracking ID are always included verbatim, never summarized. Output is marked as generated, stays editable, and is never shared or sent to GitHub without confirmation. No inbox ranking, progress scores or diff summaries from the model.
8. Insights: activity summaries and release readiness based on actual evidence.

Foundation acceptance: install APK, sign in with GitHub, cancel login, restore session, list accessible personal and organization repositories, filter by owner, refresh, sign out. Test on physical Android. No client secret in APK. CI must compile and run unit tests. Later phases are not implemented by the foundation.
