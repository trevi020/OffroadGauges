# Offroad Gauges for Android Auto

![Version](https://img.shields.io/badge/version-0.6%20beta-D4AF37?style=for-the-badge)
![License](https://img.shields.io/badge/license-MIT-2EA44F?style=for-the-badge)
![Android Auto](https://img.shields.io/badge/Android%20Auto-Car%20App%20Library%201.4.0-4285F4?style=for-the-badge&logo=androidauto&logoColor=white)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.7-02303A?style=for-the-badge&logo=gradle&logoColor=white)
![Status](https://img.shields.io/badge/status-experimental-E8590C?style=for-the-badge)

🇬🇧 English · [🇮🇹 Italiano](README.it.md)

An Android app that brings classic off-road instruments to the **Android Auto** display in digital form:
outside temperature, compass, clock, altimeter with barometer, and roll and pitch inclinometers.
The look of the gauges is inspired by the instrument cluster of a modern off-roader (night-sky background, dark
circles with gold rims, white ticks). The project is independent and has no connection with any car maker.

All data comes from the **phone** (GPS, barometer, gravity sensor): the car is only the screen.

> Project status: working and tested on Android Auto, but meant for personal and experimental use.
> The current version is set in `app/build.gradle.kts` (`versionName`).

---

## What it shows

The app draws directly on the Android Auto Surface (like a navigation app). On a full screen there are two
side-by-side pages, each with three circular gauges; switch between them by swiping or with the tabs at the bottom.

| Page | Gauge | Data |
|---|---|---|
| **Ambient** | Temperature | Outside temperature from the internet, for the current GPS position |
| | Compass | Direction of travel (average of the last 3 seconds of GPS), smoothly rotating |
| | Clock | Analog clock with date |
| **Attitude** | Altimeter | Altitude above sea level (GPS) and, below it, barometric pressure |
| | Roll | Lateral inclinometer, artificial-horizon style |
| | Pitch | Longitudinal inclinometer, artificial-horizon style |

In both inclinometers the ground (color and line) and the vehicle, drawn as a stylized Willys Jeep, rotate together
like an artificial horizon. The scale goes from -40° to +40°.

### Partial view (app shown next to other apps)

When Android Auto shows the app next to others, for example music, the app detects the available space:

- **about 2/3 of the screen:** shows **2 gauges** chosen by the user;
- **about 1/3 of the screen:** shows **1 gauge** chosen by the user;
- in these cases there are no pages or tabs.

The gauges are chosen in the phone app, "Partial view" section: any combination of 2 out of the 6 gauges for the
2/3 view, a single one for the 1/3 view. The choice is saved. If roll or pitch is among the selected gauges, the
**ZERO 0°** button also appears.

---

## How it works

### Compass
The heading is the speed-weighted vector average of the GPS bearings of the last 3 seconds, considered only above
about 5 km/h. When stopped it keeps the last known heading. The needle is filtered with a second-order damper that
simulates a glycerin-filled instrument: no jumps and no overshoot.

### Outside temperature
Read from [Open-Meteo](https://open-meteo.com) (free, no API key) at the current GPS position. It refreshes every
10 minutes or after 10 km of movement, retrying every minute while there is no data. The last value is saved and
reused for a few hours. The phone needs a data connection.

### Altimeter and pressure
- **Altitude:** altitude above sea level (MSL) is taken, in order of preference, from Android 14 or later, from the
  altitude field of the NMEA GGA sentence, or from the GPS ellipsoidal height minus 48 m (a fallback correction
  tuned for the Po Plain in Italy, constant `FALLBACK_GEOID_M` in `SensorHub.kt`). The value is filtered and animated.
- **Pressure:** phone barometer, moving average over the last 60 seconds, in hPa. If the phone has no barometer the
  value stays empty.

### Inclinometers
They use the phone's **gravity** sensor (gyroscope fusion), falling back to the filtered accelerometer.

**Phone placement:** flat, screen up, **long side parallel to the car's axis** and top edge toward the windshield
(as on most charging pads and mounts). If it is rotated by 180°, enable the option in the app. The phone app
includes an animated guide that shows how to place it.

Sign convention: positive roll = right side down, positive pitch = nose up.

**Zeroing:** with the phone still and the car on level ground, press **ZERO 0°** on the Android Auto screen
(Attitude page) or in the phone app. Zeroing is refused if the car is moving or the phone is tilted by more than
25°. The value is saved.

### Android Auto interface
- The app is a **navigation** app (Car App Library, `NavigationTemplate`) because it is the only template type that
  gives access to a Surface for free drawing.
- **Swipe** requires the "Pan" button in the template, which is not shown on touchscreens. On units without touch,
  pages can still be switched with the on-screen tabs or with the button in the action strip.
- The template requires at least one action in the top bar: there is exactly one, with a transparent icon, that
  switches page.
- Dithered background: the dark gradient is built with slight noise to avoid the banding that Android Auto's video
  compression would otherwise show.

---

## Requirements

- Android 8.0 or later phone (`minSdk 26`) with GPS. Barometer and gravity sensor recommended.
- **Android Auto** app installed and a compatible car, or the Desktop Head Unit.
- To build: Android Studio (Koala or later) with JDK 17 or 21. The project uses Gradle 8.7, Android Gradle Plugin
  8.5.2, Kotlin 1.9.24 and `androidx.car.app:app:1.4.0`.

---

## Installation

### 1. Build from source

1. Clone the repository and open it in Android Studio. Gradle sync downloads the dependencies by itself.
   If Android Studio reports an incompatible JDK (for example 25), select the bundled JDK 21.
2. Connect the phone with USB debugging enabled and press **Run**, or from a terminal:

```
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

   The APK is in `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Install an APK from Releases
Download the APK from the repository's **Releases** section and install it on the phone, allowing installation from
that source. If you switch from a debug to a signed build, or the other way round, uninstall the app first: the
signatures differ.

### 3. Enable apps from outside the Play Store in Android Auto
1. In Android Auto tap "Version" 10 times to enable **Developer settings**.
2. From the top-right menu open *Developer settings* and enable **Unknown sources**.

### 4. Why the app may not appear in the car's app list

On a real car, a sideloaded app may show up in Android Auto's app list but not in *Customize launcher*. It may also
start by itself as the last used app and then become impossible to relaunch if you pick another navigation app. On
the Desktop Head Unit it works.

The most plausible explanation, from a community analysis
([Fermata, issue #846](https://github.com/AndreyPavlenko/Fermata/issues/846), referring to Android Auto 17.7 and not
officially confirmed by Google), is that Android Auto checks which **installer** is recorded for the app.
"Unknown sources" would apply to media apps but not to template apps like this one.

Solutions, from cleanest to least clean:

1. **Play Store:** publish the app at least on the Play Console internal testing track. For personal testing this is
   the most solid route, but it requires a developer account.
2. **Install with the Play Store recorded as installer.** On the author's phone it worked by installing the APK
   with **KingInstaller** and **Shizuku** (ShizukuNext variant), which record the Play Store as the installer.
   These are third-party tools: use them only if you understand what they do, at your own risk. The recorded
   installer is lost if the app is updated in another way, and the app may then disappear again.
   To check the recorded installer:

```
adb shell dumpsys package com.offroadgauges | grep -E "installerPackageName|initiatingPackageName"
```

   It must show `installerPackageName=com.android.vending`.
3. **Google's Desktop Head Unit**, to test without a car: it does not apply these checks.

### 5. First run
Open the app on the phone, grant the permissions (location and notifications) and, for stable operation with the
screen off, set location to **"Allow all the time"**. Then connect the phone to the car, open the app list and
choose **Offroad Gauges**.

---

## Project structure

```
OffroadGauges/
├── build.gradle.kts            # Gradle and Kotlin plugins
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat        # Gradle wrapper
├── keystore.properties.example # template for signing data (the real file must not be versioned)
└── app/
    ├── build.gradle.kts        # app configuration, dependencies, release signing
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                # icons, strings, automotive_app_desc.xml
        └── java/com/offroadgauges/
            ├── car/
            │   ├── GaugeCarAppService.kt   # Car App Library service (entry point for Android Auto)
            │   ├── GaugeSession.kt         # session: starts sensors and the foreground service
            │   ├── GaugeScreen.kt          # NavigationTemplate, Surface, touch, swipe, visible area
            │   └── PermissionScreen.kt     # location permission request from the car
            ├── core/
            │   ├── SensorHub.kt            # GPS, NMEA, barometer, tilt, zeroing
            │   ├── TelemetryService.kt     # foreground service of type "location"
            │   ├── WeatherRepo.kt          # temperature from Open-Meteo
            │   ├── Damper.kt               # "glycerin" damper for needles and values
            │   └── Prefs.kt                # saved settings (zeroing, chosen gauges, etc.)
            ├── render/
            │   └── GaugeRenderer.kt        # gauge drawing, pages, 1/3 - 2/3 - full modes
            └── ui/
                ├── MainActivity.kt         # phone screen: permissions, guide, gauge selection
                └── PlacementGuideView.kt   # animation showing how to place the phone
```

---

## Building a signed release

1. Generate a keystore **outside** the project folder and make a backup copy right away:

```
keytool -genkeypair -v -keystore offroadgauges.jks -alias offroadgauges -keyalg RSA -keysize 2048 -validity 10000
```

   `keytool` is in Android Studio's JDK, folder `jbr/bin`.
2. Copy `keystore.properties.example` to `keystore.properties` (in the project root) and fill in the values. The
   file is excluded from git: never publish it.
3. Build the release: *Build > Generate Signed App Bundle / APK*, or `./gradlew assembleRelease`.
   The APK is in `app/build/outputs/apk/release/`.

If `keystore.properties` does not exist, the release build comes out unsigned and the debug build works as usual.
Do not lose the keystore or its passwords: without them you cannot install updates over an already installed version.

---

## Known limitations

- The inclinometers are affected by vibration if the phone is not firmly held in its mount: damping steadies the
  needles, but on very rough ground the readings remain indicative.
- The compass heading comes from GPS: below about 5 km/h it does not update. It does not use the magnetic compass.
- GPS altitude has an uncertainty of a few meters. The 48 m fallback correction is valid for the Po Plain: elsewhere
  adjust it in `SensorHub.kt` or rely on Android 14 or later.
- Temperature needs a data connection and shows the value of the nearest weather station, not a car sensor.
- Touch and swipe only work on touch units.
- Detection of the 1/3 and 2/3 views relies on the visible area reported by Android Auto: on some cars the
  thresholds in `GaugeRenderer.kt` may need adjusting.
- The Car App service accepts any host (`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`): fine for personal use, but it
  must be replaced with a list of trusted hosts for public distribution.

---

## License

Distributed under the MIT license: see the [LICENSE](LICENSE) file.
