"""Thin adb driver for the ppg-bp-android debug command interface (#22).

Wraps exactly the commands used for manual verification of the recorder ->
sync -> server pipeline: connect, capture, stop, sync, status. No mocking —
this drives the real app on a real phone via the real DebugCommandReceiver
broadcasts, so it exercises the actual BLE/WorkManager/HTTP stack.

Two gotchas this module bakes in so nobody has to rediscover them:

  * Every `am broadcast` to DebugCommandReceiver needs
    `--receiver-include-background`, or Android silently drops delivery to
    an app that isn't in the foreground. Without this flag the broadcast
    reports `result=0` (delivered) but the receiver's `onReceive` never
    fires -- a confusing false-positive.
  * `adb logcat` must never be left unbounded -- a bare `adb logcat` blocks
    forever and will hang a test run or an agent's terminal. Reads here are
    either a bounded `-d` snapshot, or a streaming read with a hard deadline
    that always kills the subprocess on exit (see `wait_for_log`).
  * The stock 256 KiB logcat buffer is too small on chatty OEM ROMs to read
    back reliably, so the suite enlarges it and `wait_for_log` streams
    instead of polling snapshots. See `wait_for_log` for the full story.
"""

from __future__ import annotations

import re
import selectors
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "com.polarppgbp"
DEBUG_ACTION_PREFIX = f"{PACKAGE}.debug"

# Enlarged logcat ring buffer, applied once per session by the `device`
# fixture. The stock 256 KiB main buffer is far too small on chatty OEM
# ROMs -- a OnePlus 9 Pro on OxygenOS writes ~63 KiB (~470 lines) every 20 s
# while *idle*, so under recording load the buffer holds only ~20-30 s of
# history and a log line can be evicted seconds after it is written.
LOG_BUFFER_SIZE = "16M"


class AdbError(RuntimeError):
    pass


@dataclass
class Device:
    """A connected Android device, addressed by adb serial."""

    serial: str
    adb_path: str = "adb"

    def _adb(self, *args: str, timeout: float = 30.0) -> str:
        cmd = [self.adb_path, "-s", self.serial, *args]
        try:
            result = subprocess.run(
                cmd, capture_output=True, text=True, timeout=timeout, check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f"adb command timed out after {timeout}s: {' '.join(cmd)}") from exc
        if result.returncode != 0:
            raise AdbError(f"adb command failed ({result.returncode}): {' '.join(cmd)}\n{result.stderr}")
        return result.stdout

    def broadcast(self, action: str, extras: dict[str, str] | None = None) -> str:
        """Send a debug broadcast. Always includes --receiver-include-background."""
        args = ["shell", "am", "broadcast", "-a", f"{DEBUG_ACTION_PREFIX}.{action}",
                "--receiver-include-background"]
        for key, value in (extras or {}).items():
            args += ["--es", key, value]
        return self._adb(*args)

    def clear_log(self) -> None:
        self._adb("logcat", "-c")

    def log_snapshot(self, *, tag_filter: str | None = None) -> str:
        """Bounded (non-blocking) dump of the current logcat buffer."""
        args = ["logcat", "-d"]
        out = self._adb(*args, timeout=10.0)
        if tag_filter:
            pattern = re.compile(tag_filter)
            out = "\n".join(line for line in out.splitlines() if pattern.search(line))
        return out

    def wait_for_log(self, pattern: str, *, timeout: float = 20.0, poll_interval: float = 1.0) -> str:
        """Stream logcat until `pattern` appears, or raise TimeoutError.

        Streams (`adb logcat`, which dumps existing buffer then follows)
        rather than repeatedly dumping snapshots with `logcat -d`.

        The snapshot approach loses lines: this device's stock main buffer is
        256 KiB and OxygenOS writes ~63 KiB every 20 s even while idle, so
        under recording load the buffer spans only ~20-30 s. A line written a
        few seconds before a poll could already be evicted by the time that
        poll's dump ran -- which made test_sync_uploads_to_server fail while
        sync was in fact working perfectly (the SyncWorker success line was
        confirmed present in an independent capture, 29 s before the failing
        snapshot's oldest entry).

        Streaming cannot miss a line that arrives inside the window, because
        lines are read as they are produced rather than after the fact.

        `poll_interval` is accepted for backwards compatibility and unused.
        The subprocess is always killed on exit, so this never blocks
        indefinitely the way a bare `adb logcat` does.
        """
        del poll_interval  # kept for call-site compatibility
        regex = re.compile(pattern)
        cmd = [self.adb_path, "-s", self.serial, "logcat"]
        proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1,
        )
        collected: list[str] = []
        deadline = time.monotonic() + timeout
        try:
            selector = selectors.DefaultSelector()
            selector.register(proc.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                if not selector.select(timeout=min(remaining, 1.0)):
                    continue
                line = proc.stdout.readline()
                if not line:  # stream closed
                    break
                collected.append(line)
                if regex.search(line):
                    return "".join(collected)
        finally:
            proc.kill()
            proc.wait(timeout=5)

        snapshot = "".join(collected)
        raise TimeoutError(
            f"pattern {pattern!r} not seen in logcat within {timeout}s.\n"
            f"Streamed {len(collected)} lines. Tail:\n{snapshot[-2000:]}"
        )

    def set_log_buffer_size(self, size: str = LOG_BUFFER_SIZE) -> None:
        """Enlarge the logcat ring buffer for the rest of the session.

        Not persistent across a device reboot, which is fine -- the fixture
        reapplies it. Guards against the eviction problem described in
        wait_for_log() for any code still reading `-d` snapshots.
        """
        self._adb("logcat", "-G", size)

    def is_app_running(self) -> bool:
        out = self._adb("shell", "pidof", PACKAGE)
        return bool(out.strip())

    def launch_app(self) -> None:
        self._adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")

    def install(self, apk_path: str | Path) -> None:
        self._adb("install", "-r", str(apk_path), timeout=120.0)

    def shell_status(self) -> str:
        """Send STATUS and return the freshest DebugCmd STATUS log line."""
        self.clear_log()
        self.broadcast("STATUS")
        return self.wait_for_log(r"DebugCmd: STATUS", timeout=10.0)

    def is_recording(self) -> bool:
        """True if STATUS reports an active session (state != Idle with no
        session, or session is set at all)."""
        status = self.shell_status()
        match = re.search(r"session=(\S+)", status)
        return bool(match and match.group(1) != "(none)")

    def force_stop_app(self) -> None:
        self._adb("shell", "am", "force-stop", PACKAGE)

    def ensure_stopped(self, *, timeout: float = 20.0) -> None:
        """Force any active recording to stop and wait for confirmation.

        Used both before a test starts (in case a previous run was killed
        mid-recording and never got to clean up -- a plain `finally:` block
        in a test can't protect against the whole process being killed
        from outside, e.g. Ctrl-C or a cancelled tool call) and after,
        as an unconditional teardown.

        Escalates to `am force-stop` + relaunch if the normal STOP_RECORDING
        broadcast doesn't resolve it within `timeout` -- this fixture's
        whole purpose is unattended self-healing between test runs, and a
        stuck BLE/session state is exactly the case where nobody is around
        to intervene by hand.
        """
        if not self.is_recording():
            return
        self.stop_recording()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.is_recording():
                return
            time.sleep(1.0)

        # Normal stop didn't resolve it — escalate.
        self.force_stop_app()
        time.sleep(1.0)
        self.launch_app()
        time.sleep(2.0)
        if self.is_recording():
            raise TimeoutError(
                "session still active after STOP_RECORDING and a force-stop + "
                "relaunch — this needs manual investigation (check the Polar "
                "sensor itself isn't stuck in a bad BLE state; power-cycling "
                "it can help)."
            )

    def start_recording(self, profile: str = "calibration") -> None:
        self.broadcast("START_RECORDING", {"profile": profile})

    def start_recording_from_settings(self) -> None:
        """START_RECORDING with no profile override — exercises the same
        path a real tap of the in-app Start button takes (resolves via
        SettingsStore), rather than the deterministic calibration/monitor
        override used by start_recording()."""
        self.broadcast("START_RECORDING")

    def stop_recording(self) -> None:
        self.broadcast("STOP_RECORDING")

    def get_settings(self) -> str:
        """Send GET_SETTINGS and return the freshest DebugCmd log line."""
        self.clear_log()
        self.broadcast("GET_SETTINGS")
        return self.wait_for_log(r"DebugCmd: GET_SETTINGS", timeout=10.0)

    def set_profile(self, profile: str) -> None:
        """profile: 'calibration' | 'monitor' | 'custom'."""
        self.broadcast("SET_PROFILE", {"profile": profile})

    def set_rate(self, sensor: str, hz: int) -> None:
        """sensor: 'PPG' | 'ACC' | 'GYRO'."""
        self.broadcast("SET_RATE", {"sensor": sensor, "hz": str(hz)})

    def reset_settings(self) -> None:
        self.broadcast("RESET_SETTINGS")

    def sync_now(self) -> None:
        self.broadcast("SYNC_NOW")

    def set_server(self, url: str, token: str) -> None:
        self.broadcast("SET_SERVER", {"url": url, "token": token})

    def check_server(self, *, timeout: float = 25.0) -> str:
        """Run the in-app staged server health check (#16) and return its report line.

        Uses the debug broadcast rather than driving the settings UI: the check itself
        is the same code path the "Test connection" button calls.
        """
        self.clear_log()
        self.broadcast("CHECK_SERVER")
        return self.wait_for_log(r"DebugCmd: CHECK_SERVER stage=\w+", timeout=timeout)

    def set_bluetooth(self, enabled: bool) -> None:
        """Toggle the Bluetooth adapter.

        Uses ``cmd bluetooth_manager`` rather than ``settings put global bluetooth_on``:
        observed 2026-07-26 on OxygenOS that the global setting still read 1 while the
        adapter was genuinely off, so writing it proves nothing.
        """
        self._adb("shell", "cmd", "bluetooth_manager", "enable" if enabled else "disable")

    def wait_for_bluetooth(self, enabled: bool, *, timeout: float = 25.0) -> None:
        """Block until the adapter reaches the requested state.

        ``dumpsys`` is the source of truth here. Note the OEM reports ``BLE_ON`` when
        classic Bluetooth is off but BLE-scanning-always-available is on, which must not
        be read as ON.
        """
        want = "state: ON" if enabled else None
        deadline = time.time() + timeout
        while time.time() < deadline:
            out = self._adb("shell", "dumpsys", "bluetooth_manager")
            on = "state: ON" in out
            if on == enabled:
                return
            time.sleep(1.0)
        raise AdbError(f"Bluetooth did not reach enabled={enabled} within {timeout}s (wanted {want})")

    def pull(self, remote_path: str, local_path: str | Path) -> None:
        self._adb("pull", remote_path, str(local_path), timeout=60.0)

    def ls(self, remote_path: str) -> list[str]:
        """List a directory, including dotfiles.

        Uses `ls -1a`, not `ls -1`: the sync marker is `.synced` and plain
        `ls -1` hides dotfiles, so a marker that was correctly written looked
        absent. `.` and `..` are filtered out so callers see only real entries.
        """
        out = self._adb("shell", "ls", "-1a", remote_path)
        return [
            line.strip() for line in out.splitlines()
            if line.strip() and line.strip() not in (".", "..")
        ]

    def cat(self, remote_path: str) -> str:
        return self._adb("shell", "cat", remote_path)


def list_devices(adb_path: str = "adb") -> list[str]:
    """Serials of devices in `device` state (excludes offline/unauthorized)."""
    result = subprocess.run([adb_path, "devices"], capture_output=True, text=True, timeout=15.0)
    serials = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials
