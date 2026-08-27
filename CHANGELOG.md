# Changelog

All notable changes to this project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning: [SemVer](https://semver.org).

## [Unreleased]

_Nothing yet._

## [1.2.0] - 2026-08-27

### Added
- **Multilingual support** — the app is now fully translated into **French** and **Arabic**, including Right-to-Left (RTL) layout support.
- **Troubleshooting link** — added "dontkillmyapp.com" reference in settings to help with OEM battery optimization issues.
- New integration tests for scheduling and localization logic.
- Full unit-test coverage of every layer, including the Compose screens, the
  Glance widget, the Quick Settings tile and the `ToggleActivity` trampoline —
  277 headless tests. `koverVerifyDebug` now gates coverage in CI.
- `android:roundIcon`, so launchers that ask for a round icon get the adaptive
  one that was already in the project but never referenced.

### Changed
- **Minimum Android version is now 14 (API 34)**, up from 12 (API 31). Every
  `Build.VERSION.SDK_INT` fork and every `ContextCompat` shim in the app is gone
  with it: the trampoline activity, the tile, the momentary foreground service,
  the notification permission check and the "add tile" flow now call the modern
  API directly.
- Repository moved to `7sStudio/shhh-app`; the in-app "Source code" link and the
  privacy contact point at the new location.
- Dependencies updated to their latest stable versions: Kotlin/Compose compiler
  plugin 2.4.10, coroutines 1.11.0, Robolectric 4.16.1, MockK 1.14.11, and the
  GitHub Actions used by CI. AGP, Gradle, the Compose BOM and the AndroidX
  libraries were already current.

### Fixed
- **A refused media-volume change no longer reports the whole hush/un-hush as
  failed.** The ringer moves first and its outcome alone decides the result, so
  a volume write that Android declines can no longer leave the phone audible
  while the timer stays armed and the tile and widget still read "hushed".
- **A hush timer that cannot restore is no longer thrown away.** `onTimerFired`
  now clears the stored timer and its notification only after the restore has
  actually succeeded, so the countdown's "Restore now" action survives a Do Not
  Disturb grant that was revoked mid-timer.
- **Clock faces follow the phone.** The quiet-hours dial, the home screen
  summary and the countdown notification asked `java.time` for a "short" time,
  which ignores both the per-app language and the system 24-hour setting — a
  24-hour phone could read "11:00 PM" on the dial and 23:00 in the time picker.
  They now all go through the platform formatter.
- Removed dead code found while covering it: an unused `trailing` parameter on
  the settings rows and an unreachable fallback in `QuietHours.nextStart`.
- Cleared every Android Lint and Kotlin compiler warning in the project
  (autoboxed `mutableStateOf`, `Uri.parse` over the KTX extension, the
  deprecated `android:allowBackup`, a redundant `-v26` resource qualifier).
- Dropped two strings no layout or code referenced.

## [1.1.0] - 2026-08-27

### Added
- **Timed hush** — 15 min / 30 min / 1 h / 2 h chips; sound returns automatically at the exact minute.
- **Quiet hours** — automatic schedule with start/end times and per-weekday selection.
- **Live countdown notification** (optional) — Android 16+ Live Update chip with time remaining and a "Restore now" action.
- **Hush behavior settings** — vibrate or silent ringer; restore media to the previous level or a fixed percentage.
- **Bluetooth headphones option** — media volume comes back when headphones connect; ringer stays hushed.
- **Launcher shortcuts** — long-press the app icon: Toggle, Hush 30 min, Hush 1 hour.
- **Automation intents** — `ToggleActivity` accepts HUSH / UNHUSH / TOGGLE / RESTORE_MEDIA actions (Tasker, routines, `am start`).
- Redesigned home screen and a new grouped settings screen.

### Changed
- "Hushed" now recognizes silent mode as well as vibrate, however it was set.
- Scheduled transitions run through a momentary foreground service (required by Android 16+ audio hardening).

## [1.0.0] - 2026-08-27

### Added
- One-tap quiet mode: ringer to vibrate + media volume to 0, and back with previous-volume restore.
- Quick Settings tile, Glance home screen widget, Material 3 Expressive app with Material You dynamic color.
- Invisible trampoline activity working around Android 16+ background audio hardening.
