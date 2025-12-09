# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


## [1.1.0] - 2025-12-08
### Added
- Add whitelist/blacklist config options to prevent tracking of specific mobs (see README).

### Fixed
- (Almost entirely) fix incomplete spawn condition analysis for mobs with very random spawning logic (e.g., Aether mobs). Analysis should now be stable at default retry count for almost all mobs.

### Changed
- Streamline spawn condition expansion logic.

### Technical
- To eliminate most sources of randomness in spawn condition analysis, we cache the random seed used for the first valid spawn attempt. This ensures that all conditions succeed at the first attempt, as long as they are not covariant on the randomness itself (of course, considering the condition should succeed in that case).
- The usual retries at random seeds are still performed as a fallback for to catch any remaining randomness.


## [1.0.0] - 2025-12-07
### Added
- Expand spawn condition analysis to include moon phase, slime chunk, and nether checks.

### Technical
- Refactor spawn condition analyzer to handle optional conditions more cleanly and extensibly. This allows for easier addition of new condition types in the future. It was the original plan with the queried conditions map, but performance wasn't bad enough to warrant it (especially after the ground blocks optimization).
- Move ground blocks, time of day, weather, and requires sky conditions to the new optional conditions system.
- Document the whole optional conditions system.


## [1.0.0-rc4] - 2025-12-05
### Added
- Add canSeeSky spawn condition detection and display in the spawn conditions panel.

### Fixed
- Fix the spawn conditions not working for some mobs due to canSeeSky checks and restrictive ground block checks.
- Fix each analysis creating a new fake world instead of reusing the same one, leading to BoP logs spam in BoP worlds.

### Changed
- Improve ground block logic to make mobs relying on them faster much faster (up to 10-100x for the worst cases).
- Differentiate between successful analysis with sparse conditions and complete conditions in the /smtanalyze command output.


## [1.0.0-rc1] - 2025-12-04
### Fixed
- Mark mod as client-side only to prevent potential issues when installed on dedicated servers.
- Move `enableTracking` config from server to client category (requires restart to take effect).
- Remove unused server-side code (server packet sending, server command registration).


## [0.2.2] - 2025-12-03
### Fixed
- Fix crash related to how Lost Cities handles Biomes O' Plenty, overwriting the world type, due to using a non-BoP fake world for spawn checks.


## [0.2.1] - 2025-12-03
### Added
- Add option to hide the HUD.
- Localize the names for the configs.
- Add info tooltip on why a dimension may be unknown in the spawn conditions panel.
- Add Java arg to log spawn condition crashes: -Dsupermobtracker.showcrashes=true
- Save config on Escape key (instead of only on Done button).

### Fixed
- Fix some mobs crashing the spawn conditions analyzer, due to wrong biome ID handling.
- Fix Biomes to Dimension mapping sometimes undersampling rare biomes: now it will sample over time, accumulating more data instead of sampling once at startup. So far, only ultra-rare biomes like Mushroom Islands are still affected.

### Changed
- Improve the selection star in the mob list.


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
