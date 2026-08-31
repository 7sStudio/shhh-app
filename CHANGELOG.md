# Changelog

All notable changes to this project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning: [SemVer](https://semver.org).

## [1.6.0] - 2026-08-31

### Added
- 🫥 **A second, transparent widget style.** The widget picker now offers
  "Shhh (transparent)" next to the classic card: the same one-tap toggle with
  no background behind it, blending straight into the wallpaper. While hushed
  its glyph and label tint to the accent color instead of filling a card.
  Both styles can be pinned straight from the app's Quick access settings.
  Much requested.
- 👓 **The transparent widget colors itself for your wallpaper.** It reads the
  same wallpaper signal the lockscreen clock uses: near-black content on
  wallpapers light enough for dark text, white on everything else, and hushed
  tints pick the matching light or dark step of the Material You palette — so
  it stays readable in light theme on a dark wallpaper and vice versa.
  Changing wallpaper recolors the widget on the spot — the app listens for
  the system's wallpaper-color recomputation — with the next tap or scheduled
  update as the fallback.

## [1.5.0] - 2026-08-30

### Added
- 🌍 **Five more languages** — Hindi, Bengali, Russian, Urdu (with RTL layout)
  and German, bringing the total to thirteen.

### Fixed
- 📶 **The Quick Settings tile now updates while Do Not Disturb is on.** With a
  DND mode running, tapping the tile hushed the phone but the tile kept its
  old look until the panel was closed and reopened. Nothing was left to tell
  it: the refresh the 1.4.0 service relied on
  (`TileService.requestListeningState`) is a documented no-op for a passive
  tile, and the ringer-mode broadcast it falls back on never fires under a
  zen mode, which pins the external ringer at silent. The state is now pushed
  to the tile directly, in-process.
- 🧷 **A refused toggle can no longer leave the tile or widget lying.** If
  Android refuses the change (a Do Not Disturb mode starting at the exact
  moment of the tap), both surfaces now snap back to the phone's real state.

### Changed
- ⏱️ **The automatic update check now runs up to once an hour** when the app
  opens, instead of once a day, so a new release reaches you sooner. Still
  opt-in, still nothing uploaded.
- 🌊 **Downloading an update shows a Material 3 Expressive progress bar** —
  the wavy, always-in-motion kind — instead of a static line.
- ⚡ **The tile and widget flip instantly.** Both now show the expected state
  the moment they're tapped — like Wi-Fi or the torch — and the real state
  confirms it right after (measured on a Pixel: under 100 ms where it used to
  take up to a second, or forever under DND).

## [1.4.0] - 2026-08-30

### Fixed
- 🌙 **Shhh no longer switches your Do Not Disturb off.** With DND, Bedtime or a
  driving mode already running, hushing and then un-hushing used to end it, and
  the phone came back fully audible. Shhh now moves the ring and media volume
  sliders and nothing else, so it can neither start nor end those modes — under
  every kind of DND, including "Alarms only" and "Total silence".
- 🔊 **Hushing during Do Not Disturb now really hushes.** It used to leave the
  ringer alone and lean on your DND for the silence.
- 📱 **The Quick Settings tile no longer closes the notification panel.** It now
  behaves like Wi-Fi or the torch: it flips, and the panel stays where you left it.

### Added
- 🌍 **Five more languages** — Spanish, Portuguese, Simplified Chinese, Japanese
  and Korean, joining English, French and Arabic.
- ⏰ **Alarms are guaranteed untouched.** Shhh never changes alarm volume, so a
  hushed phone still wakes you. Now locked down by tests.

### Changed
- 🔓 **Do Not Disturb access is no longer required.** Shhh works with no special
  permission. The grant is only needed to hush while one of your DND modes is
  already running, and the setup card now says so instead of looking like an error.
- 🤝 **Your Do Not Disturb wins.** While one of your modes is on, un-hushing
  restores the sliders but the phone stays as quiet as that mode says.

### Removed
- 🔕 **The "Silent" hush option.** Hushing is always vibrate now. Reaching a truly
  silent ringer forces Android to switch Do Not Disturb on — and off again
  afterwards — which is exactly the interference this release removes.

## [1.3.0] - 2026-08-30

### Added
- **Built-in updater.** Settings gains an "Updates" section: a manual "Check
  for updates" row asks the GitHub releases API for the latest version,
  downloads the APK and hands it to Android's installer (behind the system's
  one-time "Install unknown apps" grant), and an optional automatic check —
  **off by default** — runs at most once a day when the app opens and prompts
  once per new version. This adds the `INTERNET` and
  `REQUEST_INSTALL_PACKAGES` permissions; the network is used exclusively for
  the release check and the APK download, and nothing is ever uploaded
  (README and PRIVACY updated accordingly).
- **Contact the developer.** An About row that opens a pre-filled email to
  7sStudio@tutamail.com for bugs, feature requests and questions.
- **Release smoke tests** (`scripts/release-smoke-test.sh`, and a CI job) that
  run a minified, shrunk release build on a device/emulator: cold start plus
  the full widget pipeline (receiver → WorkManager → Glance → RemoteViews).
  All other tests exercise debug artifacts, so R8/resource-shrinker breakage
  was previously invisible until a release reached a phone.

### Changed
- **No more corner or edge animations, anywhere.** Revealed content used to
  expand from the top-start corner (quiet-hours dial and day chips, the fixed
  restore slider, the countdown card) and screens slid in horizontally; every
  reveal and transition now uses a quick 150 ms in-place fade
  (`ui/Transitions.kt` — the app-wide rule is centered fade or nothing).
- The project no longer targets F-Droid: the fastlane metadata and the
  F-Droid-specific build tweaks are gone. Distribution is GitHub Releases
  (plus the new in-app updater).

### Fixed
- **The home-screen widget no longer shows "Can't load widget"** in release
  builds (reported on a Pixel 10 running Android 17, but affected every
  device). R8 stripped the zero-argument constructor of WorkManager's
  `OverwritingInputMerger`, which WorkManager instantiates reflectively, so
  every Glance widget-update worker crashed before publishing the widget's
  views. A ProGuard keep rule for `androidx.work.InputMerger` subclasses fixes
  it.
- **The widget no longer freezes on a stale state after a toggle** (e.g. stuck
  on "Hushed" after a successful un-hush, until its next half-hourly update).
  Glance keeps a widget's composition alive between updates and only
  re-executes it when state it can observe changes; the widget read
  AudioManager directly inside the composition, which the snapshot system
  cannot see, so refreshes while a session was alive skipped recomposition and
  re-published the old UI. The composition now reads a snapshot-backed state
  holder that every refresh re-seeds from the phone's live state. As added
  hardening, the controller also waits — bounded at 200 ms — for an
  asynchronously-applied ringer write to be readable before surfaces refresh.
- The Quick Settings tile's state receiver is now verifiably unregistered on
  an unbind kill (test-only gap: `onDestroy` was untestable under Robolectric
  and had slipped out of the coverage gate).

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
