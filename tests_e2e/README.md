# ppg-bp-android e2e tests

Device-in-the-loop end-to-end tests, driven over `adb` against the real app
on a real phone with a real Polar sensor nearby. No mocking, no emulator —
this scripts exactly the manual verification pass used to confirm the app
still works after the repo split (BLE connect, capture, ROP bundle on disk,
sync to a real server).

This is a deliberately light layer, not a full instrumented-test suite
(there's no `androidTest/` Espresso/UI Automator setup here — see the
"what this is not" section below). It exists because building that properly
would take significant upfront work, while this gets real device-in-the-loop
coverage of the highest-risk paths (BLE, WorkManager sync, on-disk bundle
correctness) for a small amount of code.

## One-time setup

### 1. Prerequisites on your machine

- `adb` available, either on `PATH` or via `$ANDROID_HOME/platform-tools/adb`
- Python 3.11+ and [`uv`](https://docs.astral.sh/uv/)
- A Polar Verity Sense (or Polar Sense) that has been paired with the phone
  at least once before (via the app's own pairing flow, or `SET_SERVER` +
  `START_RECORDING` with an explicit `device_id` once — see
  `app/src/debug/kotlin/com/polarppgbp/debug/DebugCommandReceiver.kt`)

### 2. Prepare your phone

1. Enable Developer options (Settings > About phone > tap Build number
   7 times), then enable **USB debugging** or **Wireless debugging**.
2. For wireless debugging (no cable needed day-to-day): Settings >
   Developer options > Wireless debugging > **Pair device with pairing
   code**, then on your machine:
   ```
   adb pair <ip>:<port>   # enter the 6-digit code when prompted
   adb connect <ip>:<port>
   ```
   This pairing is one-time per machine; you'll need to `adb connect`
   again each time the phone reconnects to the network (its debugging
   port changes), but not re-pair.
3. Install the debug build at least once, either via
   `../scripts/dev_deploy.sh` from the `ppg-bp-android` repo root, or let
   this suite install it for you (see `E2E_APK_PATH` below).
4. Make sure your Polar sensor is powered on and in range before running
   `test_connect` / `test_capture_five_sec_recording`.

### 3. Configure the suite

```
cd tests_e2e
cp .env.example .env
```

Edit `.env`:

- `E2E_ADB_SERIAL` — leave blank if you only ever have one device
  connected; the suite will auto-select it and fail loudly if it finds
  zero or more than one.
- `E2E_APK_PATH` — leave blank if you'll build/install yourself (e.g. via
  `scripts/dev_deploy.sh`); set it to `../app/build/outputs/apk/debug/app-debug.apk`
  if you want the suite to install it for you each run.
- `E2E_POLAR_DEVICE_ID` — optional, only used to assert you connected to
  *your* sensor rather than someone else's Polar that happened to be in
  range. Find it from the app UI or a `STATUS` broadcast.
- `E2E_SERVER_URL` / `E2E_SERVER_TOKEN` — your `ppg-pi-server` instance.
  Leave blank to skip the sync test (it will be skipped, not failed).

`.env` is gitignored — it will contain your device id and a real server
bearer token, treat it like a secret.

## Running

```
cd tests_e2e
uv run pytest -m e2e -v
```

Tests are automatically skipped (not failed) if no device is connected, so
this is safe to leave in a normal test run / CI without a device attached.

## What each test does

| Test | What it verifies |
|---|---|
| `test_status_reachable` | The debug broadcast interface is alive — a sanity check every other test depends on |
| `test_connect` | The app reaches BLE `Connected` state against a real Polar sensor |
| `test_capture_five_sec_recording` | A 5s recording produces nonzero PPG/ACC/GYRO sample counts and a correctly-shaped ROP bundle on disk (`manifest.json`, `segments.jsonl`, `*_000.rop` per sensor) |
| `test_sync_uploads_to_server` | A finished bundle actually syncs to a real `ppg-pi-server`, the server reports `status: complete`, sample counts match, and the `.synced` marker is written |

## What this is not

This is not a substitute for:

- **Unit tests** (`app/src/test/`) — fast, JVM-only, no device needed, run
  on every build. Keep adding these for anything that doesn't need real
  BLE/Android OS behavior.
- **Instrumented tests** (a proper `app/src/androidTest/` suite with
  Espresso/UI Automator, or a mocked `PolarBleApi` for driving
  `PolarRepository` without real hardware) — a bigger investment that
  would let CI run device-behavior tests without a physical phone/sensor
  attached. Tracked separately; this e2e suite is the pragmatic
  in-between until that lands.

Known gap this suite intentionally does not cover: the actual Android OS
broadcast-delivery restriction (`am broadcast` needs
`--receiver-include-background` or delivery to a backgrounded app silently
no-ops) is a real behavior worth its own regression test once an
instrumented suite exists, since it's Android-OS-version-dependent and a
JVM-only test can't see it at all.
