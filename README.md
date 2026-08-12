# TurboDM

A production-grade Android download manager. Built in phases.

## Status
**Phase 2 — Segmented multi-part downloads + queue + retry + stability audit** (latest)

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

### Recent bug fixes (audit 2026-08)
- **Double-skip on resume** — `ChunkPlanner.reconcile` shifts partial-chunk start bytes forward, and `DownloadEngine.runChunk` was re-adding the same persisted offset *again*. Fixed: only `reconcile` shifts now. See `regression test` in `ChunkPlannerTest`.
- **Queue starvation by torrents** — `QueueManager` was counting torrent `ANALYZING` rows toward `maxParallel`. Fixed: only `DOWNLOADING` HTTP rows consume permits.
- **Connectivity flap churn** — `onCapabilitiesChanged` fires on every bandwidth change; now debounced to 2s.
- **Orphaned files on delete** — swipe-to-delete used to leave the `.part`/target file on disk. Now routes through `DownloadController.delete` which removes both.
- **Streaming URLs picked audio track** — preferred highest-bitrate audio; now prefers muxed video+audio streams so the user actually gets the video.
- **Percent-encoded filenames** — URLs and `Content-Disposition` filenames stayed `%20`-encoded. Now URL-decoded before saving.
- **Notification spam** — one toast per progress event; now throttled to ~1/sec per download with stale entry cleanup.

### Recent enhancements
- **Bandwidth throttle** — `speedLimitBps` setting now actually enforced via a per-download token bucket in `DownloadEngine`. UI slider drives a real, effective cap.

## Roadmap
- Phase 3 — Wi-Fi-only enforcement, scheduler, rules engine, **audio-only mode for streaming sites** ✓
- Phase 4 — Share/clipboard integration, headers/cookies UX
- Phase 5 — Integrity hashing ✓, real speed limiter ✓, proxy
- Phase 6 — Optional: torrent / HLS / cloud sync

## Audio-only mode
Streaming URLs (YouTube, TikTok, Instagram, SoundCloud, Bandcamp, Vimeo, Twitch,
Dailymotion) can now be saved as the **best audio stream only** instead of video.

**How to use:** Add download → paste video URL → toggle **"Audio only"** (appears
automatically for supported hosts) → start. The file is saved as `.opus` / `.m4a` /
`.flac` / `.aiff` matching whatever container the source actually exposes.

**Auto-categorization** moves audio files into `music/` under your download dir.

## Working with geo-blocked or region-locked content
YouTube and TikTok sometimes mark a video "not available to everyone". When that
happens, the app shows a clear failure message instead of cryptic HTML. To fetch
such videos you'd need either:
- A proxy / VPN that gives you an IP in the eligible region (usually paid, and
  against the app's ToS — **not implemented here**);
- Or, the same content uploaded elsewhere (often YouTube mirrors the same creator).

We will never try to silently route your traffic through proxies; that's both a
privacy and a legal issue.


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
