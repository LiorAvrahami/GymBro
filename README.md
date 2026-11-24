# GymBro

An Android app that tracks your gym training: pick the station you are on (scan its QR code or tap its picture), enter
the reps, the difficulty and the weight, and watch your progress on the statistics page.

- **Install**: every push builds a signed APK and publishes it as a GitHub release
  (**Releases → the newest build → `GymBro.apk`**). Open it on the phone; the first time Android asks to allow installing
  apps from the browser. Every build is signed with the same key (`app/keystore/`), so each one installs over the last.
- **Your data is never lost**: at the first start GymBro asks for a folder (e.g. Documents; it makes `GymBro/` inside).
  The data (`gymbro_data.json`), the photos (`images/`), a daily backup (`backups/`, 60 days) and the log
  (`gymbro_log.jsonl`) are kept there as well as in the app. Android does not delete that folder on an update or on
  uninstall; after a reinstall, choose the same folder and everything comes back.

## The screens (the user's layout draft, `design/app_layout.free`, a Lunacy file: a zip of JSON pages)

- **Launcher**: statistics (pie chart), add a station (+), and the camera's view, which reads QR codes all the while the
  page is on the screen. Below, every station, all on one screen (the grid takes as many columns as it needs, see
  `core/.../Logic.kt` `GridFit`); stations without a QR code come first. Each station is its photo, with the reps done on
  it today in a round badge. Tap a station (or show its QR code to the camera) to open it; long-press to edit it. An
  unknown QR code offers to add a station with it.
- **Station**: its picture (tap: all its pictures), its name, back; the reps (slider, 6 to 15), the difficulty (slider,
  1 to 10), the weight used (big number with up/down triangles in steps of the station's increment; tap the number to
  type it); the pencil edits the station; Submit saves the set and goes back. Everything starts at what was done last
  time on that station.
- **New station / edit**: the name; a square photo (tap the camera; tap the photo again to replace it; the grey + adds
  more pictures); the QR scanner, which shows the value it read; the machine's weight increment (5 by default).
  Editing also deletes a station (its sets stay in the history).
- **Statistics**: *Export all data* (one JSON file in Downloads with the data, every photo in base64, every QR value,
  device and storage diagnostics and the whole log; then Share); today / last 7 days / all time / streak; sets per day
  over 30 days; each station (tap for its weight over time and last sets); the history (long-press a set to delete it);
  where the data is kept, and a button to change the folder.

## Building

| Module | What it is |
|---|---|
| `core/` | Plain Kotlin: the data model, its JSON, the station grid's fit, the statistics. `gradle :core:test`. |
| `app/` | The Android app (Kotlin, views, CameraX, ML Kit's barcode reader with its model built in). |

- `GYMBRO_SKIP_ANDROID=1 gradle :core:test` runs where there is no Android SDK (the cloud session that maintains this
  project has none); GitHub builds the app (`.github/workflows/build.yml`, `tools/ci_build.sh`).
- `python tools/check_app.py` checks resources, ids and the manifest before pushing.
- Icons: [Lucide](https://lucide.dev) (ISC licence, `design/lucide/LICENSE`), converted by `design/make_ui_icons.py`.

The structure follows the 6SteP project (https://github.com/LiorAvrahami/6SteP).
