#!/usr/bin/env python3
"""Weaize location viewer for Windows, macOS and Linux.

Fetches the encrypted location history from the Supabase Postgres server and
decrypts it locally with the private key. Locations are read-only: nothing is
ever deleted or modified on the server, so the full history is preserved.

Only parties holding the private key can decrypt the payloads; the server and
API key holders only ever see ciphertext.

Usage:
    python weaize_viewer.py                 # last known location
    python weaize_viewer.py --history 50    # last 50 locations
    python weaize_viewer.py --creds /path/to/creds.txt
    python weaize_viewer.py --device <device-uuid>
    python weaize_viewer.py --live          # interactive: press P to toggle
                                            # live tracking, Q to quit

Requires: pip install cryptography requests
"""

import argparse
import base64
import getpass
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

PBKDF2_ITERATIONS = 100_000
SALT_LEN = 16
IV_LEN = 12


def parse_creds(path: Path) -> dict:
    """Parses the creds.txt file of 'label : value' lines."""
    creds = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = re.sub(r"\s+", " ", key.strip().lower())
        value = value.strip()
        if value:
            creds[key] = value
    return creds


def supabase_base_url(creds: dict) -> str:
    url = creds.get("supabase local address") or creds.get("supabase url")
    if not url:
        proj = creds.get("supabase proj id")
        if proj:
            url = f"https://{proj}.supabase.co"
    if not url:
        sys.exit("creds.txt must contain 'supabase local address', 'supabase url' or 'supabase proj id'")
    return url.rstrip("/")


def decrypt(private_key: str, encoded: str) -> dict:
    raw = base64.b64decode(encoded)
    salt, iv, ct = raw[:SALT_LEN], raw[SALT_LEN:SALT_LEN + IV_LEN], raw[SALT_LEN + IV_LEN:]
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt,
                     iterations=PBKDF2_ITERATIONS)
    key = kdf.derive(private_key.encode("utf-8"))
    plaintext = AESGCM(key).decrypt(iv, ct, None)
    return json.loads(plaintext.decode("utf-8"))


def fetch_locations(base_url: str, api_key: str, limit: int, device: str | None) -> list[dict]:
    params = {
        "select": "device_id,recorded_at,payload",
        "order": "recorded_at.desc",
        "limit": str(limit),
    }
    if device:
        params["device_id"] = f"eq.{device}"
    resp = requests.get(
        f"{base_url}/rest/v1/locations",
        params=params,
        headers={"apikey": api_key, "Authorization": f"Bearer {api_key}"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def format_row(row: dict, private_key: str) -> str:
    try:
        p = decrypt(private_key, row["payload"])
    except Exception:
        return f"{row['recorded_at']}  device={row['device_id']}  <cannot decrypt: wrong private key?>"
    ts = datetime.fromtimestamp(p["timestamp_ms"] / 1000, tz=timezone.utc)
    speed_kmh = p.get("speed_mps", 0) * 3.6
    return (
        f"{ts.isoformat()}  device={row['device_id']}\n"
        f"    lat={p['lat']:.6f} lon={p['lon']:.6f}  "
        f"speed={speed_kmh:.1f} km/h  bearing={p.get('bearing', 0):.0f}°  "
        f"accuracy={p.get('accuracy_m', 0):.0f} m\n"
        f"    https://www.openstreetmap.org/?mlat={p['lat']}&mlon={p['lon']}#map=16/{p['lat']}/{p['lon']}"
    )


class KeyPoller:
    """Non-blocking single-key reader for Windows, macOS and Linux."""

    def __enter__(self):
        if sys.platform == "win32":
            self._win = True
        else:
            self._win = False
            import termios
            import tty

            self._fd = sys.stdin.fileno()
            self._old = termios.tcgetattr(self._fd)
            tty.setcbreak(self._fd)
        return self

    def __exit__(self, *exc):
        if not self._win:
            import termios

            termios.tcsetattr(self._fd, termios.TCSADRAIN, self._old)

    def poll(self) -> str | None:
        if self._win:
            import msvcrt

            if msvcrt.kbhit():
                return msvcrt.getwch()
            return None
        import select

        ready, _, _ = select.select([sys.stdin], [], [], 0)
        if ready:
            return sys.stdin.read(1)
        return None


def show_latest(base_url: str, api_key: str, private_key: str, device: str | None) -> None:
    rows = fetch_locations(base_url, api_key, 1, device)
    if rows:
        print(format_row(rows[0], private_key))
    else:
        print("No locations recorded yet.")
    print()


def live_mode(base_url: str, api_key: str, private_key: str, device: str | None,
              interval: float) -> None:
    print("Live mode: press P to start/stop tracking, Q to quit.\n")
    tracking = False
    last_fetch = 0.0
    with KeyPoller() as keys:
        while True:
            key = keys.poll()
            if key:
                k = key.lower()
                if k == "q":
                    return
                if k == "p":
                    tracking = not tracking
                    print(f"[tracking {'ON' if tracking else 'OFF'}]")
                    last_fetch = 0.0
            if tracking and time.monotonic() - last_fetch >= interval:
                last_fetch = time.monotonic()
                try:
                    show_latest(base_url, api_key, private_key, device)
                except Exception as e:
                    print(f"fetch failed: {e}")
            time.sleep(0.05)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--creds", default="creds.txt", help="path to creds.txt (default: ./creds.txt)")
    parser.add_argument("--history", type=int, default=1,
                        help="number of most recent locations to show (default: 1 = last known)")
    parser.add_argument("--device", help="filter by device UUID")
    parser.add_argument("--live", action="store_true",
                        help="interactive live tracking: press P to toggle, Q to quit")
    parser.add_argument("--interval", type=float, default=10.0,
                        help="refresh interval in seconds for --live (default: 10)")
    args = parser.parse_args()

    creds_path = Path(args.creds)
    if not creds_path.exists():
        sys.exit(f"credentials file not found: {creds_path}")
    creds = parse_creds(creds_path)

    api_key = creds.get("supabase apikey pub") or creds.get("supabase apikey")
    if not api_key:
        sys.exit("creds.txt must contain 'supabase apikey pub'")

    private_key = creds.get("private key") or getpass.getpass("Private key: ")
    if not private_key:
        sys.exit("a private key is required to decrypt locations")

    if args.live:
        live_mode(supabase_base_url(creds), api_key, private_key, args.device, args.interval)
        return

    rows = fetch_locations(supabase_base_url(creds), api_key, args.history, args.device)
    if not rows:
        print("No locations recorded yet.")
        return

    label = "Last known location" if args.history == 1 else f"Last {len(rows)} locations"
    print(f"{label} (server history is append-only; nothing was deleted):\n")
    for row in rows:
        print(format_row(row, private_key))
        print()


if __name__ == "__main__":
    main()
