# ppg-bp-android

<p align="center">
  <img src="docs/branding/logos/banner.svg" alt="ppg-bp-android: an arterial/PPG pulse waveform mark, a phone glyph, and the ppg-bp-android wordmark" width="500">
</p>

Native Kotlin app that records raw PPG + motion data from a Polar Verity Sense over BLE, stages it on-device, and syncs it to a self-hosted server when it can. Part of a three-repo project for estimating blood pressure trends from raw PPG in cases that need per-person calibration, such as the autonomic BP instability seen in Multiple System Atrophy (MSA).

Not a medical device. Read [DISCLAIMER.md](DISCLAIMER.md) before using this for anything real.

## What it does

The app connects to a Polar Verity Sense and streams PPG (176 Hz, 4-channel), accelerometer, and gyroscope (up to 416 Hz) via the Polar BLE SDK. Raw samples are written to disk as rotating per-sensor files (ROP format, see `ppg-bp/docs/design/raw_rop_storage.md`), so a crash loses at most one rotation window, not the whole session.

It also pairs with and reads an Omron blood pressure cuff over BLE (reverse-engineered protocol, since the cuff has no public API) to collect calibration data. Recording is fully offline; staged bundles sync to a server over HTTP whenever one is reachable, with no cloud dependency and no requirement to be online while recording. A foreground service keeps recording alive when the app goes to background.

## Why native Kotlin, not a cross-platform framework

The Polar BLE SDK is Kotlin/Java-first. Background BLE streaming reliability on Android already has enough sharp edges (service lifecycle, battery optimization killing connections, GATT callback threading) without adding a cross-platform bridge on top of it.

## Architecture

```
[Polar Verity Sense]
       │ BLE (Polar BLE SDK)
       ▼
[PolarRepository] — owns the connection, one coroutine per sensor stream
       │
       ▼
[SessionWriter] — packs samples into per-sensor ROP files, rotates on a timer
       │
       ▼
[on-device storage] — app-private external files dir, no runtime storage permission needed
       │
       ▼ (WorkManager, opportunistic)
[SyncWorker / CuffSyncWorker] — HTTP upload to the server, resumable per-file
```

Each sensor's BLE stream startup is wrapped in a retry-with-backoff loop, not a bare `try/catch`. This exists because of a real bug: a transient failure in the stream-settings request killed a sensor's coroutine silently for the rest of a 6-hour session, with the UI counters for the *other* sensors still climbing normally. Nothing crashed, nothing logged an error the user would see — the session just quietly had a gyroscope and no PPG. If you're touching `PolarRepository`, keep the retry wrapper.

## Debug harness

A debug-only source set (`src/debug/`, compiled into `debug` and `releaseTest` only, never into a shipping release) exposes an ADB broadcast interface to drive the app without touching the UI — useful for wireless-ADB testing where you don't have a screen in front of you.

**Every one of these needs `--receiver-include-background`.** Without it Android silently drops delivery to an app that isn't in the foreground: `am broadcast` still prints `result=0` as if it worked, but `onReceive` never fires. Confirmed on Android 14. The e2e suite bakes the flag in (`tests_e2e/e2e/adb.py`); these examples previously omitted it and did nothing.

```bash
B="adb shell am broadcast --receiver-include-background -a"

$B com.polarppgbp.debug.START_RECORDING --es profile calibration --es device_id <your-polar-serial>
$B com.polarppgbp.debug.STOP_RECORDING
$B com.polarppgbp.debug.STATUS
$B com.polarppgbp.debug.SYNC_NOW
$B com.polarppgbp.debug.SET_SERVER --es url http://<host>:8000 --es token <token>
$B com.polarppgbp.debug.SET_DEVICE_ID --es id <your-polar-serial>
$B com.polarppgbp.debug.CHECK_SERVER
$B com.polarppgbp.debug.PAIR_CUFF
$B com.polarppgbp.debug.READ_CUFF
```

Targeting the receiver explicitly (`-n com.polarppgbp/com.polarppgbp.debug.DebugCommandReceiver`) also works, if you prefer not to rely on the flag.

## Configuring your own device

The app needs a Polar device ID to auto-connect to on startup. It never ships with a real device ID hardcoded. Set one of:

- **Recommended:** pair once through the UI, or send a `SET_SERVER`/`device_id` debug broadcast (see above) — the app remembers it in `SharedPreferences` from then on.
- **Build-time default:** add `defaultDeviceId=<your-polar-serial>` to your local, gitignored `local.properties`, or pass `-PdefaultDeviceId=<your-polar-serial>` to Gradle. This becomes `BuildConfig.DEFAULT_DEVICE_ID`, used only as a fallback when no device has been paired yet.

If neither is set, the recording service logs a warning and refuses to start rather than silently doing nothing.

Logs: `adb logcat -s PolarRepo SyncWorker CuffSyncWorker DebugCmd`

## Status

| Component | State |
|---|---|
| BLE capture: PPG 176 Hz, ACC/GYRO 416 Hz | Done. Verified against real hardware, resilient stream startup. |
| Rotating ROP file writer | Done. Crash-safe. |
| Omron cuff pairing and read | Done. Real bonding quirks handled, see the cuff protocol doc. |
| Offline-first recording, opportunistic HTTP sync | Done. Session bundles and cuff readings. |
| ADB debug command harness | Done. |
| Foreground-notification UX | In progress. Polish pending. |
| Automatic posture tagging from accelerometer | Not started. Data is captured; tagging isn't wired up in the app yet. |

## Required hardware

This app targets specific devices. Used as-is, with no code changes, you need all of the following:

| Item | Model | Why this specific one |
|---|---|---|
| PPG sensor | **Polar Verity Sense** | The only sensor the capture path targets: 176 Hz, 22-bit, 4-channel raw PPG plus 416 Hz ACC/GYRO over the Polar BLE SDK. Other Polar devices (H10, OH1) speak the same PMD protocol and may partly work, but are untested here. |
| BP cuff | **Omron Evolv (HEM-7600T / BP7000)** | Calibration reference. Its BLE protocol is reverse-engineered and model-specific — other Omron models use different EEPROM layouts and record encodings, and will not work unmodified. |
| Phone | **Android 13+** (`minSdk 33`) with BLE | Required by Polar BLE SDK 7.0+. Developed and tested against a OnePlus 9 Pro on Android 14 (API 34). |
| Server | A machine on your network | Sync target. Sizing and specs: [ppg-bp-server](https://github.com/EliasVahlberg/ppg-bp-server#hardware-and-sizing). |

A cuff is not optional for BP estimation. PPG gives you waveform morphology, not pressure — without paired cuff readings there is nothing to calibrate against, and the app will only ever be a raw-signal recorder.

## Installing

Signed release APKs are published on the [Releases page](https://github.com/EliasVahlberg/ppg-bp-android/releases). Android 13 or newer is required (see the hardware table below).

The maintainable route is [Obtainium](https://github.com/ImranR98/Obtainium), which tracks this repository's releases and offers updates on the phone without a developer, a USB cable or an app store account. Add an app source with this repository's URL, pick the APK asset, and enable update notifications. The repository is public, so no token is needed.

Direct install works too: download the APK from a release and open it. Android will ask for permission to install from an unknown source once.

Two things worth knowing before you install on a phone you care about:

- **Releases from v0.2.0 onward are signed with a project key**, so they update in place and keep app data. Anything built before that on a developer machine was signed with a per-machine debug key and cannot be updated over. Android will refuse with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling to resolve it erases staged sessions and the local cuff store. Sync first.
- **The Omron bond is not app data.** It lives in the Android Bluetooth stack, so it survives both updates and uninstalls. The cuff's unlock key lives in the cuff's own EEPROM. Neither needs re-pairing after an app update.

On first launch a setup screen requests Bluetooth permissions, notifications and the battery-optimisation exemption, and blocks recording until they are granted. The exemption matters more than it sounds: without it Android may pause or kill a long recording on an idle phone with no warning and no error, which looks like missing data rather than a failure.

## Building

```bash
cd android
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release builds

A release build needs signing credentials, supplied as Gradle properties (`-PreleaseStoreFile=...`), in a gitignored `local.properties`, or as environment variables (`PPG_BP_STORE_FILE`, `PPG_BP_STORE_PASSWORD`, `PPG_BP_KEY_ALIAS`, `PPG_BP_KEY_PASSWORD`). See `local.properties.example`. Without them the build still succeeds but produces an unsigned APK and says so.

Note that a PKCS12 keystore cannot hold a key password different from the store password. `keytool` accepts `-keypass` and silently keeps the store password, and the mismatch only surfaces much later as `Failed to read key ... Given final block not properly padded` during packaging. Use one password for both.

```bash
./gradlew assembleRelease       # shipping artifact
./gradlew assembleReleaseTest   # same R8 config, with the debug harness compiled in
```

`releaseTest` exists because a shipping release build deliberately cannot be driven over ADB, which makes the minified build hard to verify. Reaching a broadcast receiver with `am broadcast` requires it to be exported, since `adb shell` runs as a different app identity — and an exported receiver with no permission gate is callable by any app on the phone, which for `SET_SERVER` means silently repointing the upload URL and token. Release builds are also not debuggable, so `run-as` is unavailable. `releaseTest` keeps the release minification, keep rules and signing key, and reuses `src/debug/` verbatim so the harness cannot drift from the debug build. Never distribute it.

## License

MIT — see [LICENSE](LICENSE).
