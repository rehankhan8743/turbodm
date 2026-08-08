# TurboDM

A production-grade Android download manager. Built in phases.

## Status
**Phase 1 — Foundation + runnable MVP** (this commit)

Includes:
- Gradle (Kotlin DSL) + version catalog
- Hilt DI
- Room database
- Compose UI (Material 3)
- Foreground download service
- Basic single-thread downloader with resume
- Add URL flow, downloads list, settings shell
- Notification progress

## Roadmap
- Phase 2 — Segmented multi-part downloads, queue manager
- Phase 3 — Scheduler, Wi-Fi-only, rules engine
- Phase 4 — Share/clipboard integration, headers/cookies
- Phase 5 — Integrity hashing, speed limiter, proxy
- Phase 6 — Optional: torrent / HLS / cloud sync

## Build
Open the project in Android Studio (Hedgehog or newer), let Gradle sync, and run.

The wrapper is not committed — generate it once with:
```
gradle wrapper --gradle-version 8.9
```

## Permissions required
- `INTERNET` — networking
- `ACCESS_NETWORK_STATE` — connectivity checks
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` — running downloads
- `POST_NOTIFICATIONS` (Android 13+) — progress
- `WAKE_LOCK` — keep CPU on during transfers
