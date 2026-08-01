# Weaize

A personal-use Android app that combines Waze-style driving assistance with a
simpler OwnTracks-style private location sharing system.

## Features

- **Map + navigation view** using OpenStreetMap (osmdroid), no Google services required.
- **Speed estimates** from GPS doppler speed, with distance/time fallback, shown live on screen.
- **Gyroscope**: sharp-turn detection while driving triggers a voice warning.
- **Voice warnings (TTS)** in the client-selected language (English, Spanish, Portuguese,
  French, German, Italian): overspeed, sharp turn, GPS lost.
- **Offline maps**: pre-download a region of the road system for offline use
  ("Offline maps" button — enter the bounding box), or enable
  *auto-download tiles around me* in settings.
- **Private location sharing**: the app periodically uploads your position to your
  Supabase Postgres server, encrypted end-to-end with AES-256-GCM using a key derived
  (PBKDF2) from your **private key**. The server only stores ciphertext — only parties
  who input the private key can read locations.
- **Append-only history**: the API allows insert/select only (see `server/schema.sql`),
  so previous locations are never deleted.

## Setup

1. Create the table: run `server/schema.sql` in your Supabase SQL editor.
2. Fill in `viewer/creds.txt.example` → save as `creds.txt` (add your private key line, or
   leave it out and the viewer will prompt for it).
3. In the app: *Import creds.txt* (or type the Supabase URL and API key in Settings),
   then set your **private key** in Settings.
4. Press **Start** to begin tracking and uploading.

## Viewing locations from a computer (Windows / macOS / Linux)

```bash
pip install cryptography requests
python viewer/weaize_viewer.py                    # last known location
python viewer/weaize_viewer.py --history 100      # recent history
python viewer/weaize_viewer.py --creds ~/creds.txt --device <uuid>
python viewer/weaize_viewer.py --live             # press P to toggle live tracking, Q to quit
```

Or use the GUI (tkinter, bundled with Python on Windows/macOS; on Debian/Ubuntu
install it with `sudo apt install python3-tk`):

```bash
python viewer/weaize_viewer_gui.py                # loads ./creds.txt if present
python viewer/weaize_viewer_gui.py --creds ~/creds.txt
```

The GUI shows the location history in a table, has a "Live tracking"
auto-refresh toggle, and double-clicking a row (or "Open in map") opens the
position in OpenStreetMap in your browser.

A backup Postgres/Supabase service can be configured with the
`supabase local address 2` / `supabase proj id 2` / `supabase apikey pub 2`
lines in creds.txt (and the matching "Backup Supabase" fields in the app's
Settings): the app retries failed uploads against the backup and the viewer
falls back to it when the primary is unreachable.

The viewer never deletes anything; it decrypts locally using the private key from
creds.txt (or an interactive prompt) and prints coordinates, speed, bearing and an
OpenStreetMap link.

## Building the app

```bash
cd weaize
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 34).

## Security notes

- The private key never leaves your devices; the phone encrypts before upload and the
  viewer decrypts after download.
- The Supabase anon key only permits `INSERT` and `SELECT` on the `locations` table
  (row-level security), so the API cannot be used to erase history.
- Keep `creds.txt` out of version control.
