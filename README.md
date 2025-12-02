# Super Mob Tracker

A Minecraft 1.12.2 mod that lets you select a mob to view spawn conditions and track live, no item required.

## Features
- GUI accessible via keybinding (default O) or Inventory button.
- Filterable scrolling list of mobs on the left; right panel shows details and spawn conditions.
- Live tracking on the main screen for selected mob. Double-click a mob in the list to toggle tracking.
- Server config: enableTracking to globally disable tracking.
- Client config: detectionRange to set radius for considering spawn attempts.

## Commands

### /smtanalyze
Analyzes all registered mobs and exports results to the `supermobtracker/` folder. This is useful for mod developers to identify spawn condition issues or benchmark performance.

Running `/smtanalyze` with no arguments runs all analyses with default parameters. You can also run specific analyses:

- `/smtanalyze mobs [samples]` - Analyzes all mobs and records how long each takes to analyze. Results are separated into successful, failed (couldn't determine conditions), and crashed (threw exceptions). Each list is sorted slowest-first.

- `/smtanalyze dimension [samples] [extendedCount] [numGrids]` - Benchmarks the dimension-to-biome mapping system with per-dimension timing. Useful for tuning the sampling parameters if dimension detection is slow or inaccurate.

## Building
Run:
```
./gradlew -q build
```
Resulting jar will be under `build/libs/`.

## Dev Tools
- **Profiling**: Enable timing profiling for spawn condition analysis
  ```
  -Dsupermobtracker.profile=true
  ```
  Outputs analysis timing to console.

## License
This project is licensed under the MIT License - see the LICENSE file for details.
