# Contributing to Shhh

Thanks for your interest! Bug reports, feature requests and PRs are welcome.

## Building

- JDK 17+ and the Android SDK (or just open the project in Android Studio).
- `./gradlew assembleDebug` builds; `./gradlew testDebugUnitTest` runs the unit tests.
- Instrumented tests need a device/emulator: `./gradlew connectedAndroidTest`.

## Ground rules

- Keep it lightweight: no new runtime dependencies without a strong reason — the app currently ships with AndroidX only and **no internet permission**; that stays.
- Kotlin + Jetpack Compose (Material 3 Expressive). Match the existing style; `./gradlew lintDebug` must stay clean.
- Every behavior change to `QuietModeController`, `HushManager` or `QuietHours` needs a unit test.
- Read the "Android 16+ audio hardening" section of the README before touching anything that changes volume or ringer state from the background — it is the app's central constraint.

## Commit / PR

- Small, focused PRs against `main`.
- Describe *why*, not just what.
- Update `CHANGELOG.md` under an `[Unreleased]` heading when user-visible.
