# Super Mob Tracker

A client-side Minecraft 1.12.2 mod that lets you select mobs to view spawn conditions and track live, no item required.

## Features
- GUI accessible via keybinding (default O) or Inventory button.
- Filterable scrolling list of mobs on the left; right panel shows details and spawn conditions.
- Live tracking on the main screen for selected mob. Double-click a mob in the list to toggle tracking.
- Click on the biomes list to copy biome names to clipboard.
- Support for Just Enough Resources, custom drops viewer, and full drops tables integrated with JEI.
  - Custom drops viewer cannot be used without server-side installation (if you play singleplayer, you can ignore this). However, the JEI integration of drops tables will work without server-side installation, as long as you have the loot dump file in config.
  - The custom viewer can use mouse or keys to open JEI: U/Left-click for uses, R/Right-click for recipes.


### Configs
- **enableTracking**: Globally disable tracking (requires restart).
- **detectionRange**: Set radius for considering spawn attempts.
- **spawnCheckRetries**: Set maximum retries for spawn condition checks. Higher values handle random spawn conditions better but increase analysis time on selection. This can lead to some high delays when selecting some mobs with tricky spawn conditions.
- **whitelist/blacklist**: Configure which mobs are allowed/disallowed for tracking. Whitelist takes priority over blacklist. Partial matches are supported (e.g., `zomb` matches all mobs with "zombie" in their ID or `minecraft` matches all mobs from the Minecraft namespace). To avoid matching too broadly, keep the `:` separator for namespace matching (e.g., `aoa:`). As it is a purely client-side mod, there is no way to enforce server-side mob restrictions.
- **dropSimulationCount**: Number of loot table simulations to run when estimating drops. Higher values yield more accurate results but increase analysis time. The process is done asynchronously to avoid blocking the UI, so increasing this value should not cause issues.
- **useModelXRay**: Enable X-ray rendering for the entity preview, instead of the default glowing outline.
- **external spawn hints**: Add non-natural or spawn-table-less mobs in `config/supermobtracker/spawn_hints.json`. This is intended for worldgen, structures, scripted spawns, spawners, and similar sources that do not appear in normal biome spawn lists.
- **loot dump**: Required for the JEI integration of loot tables to work. See the `/smtlootdump` command section below, for more details.

## External Spawn Hints
Some mobs do not use normal biome spawn tables at all, so the analyzer has nothing native to sample from. For those cases, Super Mob Tracker can load fallback spawn metadata from `config/supermobtracker/spawn_hints.json`.

The parser accepts biome IDs and biome dictionary types in the same entry. Dimension ID is optional: if omitted, the mod tries to infer it from the resolved biome list and otherwise falls back to the current dimension. Known spawn reasons such as `worldgen`, `structure`, `spawner`, `event`, and `command` get built-in labels in the GUI, while unknown values are displayed as-is. Bundled defaults are loaded first, and the user config file overrides them by entity ID.

Validation notes: `lightLevels` use the vanilla 0-15 range, `timeOfDay` uses Minecraft day ticks from 0 to 23999, and `weather` accepts `clear`, `rain`, or `thunder`.

Example:

```json
{
  "version": 1,
  "entries": [
    {
      "entityId": "iceandfire:seaserpent",
      "spawnReason": "worldgen",
      "biomes": {
        "types": ["OCEAN"]
      },
      "groundBlocks": ["water"],
      "lightLevels": {
        "min": 0,
        "max": 15
      },
      "yLevels": {
        "min": 20,
        "max": 59
      }
    }
  ]
}
```

See [`docs/spawn_hints.example.json`](https://github.com/Aedial/SuperMobTracker/tree/main/docs/spawn_hints.example.json) for a copy-paste example and [`docs/spawn_hints.schema.json`](https://github.com/Aedial/SuperMobTracker/tree/main/docs/spawn_hints.schema.json) for the JSON Schema. You can also use the bundled [`spawn_hints.defaults.json`](https://github.com/Aedial/SuperMobTracker/tree/main/src/main/resources/assets/supermobtracker/spawn_hints.defaults.json) as a reference.


## FAQ
### Do I need to install this on a server?
You do not need to, unless you want to use the custom loot tables viewer. Most of the time, JER should be sufficient for loot tables viewing. The mod will still work fine without server-side installation.

### How is the mobs list filtered?
The filter box matches both localized and unlocalized mob names. This means you can type the mod name, the name in your selected language, or the default English name.

### The spawn conditions seem off, why?
Many mobs have inherently random spawn conditions that may not be fully captured by the analysis. Increasing the `spawnCheckRetries` config can help, although such mobs should be fairly rare. You can always hit the [Retry] button to re-evaluate the spawn conditions on failure.

If the mob doesn't use normal biome spawn tables, add an external spawn hint instead of increasing retries. The GUI will then show the configured spawn source explicitly.

### Some biomes/dimensions/blocks show up as raw registry names, why?
Some mods do not provide proper localization keys for their biomes/dimensions//blocks. In this case, your modpack should provide a `lang` file with the appropriate keys to get proper names. Considering a raw name of `<modid>:<biome_name>`, the localization key format is `biome.<modid>.<biome_name>.path` for biomes, `dimension.<modid>.<dimension_name>.path` for dimensions, and `tile.<modid>.<block_name>.name` for blocks. I will not include other-mods-specific localization in the mod itself.

Of course, if localization for these exist, it is a bug and should be reported.

### Some drops are missing or incorrect, why?
The drop simulation system uses a fake player and initializes the entity in a minimal fake world. However, some mobs may require a real player (e.g. Corail Tombstone) or get their loot table initialized in a special way that the simulation cannot replicate. In these cases, some drops may be missing or incorrect.

On top of that, due to statistical randomness in loot tables, most drops will have some variance unless they are guaranteed drops. Increasing the `dropSimulationCount` config can help reduce variance, but some randomness will always remain. If a drop is very rare, it may not show up at all in the simulation.

## Commands

### /smtlootdump
Dumps all loot tables into a single zipped JSON file, in the `config/supermobtracker/` folder. This is required for the JEI integration of loot tables to work, and will need to be re-run whenever loot tables change (e.g., after adding/removing mods or changing mod configs). Of course, an un-updated loot dump will not break anything, but the loot information may be outdated.

You may provide the number of iterations to simulate for each loot table, which will be used to estimate drop chances. The default uses the `dropSimulationCount` config value.

### /smtanalyze
Analyzes all registered mobs and exports results to the `supermobtracker/` folder. This is useful to identify spawn condition issues or benchmark performance. If a lot of mobs fail to analyze, even at high `spawnCheckRetries`, consider opening an issue with the exported data.

Running `/smtanalyze` with no arguments runs all analyses with default parameters. You can also run specific analyses:

- `/smtanalyze mobs [samples]` - Analyzes all mobs and records how long each takes to analyze. Results are separated into successful, failed (couldn't determine conditions), and crashed (threw exceptions). Each list is sorted slowest-first.

- `/smtanalyze dimension [samples] [extendedCount] [numGrids]` - Benchmarks the dimension-to-biome mapping system with per-dimension timing. Useful for tuning the sampling parameters if dimension detection is slow or inaccurate.

- `/smtanalyze loot [samples] [simulationCount]` - Analyzes all loot tables and records how long each takes to analyze. Results are separated into successful and crashed (threw exceptions). Each list is sorted slowest-first.

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
