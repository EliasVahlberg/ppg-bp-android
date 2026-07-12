"""E2e tests for per-sensor sample rate settings (#1).

Exercises the debug SET_PROFILE/SET_RATE/RESET_SETTINGS/GET_SETTINGS
broadcasts, which call the exact same SettingsStore functions the Settings
screen's UI actions call (see DebugCommandReceiver.kt) -- so these tests are
a scripted equivalent of using the Settings screen, not a separate path.

Covers the three things explicitly asked for:
  - configuring per-sensor sample rate without ADB-only access (here,
    scripted via ADB for repeatability, but exercising the same underlying
    persistence + resolution logic the UI drives)
  - settings surviving an app restart
  - RESET_SETTINGS (the "reset to defaults" debug shortcut, mirroring the
    in-app "Reset all settings" button)
"""

from __future__ import annotations

import re
import time

import pytest

from e2e.adb import Device

CALIBRATION_RATES = {"PPG": 176, "ACC": 416, "GYRO": 416}


def _parse_get_settings(log: str) -> dict:
    match = re.search(
        r"GET_SETTINGS profile=(\S+) ppg=(\d+) acc=(\d+) gyro=(\d+) resolved=\{(.+?)\}",
        log,
    )
    assert match, f"couldn't parse GET_SETTINGS output:\n{log}"
    profile, ppg, acc, gyro, resolved = match.groups()
    return {
        "profile": profile,
        "custom_ppg": int(ppg),
        "custom_acc": int(acc),
        "custom_gyro": int(gyro),
        "resolved": resolved,
    }


@pytest.fixture(autouse=True)
def _reset_settings_after_test(device: Device):
    """Settings persist in the app's SharedPreferences, independent of the
    recording-state guard in conftest.py -- reset explicitly so one test's
    custom rates can't leak into the next."""
    yield
    device.reset_settings()


@pytest.mark.e2e
def test_reset_settings_restores_calibration_defaults(device: Device):
    device.set_profile("custom")
    device.set_rate("PPG", 135)
    log = device.get_settings()
    assert _parse_get_settings(log)["profile"] == "custom"

    device.reset_settings()
    log = device.get_settings()
    parsed = _parse_get_settings(log)
    assert parsed["profile"] == "calibration"
    assert parsed["custom_ppg"] == CALIBRATION_RATES["PPG"]
    assert parsed["custom_acc"] == CALIBRATION_RATES["ACC"]
    assert parsed["custom_gyro"] == CALIBRATION_RATES["GYRO"]


@pytest.mark.e2e
def test_custom_rate_selection_persists_and_resolves(device: Device):
    device.set_profile("custom")
    device.set_rate("PPG", 135)
    device.set_rate("ACC", 208)
    device.set_rate("GYRO", 104)

    log = device.get_settings()
    parsed = _parse_get_settings(log)
    assert parsed["profile"] == "custom"
    assert parsed["custom_ppg"] == 135
    assert parsed["custom_acc"] == 208
    assert parsed["custom_gyro"] == 104
    assert "PPG=135" in parsed["resolved"]
    assert "ACC=208" in parsed["resolved"]
    assert "GYRO=104" in parsed["resolved"]


@pytest.mark.e2e
def test_settings_survive_app_restart(device: Device):
    device.set_profile("custom")
    device.set_rate("PPG", 55)

    device.force_stop_app()
    time.sleep(1)
    device.launch_app()
    time.sleep(2)

    log = device.get_settings()
    parsed = _parse_get_settings(log)
    assert parsed["profile"] == "custom"
    assert parsed["custom_ppg"] == 55


@pytest.mark.e2e
def test_recording_uses_persisted_custom_rate(device: Device):
    """The real regression this session caught: START_RECORDING with no
    profile override must resolve via SettingsStore (the same path the
    in-app Start button takes), not silently fall back to calibration."""
    device.set_profile("custom")
    device.set_rate("PPG", 135)
    device.set_rate("ACC", 208)
    device.set_rate("GYRO", 104)

    device.clear_log()
    device.start_recording_from_settings()
    try:
        log = device.wait_for_log(r"RecordingService: Auto-connecting.*profile=custom", timeout=15.0)
        assert "profile=custom" in log

        device.wait_for_log(r"PolarRepo: PPG streaming @ 135Hz", timeout=30.0)
        device.wait_for_log(r"PolarRepo: ACC streaming @ 208Hz", timeout=15.0)
        device.wait_for_log(r"PolarRepo: GYRO streaming @ 104Hz", timeout=15.0)
    finally:
        device.stop_recording()


@pytest.mark.e2e
def test_unsupported_rate_fails_loudly_not_silently(device: Device):
    """Acceptance criteria from #1: an unsupported rate must be caught, not
    silently downgraded. 300Hz PPG isn't one of the Polar Verity Sense's
    online-streaming rates (28/44/55/135/176), so this must produce a clear
    error and stop the session rather than quietly using e.g. 176Hz."""
    device.set_profile("custom")
    device.set_rate("PPG", 300)

    device.clear_log()
    device.start_recording_from_settings()
    try:
        log = device.wait_for_log(r"PolarRepo: PPG: unsupported rate requested", timeout=30.0)
        assert "300" in log
    finally:
        device.stop_recording()
