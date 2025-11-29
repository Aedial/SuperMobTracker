# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


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
