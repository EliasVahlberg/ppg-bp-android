"""Config for the e2e suite, loaded from tests_e2e/.env (gitignored).

Copy .env.example to .env and fill in your own device/server details. Kept
as a tiny hand-rolled loader (not python-dotenv) to avoid adding a
dependency for four key=value lines.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ENV_PATH = Path(__file__).parent.parent / ".env"


def _load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


_load_dotenv(ENV_PATH)


@dataclass(frozen=True)
class E2EConfig:
    adb_serial: str | None  # None = use whichever single device is connected
    adb_path: str
    apk_path: str | None  # path to a prebuilt app-debug.apk; None = assume already installed
    polar_device_id: str | None  # e.g. "ABCD1234", for assertions only (never required to connect)
    server_url: str | None
    server_token: str | None

    @classmethod
    def from_env(cls) -> "E2EConfig":
        return cls(
            adb_serial=os.environ.get("E2E_ADB_SERIAL") or None,
            adb_path=os.environ.get("E2E_ADB_PATH", "adb"),
            apk_path=os.environ.get("E2E_APK_PATH") or None,
            polar_device_id=os.environ.get("E2E_POLAR_DEVICE_ID") or None,
            server_url=os.environ.get("E2E_SERVER_URL") or None,
            server_token=os.environ.get("E2E_SERVER_TOKEN") or None,
        )
