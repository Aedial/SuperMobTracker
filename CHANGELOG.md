# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


## [0.2.0] - 2025-12-02
### Added
- Add config option to select HUD position from 3x3 grid.
- Add config option to tweak some misc HUD display settings.
- Remember the filter text box contents when reopening the GUI.
- Add proper button to open the GUI from inventory screen (temporary icon).

### Fixed
- Make HUD box much nicer.
- Fix Z-priority issues with biomes tooltip.


## [0.1.3] - 2025-12-01
### Added
- Add /smtanalyze command to analyze mob spawn conditions and dimension-biome mapping.
  - `/smtanalyze mobs [samples]` - Analyzes all mobs and records how long each takes to analyze. Results are separated into successful, failed (couldn't determine conditions), and crashed (threw exceptions). Each list is sorted slowest-first.
  - `/smtanalyze dimension [samples] [extendedCount] [numGrids]` - Benchmarks the dimension-to-biome mapping system with per-dimension timing. Useful for tuning the sampling parameters if dimension detection is slow or inaccurate.
  - `/smtanalyze` with no arguments runs all analyses with default parameters.
- Add dimension inference based on biomes, as some mobs check for the dimension directly.

### Fixed
- Fix the mob spawn conditions that depend on dimension checks.


## [0.1.2] - 2025-11-30
### Fixed
- Fix Any not being localized for ground blocks in spawn conditions panel.
- Fix(-ish) flaky spawn condition analysis for most mobs (only a few mobs remain flaky at default retry count).
  -> For that, a retry count config option has been added (default 100).
  -> Some mobs may still have sparce or missing conditions due to inherent randomness in their spawning logic. Refreshing the selection or increasing the retry count may help.

## Changed
- Single biome spawn condition now shows the biome name instead of count.


## [0.1.1] - 2025-11-29
### Added
- Right-click to clear mob filter text box.

### Fixed
- Correct spawn condition analysis for mobs that cannot spawn naturally.
- Fix JER GUI staying on page 1 when opening from mob tracker GUI.
- Fix mob search not using localized names for matching, in Localized mode.
- Fix mobs not being filtered by localized names (now they match both localized and unlocalized names).
- Make the Biomes tooltip cleaner.
- Correctly localize ground block names in spawn conditions panel.
- Correctly localize biome names in spawn conditions panel.

### Technical
- Remove biome expansion logic. Mobs without a native biome will be considered to not spawn naturally.
- Cache native biomes for each mob to avoid repeated expensive lookups.


## [0.1.0] - 2025-11-28
### Added
- GUI accessible via keybinding (default N) or Inventory button.
- Live tracking on the main screen for selected mob. Double-click a mob in the list to toggle tracking.
- Server config: enableTracking to globally disable tracking.
- Client config: detectionRange to set radius for considering spawn attempts.
- Tracked list and GUI state persistence across game sessions.
- Filterable scrolling list of mobs on the left; right panel shows details and spawn conditions.
  - The spawn conditions panel includes inferred biomes, ground blocks, light levels, times of day, and weather conditions.
  - Conditions are derived from live spawn attempt data, so it should stay up to date with other mods that modify mob spawning.
  - If JEI and JER (Just Enough Resources) are installed, a button is provided to view the mob's drops in JER.
