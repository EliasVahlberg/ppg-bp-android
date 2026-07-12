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
  * `adb logcat` with no bound (no `-d`, no `timeout`) blocks forever and
    will hang a test run or an agent's terminal. Every log read here is
    either a bounded `-d` snapshot or wrapped in `timeout`.
"""

from __future__ import annotations

import re
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "com.polarppgbp"
DEBUG_ACTION_PREFIX = f"{PACKAGE}.debug"


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
        """Poll bounded log snapshots for `pattern`. Never blocks indefinitely."""
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        last_snapshot = ""
        while time.monotonic() < deadline:
            last_snapshot = self.log_snapshot()
            match = regex.search(last_snapshot)
            if match:
                return last_snapshot
            time.sleep(poll_interval)
        raise TimeoutError(
            f"pattern {pattern!r} not seen in logcat within {timeout}s.\n"
            f"Last snapshot tail:\n{last_snapshot[-2000:]}"
        )

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

    def pull(self, remote_path: str, local_path: str | Path) -> None:
        self._adb("pull", remote_path, str(local_path), timeout=60.0)

    def ls(self, remote_path: str) -> list[str]:
        out = self._adb("shell", "ls", "-1", remote_path)
        return [line.strip() for line in out.splitlines() if line.strip()]

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
