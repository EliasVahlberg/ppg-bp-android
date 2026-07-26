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



def _settle_bluetooth_stack() -> None:
    """Give the BLE stack a moment after the adapter has been power-cycled.

    Twice on 2026-07-26, a capture test run shortly after repeated adapter toggling
    reported a stream as started ("ACC streaming @ 416Hz") while delivering zero
    samples, and recovered only after the app process was restarted. Root cause was
    not established, so this is a deliberately conservative pause rather than a fix:
    tests that toggle the radio should not leave the stack hot for whatever runs next.
    """
    time.sleep(5)

@pytest.mark.e2e
def test_bluetooth_off_is_reported_and_not_retried_forever(clean_log: Device):
    """#17: Bluetooth off must fail loudly and stop, not retry silently forever.

    Reproduces the failure observed 2026-07-26 while bringing up this suite: with the
    radio off, starting a recording produced no user-visible error and an unbounded
    reconnect loop, which the UI rendered as "Lost connection - reconnecting" -- a
    recovery that could never happen.

    The radio is restored in a finally block. If this test is interrupted between the
    disable and the restore, re-enable Bluetooth on the device manually.
    """
    device = clean_log
    device.set_bluetooth(False)
    try:
        device.wait_for_bluetooth(False)
        device.clear_log()
        device.start_recording()

        log = device.wait_for_log(r"RecordingService: Not starting: Bluetooth is off", timeout=20.0)
        assert "Turn Bluetooth on to record" in log, (
            f"the log must state the remedy, not just the cause:\n{log}"
        )

        # Hold and observe: a bounded number of attempts, not an escalating storm. The
        # pre-fix behaviour produced a new attempt every 1/2/4/8/16s indefinitely.
        # Refusing at START leaves no loop running at all, but a loop already in flight
        # (radio switched off mid-session) holds instead of retrying, so allow either and
        # require the reason to be logged whenever attempts did occur.
        time.sleep(20)
        snapshot = device.log_snapshot(tag_filter="RecordingService")
        attempts = snapshot.count("Reconnecting to")
        assert attempts <= 2, (
            f"expected the reconnect loop to hold while blocked, saw {attempts} attempts:\n{snapshot}"
        )
        if attempts:
            assert "Not reconnecting: Bluetooth is off" in snapshot, (
                f"a held loop must say why it is not retrying:\n{snapshot}"
            )
        assert not device.is_recording(), "no session should be running with the radio off"
    finally:
        device.set_bluetooth(True)
        device.wait_for_bluetooth(True)
        _settle_bluetooth_stack()


@pytest.mark.e2e
def test_recording_survives_and_resumes_after_bluetooth_outage(clean_log: Device):
    """#17: a mid-session radio outage must hold, then resume into the same session.

    This is the data-preservation half of the issue: the session bundle must not be
    abandoned when the radio drops, and the user must not have to notice and restart.
    """
    device = clean_log
    device.start_recording()
    try:
        started = device.wait_for_log(r"PolarRepo: Started session (\S+)", timeout=30.0)
        session_dir = re.search(r"PolarRepo: Started session (\S+)", started).group(1)
        device.wait_for_log(r"PolarRepo: Connected to ", timeout=45.0)
        time.sleep(5)

        device.set_bluetooth(False)
        device.wait_for_bluetooth(False)
        device.wait_for_log(r"RecordingService: Not reconnecting: Bluetooth is off", timeout=30.0)

        device.set_bluetooth(True)
        device.wait_for_bluetooth(True)
        device.wait_for_log(r"PolarRepo: Connected to ", timeout=60.0)
        time.sleep(8)

        status = device.shell_status()
        assert session_dir in status, (
            f"recording should have resumed into the same session {session_dir}, got:\n{status}"
        )
    finally:
        device.set_bluetooth(True)
        device.wait_for_bluetooth(True)
        device.stop_recording()
        _settle_bluetooth_stack()


@pytest.mark.e2e
def test_server_health_check_names_the_failing_stage(clean_log: Device, e2e_config: E2EConfig):
    """#16: a wrong address and a wrong token must not look the same to the user.

    Skipped if E2E_SERVER_URL/E2E_SERVER_TOKEN aren't set, and the AUTHENTICATED case
    additionally needs the server actually running.

    Restores the configured server in a finally block, since it rewrites app prefs.
    """
    device = clean_log
    if not e2e_config.server_url or not e2e_config.server_token:
        pytest.skip("E2E_SERVER_URL/E2E_SERVER_TOKEN not set in .env — see .env.example")

    try:
        # Wrong port: nothing listening, so this must read as an address problem.
        device.set_server("http://192.0.2.1:9", e2e_config.server_token)
        time.sleep(1)
        log = device.check_server()
        assert "stage=UNREACHABLE" in log, f"expected UNREACHABLE, got:\n{log}"

        # Right address, wrong token: must be reported as a token problem, and must say
        # the address is fine so the user does not go re-typing the host.
        device.set_server(e2e_config.server_url, "0" * 64)
        time.sleep(1)
        log = device.check_server()
        assert "stage=REACHABLE" in log, f"expected REACHABLE (token rejected), got:\n{log}"
        detail = device.log_snapshot(tag_filter="DebugCmd")
        assert "address is right" in detail, f"should absolve the address:\n{detail}"

        # Correct config: fully healthy.
        device.set_server(e2e_config.server_url, e2e_config.server_token)
        time.sleep(1)
        log = device.check_server()
        assert "stage=AUTHENTICATED" in log and "ok=true" in log, (
            f"expected AUTHENTICATED with the real config, got:\n{log}"
        )
    finally:
        device.set_server(e2e_config.server_url, e2e_config.server_token)
