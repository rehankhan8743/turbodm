# TurboDM

A production-grade Android download manager. Built in phases.

## Status
**Phase 2 — Segmented multi-part downloads + queue + retry** (latest)

What's working:
- Gradle (Kotlin DSL) + version catalog, KSP, Hilt
- Room v2 with a real migration from v1
- Compose UI (Material 3) with share-to-download
- Foreground service with progress + cancel notifications
- Segmented multi-part downloads (configurable segment count, capped at 64 MB/chunk)
- Per-chunk retry with exponential backoff (1/2/4/8/16s, transient errors only)
- Resume after process kill — chunk state persisted, partial bytes re-requested via Range
- Queue manager with bounded concurrency (`maxParallel`), priority + FIFO ordering
- Speed tracker with sliding 1s window (notification shows real KB/s)
- DataStore-backed settings: parallel / Wi-Fi-only flag / speed limit / segments / UA / folder

## Roadmap
- Phase 3 — Wi-Fi-only enforcement, scheduler, rules engine
- Phase 4 — Share/clipboard integration, headers/cookies UX
- Phase 5 — Integrity hashing, real speed limiter, proxy
- Phase 6 — Optional: torrent / HLS / cloud sync

## Build

### Standard (Android Studio / macOS / Linux / Windows)
Open in Android Studio Hedgehog or newer. Let Gradle sync, then **Run**.

### Termux
Termux on Android can build the APK but needs workarounds:
1. Install the Android SDK (`sdkmanager`) and `platforms;android-35` inside Termux.
2. Create `local.properties` with your SDK path (gitignored):
   ```
   sdk.dir=/path/to/your/android-sdk
   ```
3. AGP's bundled `aapt2` is glibc-only and crashes on Termux with
   `aapt2: syntax error: unexpected '('`. Override it in `local.properties`:
   ```
   android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
   ```
4. Run `./gradlew assembleDebug` from the project root. The wrapper is committed.

## Permissions required
- `INTERNET` — networking
- `ACCESS_NETWORK_STATE` — connectivity checks
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` — running downloads
- `POST_NOTIFICATIONS` (Android 13+) — progress
- `WAKE_LOCK` — keep CPU on during transfers
