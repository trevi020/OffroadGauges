# CLAUDE.md — Offroad Gauges

Android Auto app that draws digital off-road gauges (inspired by the Mitsubishi Pajero 2027 cluster). All data comes
from the phone (GPS, barometer, gravity sensor); the car is only the screen. Personal/experimental project, MIT.

## Working with the owner
- Owner: Alberto (Padova, Italy). Talk to him in **Italian**; code, comments, UI strings, README and commit messages
  are in **English** (an Italian README copy lives in `README.it.md`).
- He prefers complete, ready-to-use files over fragments, and technical answers with concrete commands and tradeoffs.
- He builds and tests in Android Studio on a real phone and cars. Never claim something was compiled or tested on
  a device unless you actually ran it. Say what was verified and what was not.

## Build
- Gradle 8.7 (wrapper), AGP 8.5.2, Kotlin 1.9.24, `compileSdk/targetSdk 34`, `minSdk 26`, Java/Kotlin target 17.
- Run Gradle with JDK 17 or 21. **JDK 25 does not work** with Gradle 8.7.
- Debug build: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
- Only dependencies: `androidx.car.app:app:1.4.0`, `core-ktx`, `lifecycle-common`, `kotlinx-coroutines-android`.
- Version lives in `app/build.gradle.kts` (`versionCode = 6`, `versionName = "0.6"`, beta/pre-release). Bump both.

## Signing and secrets (important)
- Release signing reads `keystore.properties` in the repo root (gitignored, template: `keystore.properties.example`).
  If the file is missing, the release build is unsigned and debug still works.
- The keystore (`*.jks`) lives **outside** the project. Never create, move, print, commit or upload keystore files
  or passwords. Never run `git add -f` on ignored files. The release APK is signed and uploaded by Alberto himself.

## Architecture (`app/src/main/java/com/offroadgauges/`)
- `car/GaugeCarAppService` (ALLOW_ALL_HOSTS_VALIDATOR, personal use), `GaugeSession` (acquires SensorHub, starts
  TelemetryService), `PermissionScreen`, `GaugeScreen`.
- `GaugeScreen` uses a `NavigationTemplate` (the only template that gives a Surface). It needs at least one action in
  the action strip (one transparent-icon action that toggles the page) and `Action.PAN` in the map action strip to
  receive scroll/fling. The Pan button is not shown on touchscreens. `SurfaceCallback` forwards
  `onVisibleAreaChanged`, `onClick`, `onScroll`, `onFling` to the renderer.
- `render/GaugeRenderer`: draws with `lockHardwareCanvas` on a HandlerThread at ~30 fps. Design space height is 520,
  width adapts to the area. Mode is chosen from visibleArea width / surface width: FULL (3 gauges, 2 swipeable pages),
  TWO (~2/3, 2 user-chosen gauges), ONE (~1/3, 1 user-chosen gauge); thresholds 0.8 and 0.45 in `render()`.
  Gauge ids: 0 temperature, 1 compass, 2 clock, 3 altimeter, 4 roll, 5 pitch. Background is a pre-built dithered
  bitmap (against H.264 banding) — do not replace it with a plain gradient.
- `core/SensorHub` (singleton, ref-counted `acquire()/release()`): GPS, NMEA GGA MSL altitude, barometer (60 s
  average), tilt, heading. `Damper` = critically damped second-order smoothing ("glycerin" needle). `Prefs` =
  SharedPreferences (zero offsets, rotated180, temperature cache, partial-view choices). `WeatherRepo` = Open-Meteo.
  `TelemetryService` = foreground service type `location`.
- `ui/MainActivity` (permissions, placement guide, rotated-180 option, partial-view selection, live readout, zero
  button) and `ui/PlacementGuideView` (7 s looping animation).

## Conventions that must not break
- Phone is mounted flat, screen up, long side along the car axis, top edge toward the windshield.
  Phone X = car right, Y = forward, Z = up.
- **Roll + = right side down. Pitch + = nose up** (`atan2(y, hypot(x, gz))`). Do not flip these again.
- Artificial horizon: ground, ground line and car rotate together (roll `rotate(+shown)`, pitch `rotate(-shown)`),
  clipped to the circle, fixed ±40° scale.
- Zero calibration is refused if speed > 1 m/s or tilt > 25°. Roll/pitch offsets are saved in `Prefs`.
- Heading: speed-weighted vector average of GPS bearings over 3 s, only above 1.5 m/s; keeps the last value when stopped.
- Altitude MSL order: Android 14 `mslAltitudeMeters` → NMEA GGA MSL field → ellipsoid − `FALLBACK_GEOID_M` (48 m,
  Po Plain).
- Layout is resolution independent: never hardcode pixel sizes outside the design space.
- Digits are centered in the dial and the unit symbol sits to the right without shifting them (temperature, compass).

## Android Auto installation gotcha
Template apps are validated by the installer that is recorded: sideloaded APKs may be missing from the car launcher
(works on the Desktop Head Unit). "Unknown sources" only covers media apps. Workaround that worked for the owner:
install with KingInstaller + ShizukuNext so the Play Store is the recorded installer. Check with
`adb shell dumpsys package com.offroadgauges | grep -E "installerPackageName|initiatingPackageName"`
(expected `com.android.vending`). Source: Fermata issue #846, AA 17.7, not officially confirmed by Google.

## Verification checklist for changes
1. `./gradlew assembleDebug` compiles (needs JDK 17/21).
2. UI/renderer changes: test on the Desktop Head Unit in all three modes (FULL, ~2/3, ~1/3) and both pages.
3. Sensor changes: check sign conventions and the re-zero flow on a real phone.
4. Keep README (and `README.it.md`) in sync with user-visible behavior; keep UI strings in English.

## Git
- Remote is the owner's GitHub repo, branch `main`. Releases (APK/AAB) go to GitHub Releases, never into the repo
  (`*.apk`, `*.aab` are gitignored).
- Do not commit `keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, `.idea/` or `build/`.
