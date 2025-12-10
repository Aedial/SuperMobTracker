# Super Mob Tracker

A client-side Minecraft 1.12.2 mod that lets you select mobs to view spawn conditions and track live, no item required.

## Features
- GUI accessible via keybinding (default O) or Inventory button.
- Filterable scrolling list of mobs on the left; right panel shows details and spawn conditions.
- Live tracking on the main screen for selected mob. Double-click a mob in the list to toggle tracking.
- Click on the biomes list to copy biome names to clipboard.
- Configs:
  - enableTracking: Globally disable tracking (requires restart).
  - detectionRange: Set radius for considering spawn attempts.
  - spawnCheckRetries: Set maximum retries for spawn condition checks. Higher values handle random spawn conditions better but increase analysis time on selection. This can lead to some high delays when selecting some mobs with tricky spawn conditions.
  - whitelist/blacklist: Configure which mobs are allowed/disallowed for tracking. Whitelist takes priority over blacklist. Partial matches are supported (e.g., `zomb` matches all mobs with "zombie" in their ID or `minecraft` matches all mobs from the Minecraft namespace). To avoid matching too broadly, keep the `:` separator for namespace matching (e.g., `aoa:`). As it is a purely client-side mod, there is no way to enforce server-side mob restrictions.

## Questions
### Do I need to install this on a server?
No, this mod is client-side.

### Is it fine to put in on server-side either way?
It is fine, the mod will simply not do anything server-side.

### How is the mobs list filtered?
The filter box matches both localized and unlocalized mob names. This means you can type the mod name, the name in your selected language, or the default English name.

### The spawn conditions seem off, why?
Many mobs have inherently random spawn conditions that may not be fully captured by the analysis. Increasing the `spawnCheckRetries` config can help, but the conditions could still be sparse. Refreshing the selection may also yield different results due to this. This is also why some mobs can be slow to analyze (taking a second).

### Some biomes/dimensions/blocks show up as raw registry names, why?
Some mods do not provide proper localization keys for their biomes/dimensions//blocks. In this case, your modpack should provide a `lang` file with the appropriate keys to get proper names. Considering a raw name of `<modid>:<biome_name>`, the localization key format is `biome.<modid>.<biome_name>.path` for biomes, `dimension.<modid>.<dimension_name>.path` for dimensions, and `tile.<modid>.<block_name>.name` for blocks. I will not include other-mods-specific localization in the mod itself.
Of course, if localization for these exist, it is a bug and should be reported.

## Commands

### /smtanalyze
Analyzes all registered mobs and exports results to the `supermobtracker/` folder. This is useful to identify spawn condition issues or benchmark performance.

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
