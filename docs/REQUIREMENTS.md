# GymBro — Requirements (living notes)

Status: **first version built** (see README.md for how each screen works).

## Purpose
1. Track personal gym training and progress over time.
2. Track and guide the user through a live training session.

## Platform & architecture
- A **real installed app, not a web app**: native Android, Kotlin, built like https://github.com/LiorAvrahami/6SteP
  (GitHub Actions builds a signed APK and publishes it as a release).
- **Data durability is critical.** The data must survive app updates **and uninstalling**: it is kept in a folder the
  user picks (Storage Access Framework) as well as in the app; daily backups in that folder.

## Stations
- Example: Technogym "ARM CURL" (`reference/station-arm-curl-technogym.jpg`): a placard with a QR code.
- Choose the station by **scanning its QR code** or **tapping it in a grid of pictures**.

## The layout draft (`design/app_layout.free`, user 2026-11-24)
- **Launcher**: stats button (pie chart icon), add-station button, live camera view for the QR scanner (always on while
  the page is shown, except with the screen off). All stations on one screen: smaller buttons / more columns as needed.
  Next to each station: the reps done today on it. Stations without a barcode first, without splitting them visibly.
  For now a station is shown as the picture taken of it.
- **StationSelectedPage**: station image (opens the gallery of its images), station name label, back icon; reps
  slider 6–15 (big bold gray label); difficulty slider (big bold gray label); "weight used" numeric up/down in the
  station's increments (number left, big up/down triangle buttons right); pencil to edit the station; submit (check)
  — registers a "StationUse" and returns to the launcher.
- **NewStation**: station name; camera button for a **square** photo (then shows the photo; tapping again replaces it;
  a gray + in a lighter gray circle at its bottom right adds more images, shown in the gallery); QR scanner view that
  shows the scanned value; weight increment (keyboard input, default 5, light gray large bold).

## Statistics view (designed by Claude)
- A button that exports **all the user data to one file in Downloads**, for diagnostics: lots of logging, all the
  images and QR values included.

## Notes / decisions
- The first message said "after a minute, prompt a screen to select the reps". The layout draft replaced it: opening a
  station shows the page where the reps are entered, and Submit returns to the launcher.
- Difficulty: 1–10. Weights in kg. Reps/difficulty/weight start at the last set's values on that station.
