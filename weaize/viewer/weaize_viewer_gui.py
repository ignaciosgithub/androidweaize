#!/usr/bin/env python3
"""Weaize location viewer GUI for Windows, macOS and Linux.

Tkinter front-end over the same encrypted Supabase history used by
weaize_viewer.py: shows the last known location, recent history, live
tracking (auto-refresh), and opens positions in OpenStreetMap. Read-only —
nothing is ever deleted from the server. Only holders of the private key can
decrypt the locations.

Usage:
    python weaize_viewer_gui.py [--creds /path/to/creds.txt]

Requires: pip install cryptography requests   (tkinter ships with Python)
"""

import argparse
import threading
import webbrowser
from datetime import datetime, timezone
from pathlib import Path
from tkinter import (
    BOTH,
    END,
    E,
    N,
    S,
    W,
    BooleanVar,
    StringVar,
    Tk,
    filedialog,
    messagebox,
    simpledialog,
    ttk,
)

from weaize_viewer import decrypt, fetch_locations_any, parse_creds, supabase_services


class ViewerApp:
    def __init__(self, root: Tk, creds_path: Path | None):
        self.root = root
        root.title("Weaize Viewer")
        root.geometry("760x480")

        self.services: list[tuple[str, str]] = []
        self.private_key = None
        self.rows: list[dict] = []
        self.live = BooleanVar(value=False)
        self.status = StringVar(value="Load a creds.txt to begin")
        self.interval_s = 10

        top = ttk.Frame(root, padding=8)
        top.grid(row=0, column=0, sticky=(W, E))
        ttk.Button(top, text="Load creds.txt", command=self.load_creds).grid(row=0, column=0)
        ttk.Button(top, text="Refresh", command=self.refresh).grid(row=0, column=1, padx=4)
        ttk.Checkbutton(top, text="Live tracking", variable=self.live,
                        command=self.on_live_toggle).grid(row=0, column=2, padx=4)
        ttk.Label(top, text="History:").grid(row=0, column=3, padx=(12, 2))
        self.history_count = ttk.Spinbox(top, from_=1, to=500, width=5)
        self.history_count.set(20)
        self.history_count.grid(row=0, column=4)
        ttk.Button(top, text="Open in map", command=self.open_selected).grid(row=0, column=5, padx=12)

        columns = ("time", "lat", "lon", "speed", "bearing", "accuracy", "device")
        self.tree = ttk.Treeview(root, columns=columns, show="headings")
        widths = {"time": 170, "lat": 90, "lon": 90, "speed": 80, "bearing": 60,
                  "accuracy": 70, "device": 160}
        for col in columns:
            self.tree.heading(col, text=col.capitalize())
            self.tree.column(col, width=widths[col], anchor="center")
        self.tree.grid(row=1, column=0, sticky=(N, S, E, W), padx=8)
        self.tree.bind("<Double-1>", lambda e: self.open_selected())

        ttk.Label(root, textvariable=self.status, padding=6).grid(row=2, column=0, sticky=W)

        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)

        if creds_path and creds_path.exists():
            self.apply_creds(creds_path)

    def load_creds(self):
        path = filedialog.askopenfilename(
            title="Select creds.txt",
            filetypes=[("Text files", "*.txt"), ("All files", "*")])
        if path:
            self.apply_creds(Path(path))

    def apply_creds(self, path: Path):
        try:
            creds = parse_creds(path)
            self.services = supabase_services(creds)
            self.private_key = creds.get("private key")
        except SystemExit as e:
            messagebox.showerror("Weaize Viewer", str(e))
            return
        if not self.services:
            messagebox.showerror("Weaize Viewer", "creds.txt must contain 'supabase apikey pub'")
            return
        if not self.private_key:
            self.private_key = simpledialog.askstring(
                "Private key", "Enter your private key:", show="*")
        if not self.private_key:
            messagebox.showerror("Weaize Viewer", "A private key is required to decrypt locations")
            return
        backup = " + backup" if len(self.services) > 1 else ""
        self.status.set(f"Loaded credentials ({self.services[0][0]}{backup})")
        self.refresh()

    def refresh(self):
        if not self.services:
            self.status.set("Load a creds.txt first")
            return
        limit = int(self.history_count.get() or 20)
        self.status.set("Fetching…")
        threading.Thread(target=self._fetch, args=(limit,), daemon=True).start()

    def _fetch(self, limit: int):
        try:
            rows = fetch_locations_any(self.services, limit, None)
        except Exception as e:
            msg = f"Fetch failed: {e}"
            self.root.after(0, lambda: self.status.set(msg))
            return
        self.root.after(0, lambda: self._show(rows))

    def _show(self, rows: list[dict]):
        self.tree.delete(*self.tree.get_children())
        self.rows = []
        for row in rows:
            try:
                p = decrypt(self.private_key, row["payload"])
            except Exception:
                self.tree.insert("", END, values=(row["recorded_at"], "?", "?", "?", "?", "?",
                                                  row["device_id"][:13] + "…"))
                self.rows.append({})
                continue
            ts = datetime.fromtimestamp(p["timestamp_ms"] / 1000, tz=timezone.utc)
            self.tree.insert("", END, values=(
                ts.strftime("%Y-%m-%d %H:%M:%S UTC"),
                f"{p['lat']:.6f}",
                f"{p['lon']:.6f}",
                f"{p.get('speed_mps', 0) * 3.6:.1f} km/h",
                f"{p.get('bearing', 0):.0f}°",
                f"{p.get('accuracy_m', 0):.0f} m",
                row["device_id"][:13] + "…",
            ))
            self.rows.append(p)
        n = len(rows)
        when = datetime.now().strftime("%H:%M:%S")
        self.status.set(f"{n} location(s) — updated {when}"
                        + ("  [live]" if self.live.get() else ""))

    def open_selected(self):
        selection = self.tree.selection()
        index = self.tree.index(selection[0]) if selection else 0
        if not self.rows or index >= len(self.rows) or not self.rows[index]:
            self.status.set("Nothing to open")
            return
        p = self.rows[index]
        webbrowser.open(
            f"https://www.openstreetmap.org/?mlat={p['lat']}&mlon={p['lon']}"
            f"#map=16/{p['lat']}/{p['lon']}")

    def on_live_toggle(self):
        if self.live.get():
            self._live_tick()

    def _live_tick(self):
        if not self.live.get():
            return
        self.refresh()
        self.root.after(self.interval_s * 1000, self._live_tick)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--creds", default="creds.txt",
                        help="path to creds.txt (default: ./creds.txt)")
    args = parser.parse_args()

    root = Tk()
    ViewerApp(root, Path(args.creds))
    root.mainloop()


if __name__ == "__main__":
    main()
