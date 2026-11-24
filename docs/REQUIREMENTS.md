# GymBro — Requirements (living notes)

Status: **collecting requirements — not implemented yet.** More details will be added later.

## Purpose
1. Track personal gym training and progress over time.
2. Track and guide the user through a live training session.

## Platform & architecture
- A **real native/installed app, not a web app**.
- Use https://github.com/LiorAvrahami/6SteP as the reference for how to build the app (stack, structure, build setup).
- **Data durability is critical.** Personal training data must be stored somewhere persistent that
  survives app updates **and even uninstalling** the app (e.g. user-visible shared storage /
  external file the user owns, and/or a backup/export mechanism). The app storage that gets wiped on
  uninstall is not enough on its own.

## Stations
- The gym has stations (machines). Example: Technogym "ARM CURL" station
  (see `reference/station-arm-curl-technogym.jpg`): a placard with the station name, a muscle diagram
  (highlighted target muscles, here biceps), a usage illustration, and a **QR code** (technogym.com /
  mywellness).
- Two ways to choose the station you're using:
  1. **Scan the station's QR code** → means "I'm using this station now".
  2. **Pick it from a table/grid of stations with icons.**

## Session flow (so far)
- After a station is selected (scan or table), wait **~1 minute**, then show a screen where the
  user selects **how many reps** they did.

## Open questions (to be described later by the user)
- Weights/sets logging, rest timers, workout plans / guidance logic.
- How QR codes map to stations (each machine's QR content).
- Progress views/stats.
