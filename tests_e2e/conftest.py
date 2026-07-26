"""Fixtures for the device-in-the-loop e2e suite.

All tests here are marked `e2e` (see pyproject.toml) and are skipped by
default unless a device is actually connected -- run with:

    uv run pytest -m e2e

See tests_e2e/README.md for one-time phone setup.
"""

from __future__ import annotations

import pytest

from e2e.adb import Device, list_devices
from e2e.config import E2EConfig

PACKAGE = "com.polarppgbp"


def pytest_collection_modifyitems(config, items):
    for item in items:
        if "tests_e2e" in str(item.fspath):
            item.add_marker(pytest.mark.e2e)


@pytest.fixture(scope="session")
def e2e_config() -> E2EConfig:
    return E2EConfig.from_env()


@pytest.fixture(scope="session")
def device(e2e_config: E2EConfig) -> Device:
    serial = e2e_config.adb_serial
    if not serial:
        connected = list_devices(e2e_config.adb_path)
        if len(connected) == 0:
            pytest.skip("no adb device connected — plug in / pair a phone first (see README.md)")
        if len(connected) > 1:
            pytest.skip(
                f"multiple adb devices connected ({connected}) — "
                "set E2E_ADB_SERIAL in .env to pick one"
            )
        serial = connected[0]

    dev = Device(serial=serial, adb_path=e2e_config.adb_path)

    # Enlarge the logcat ring buffer before anything reads logs. The stock
    # 256 KiB main buffer holds only ~20-30 s of history on a chatty OEM ROM
    # under recording load, which is short enough to lose a line between it
    # being written and a test looking for it.
    dev.set_log_buffer_size()

    if e2e_config.apk_path:
        dev.install(e2e_config.apk_path)

    if not dev.is_app_running():
        dev.launch_app()

    if e2e_config.server_url and e2e_config.server_token:
        dev.set_server(e2e_config.server_url, e2e_config.server_token)

    return dev


@pytest.fixture(autouse=True)
def _stop_recording_around_test(device: Device):
    """Unconditional recording-state guard, before and after every test.

    Before: if a previous test run was killed from outside (Ctrl-C,
    cancelled tool call, crashed pytest process) mid-recording, a plain
    `finally:` in that test never got a chance to run -- the device can be
    left with an open session and a half-broken BLE link. This self-heals
    that on the next run instead of every subsequent test failing with a
    confusing "PPG never started" error.

    After: same guard in reverse, so a failing assertion mid-test (which
    *does* still run the test's own `finally`, but better safe) can't
    leave a session running into the next test either.
    """
    device.ensure_stopped()
    yield
    device.ensure_stopped()


@pytest.fixture
def clean_log(device: Device) -> Device:
    """Clears logcat before the test so log assertions aren't polluted by
    earlier fixture setup (app launch, SET_SERVER, etc.)."""
    device.clear_log()
    return device
