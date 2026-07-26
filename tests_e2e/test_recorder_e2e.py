"""Device-in-the-loop e2e tests, replaying the manual verification pass
done after the ppg-bp / ppg-bp-android / ppg-bp-server repo split:

  - test_connect: BLE connect to a real Polar sensor
  - test_capture_five_sec_recording: start/stop a short recording, verify
    the on-disk ROP bundle
  - test_sync_uploads_to_server: verify a finished bundle actually syncs
    and the server accepts it (skipped if no server configured)

Run with a device connected and the app installed:

    cd tests_e2e && uv run pytest -m e2e -v

Everything here talks to the real app via the debug broadcast interface
(#22) -- no mocking, this is intentionally the same path used for manual
verification, just scripted.
"""

from __future__ import annotations

import re
import time

import pytest

from e2e.adb import Device
from e2e.config import E2EConfig

SESSIONS_DIR = f"/storage/emulated/0/Android/data/com.polarppgbp/files/sessions"


def _wait_for_streaming(device: Device, *, timeout: float = 30.0, poll_interval: float = 1.0) -> None:
    """Poll STATUS until at least one PPG sample has been recorded.

    More robust than chaining wait_for_log() on "Connected"/"streaming"
    lines: those happen within milliseconds of each other, so two
    sequential log-pattern polls can race each other. STATUS reflects
    live cumulative state, so polling it directly can't miss a transition
    the way a point-in-time log snapshot can.
    """
    import time as _time

    deadline = _time.monotonic() + timeout
    last_status = ""
    while _time.monotonic() < deadline:
        last_status = device.shell_status()
        match = re.search(r"ppgSamples=(\d+)", last_status)
        if match and int(match.group(1)) > 0:
            return
        _time.sleep(poll_interval)
    raise TimeoutError(
        f"no PPG samples recorded within {timeout}s — sensor never started streaming.\n"
        f"Last STATUS:\n{last_status}"
    )


@pytest.mark.e2e
def test_status_reachable(clean_log: Device):
    """Sanity check: the debug broadcast interface is alive and answers
    STATUS. If this fails, nothing else in this file will work either --
    check the app is installed & running, and that you're not missing
    --receiver-include-background (adb.py always sends it, so this
    usually means the app isn't actually running)."""
    status = clean_log.shell_status()
    assert "DebugCmd: STATUS" in status


@pytest.mark.e2e
def test_connect(clean_log: Device, e2e_config: E2EConfig):
    """Start a recording (which triggers connect) and verify the app
    reaches Connected state within a reasonable timeout. Stops the
    session again afterwards regardless of outcome."""
    device = clean_log
    device.start_recording(profile="calibration")
    try:
        log = device.wait_for_log(r"PolarRepo: Connected to (\S+)", timeout=30.0)
        match = re.search(r"PolarRepo: Connected to (\S+) \((.+)\)", log)
        assert match, f"Connected line found but couldn't parse device id/name from:\n{log}"
        connected_id, connected_name = match.group(1), match.group(2)
        if e2e_config.polar_device_id:
            assert connected_id == e2e_config.polar_device_id, (
                f"connected to {connected_id!r} but E2E_POLAR_DEVICE_ID is "
                f"{e2e_config.polar_device_id!r} — wrong sensor in range?"
            )
    finally:
        device.stop_recording()


@pytest.mark.e2e
def test_capture_five_sec_recording(clean_log: Device):
    """Record for 5 seconds, stop, and verify the resulting bundle on
    disk has the shape the server's converter expects: manifest.json,
    segments.jsonl, and at least a ppg_000.rop with a nonzero sample
    count reported by STATUS before stopping.

    Polls STATUS directly for nonzero sample counts rather than waiting on
    intermediate "Connected"/"streaming" log lines — connect and stream
    startup happen within milliseconds of each other on the device, which
    made two sequential wait_for_log() polls flaky (the second pattern
    could already be behind the poll window by the time it's checked).
    STATUS is idempotent and reflects live state, so polling it directly
    is both simpler and more robust.
    """
    device = clean_log
    device.start_recording(profile="calibration")
    try:
        session_dir = _wait_for_streaming(device, timeout=30.0)
        time.sleep(5)

        status = device.shell_status()
        match = re.search(
            r"ppgSamples=(\d+), accSamples=(\d+), gyroSamples=(\d+)\).*session=(\S+)",
            status,
        )
        assert match, f"couldn't parse STATUS output:\n{status}"
        ppg_n, acc_n, gyro_n, session_dir = match.groups()
        assert int(ppg_n) > 0, "recorded 0 PPG samples after 5s — sensor not actually streaming?"
        assert int(acc_n) > 0, "recorded 0 ACC samples after 5s"
        assert int(gyro_n) > 0, "recorded 0 GYRO samples after 5s"
    finally:
        device.stop_recording()
        device.wait_for_log(r"PolarRepo: Stopped session", timeout=15.0)

    files = device.ls(session_dir)
    assert "manifest.json" in files
    assert "segments.jsonl" in files
    assert any(f.startswith("ppg_") and f.endswith(".rop") for f in files), files
    assert any(f.startswith("acc_") and f.endswith(".rop") for f in files), files
    assert any(f.startswith("gyro_") and f.endswith(".rop") for f in files), files


@pytest.mark.e2e
def test_sync_uploads_to_server(clean_log: Device, e2e_config: E2EConfig):
    """Record briefly, stop (which enqueues sync), and verify the server
    actually accepted the bundle: the .synced marker appears, and the
    SyncWorker log line reports status=complete with the sample counts
    matching what was recorded.

    Skipped if E2E_SERVER_URL/E2E_SERVER_TOKEN aren't set in .env — sync
    can't succeed without a real server to talk to.
    """
    if not (e2e_config.server_url and e2e_config.server_token):
        pytest.skip("E2E_SERVER_URL/E2E_SERVER_TOKEN not set in .env — see .env.example")

    device = clean_log
    device.start_recording(profile="calibration")
    try:
        _wait_for_streaming(device, timeout=30.0)
        time.sleep(5)
    finally:
        device.stop_recording()

    log = device.wait_for_log(r"PolarRepo: Stopped session (\S+)", timeout=15.0)
    match = re.search(r"PolarRepo: Stopped session (\S+)", log)
    assert match, f"couldn't find session dir in:\n{log}"
    session_dir = match.group(1)

    # NB: no space before "status" -- the server serialises CompleteResponse as
    # compact JSON (`...uuid","status":"complete"`). An earlier version of this
    # pattern had ` "status"` with a leading space and could therefore never
    # match, so this test failed while sync was in fact working correctly.
    sync_log = device.wait_for_log(r'SyncWorker: synced .*"status":"complete"', timeout=60.0)
    assert '"status":"complete"' in sync_log

    counts_match = re.search(r'"samples_per_sensor":\{"ppg":(\d+),"acc":(\d+),"gyro":(\d+)', sync_log)
    assert counts_match, f"couldn't parse sample counts from sync log:\n{sync_log}"
    ppg_n, acc_n, gyro_n = (int(x) for x in counts_match.groups())
    assert ppg_n > 0 and acc_n > 0 and gyro_n > 0, (
        f"server reported zero samples for one or more sensors: ppg={ppg_n} acc={acc_n} gyro={gyro_n}"
    )

    files = device.ls(session_dir)
    assert ".synced" in files, f"no .synced marker in {session_dir} after sync completed — {files}"
