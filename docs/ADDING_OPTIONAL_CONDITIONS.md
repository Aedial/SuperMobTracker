# Adding Optional Conditions to SuperMobTracker

This document describes how to add new optional conditions to the spawn condition analysis system. Optional conditions are conditions that are only tracked when the entity's spawn logic actually checks for them.

## Architecture Overview

The spawn condition analysis uses a queue-based combination system:

1. **SimulatedWorld** - A fake world that intercepts condition checks
2. **SampleFinder** - Tests combinations to find the first valid spawn
3. **ConditionExpander** - Expands valid samples to find all acceptable values (uses utility methods for consistency)
4. **GUI Display** - Shows the results to the user
5. **Localization** - Provides translated strings

### Seed Management

The system tracks the random seed that successfully finds the first spawn sample. This seed is then reused during condition expansion to improve consistency:
- `ConditionUtils.resetSuccessfulSeed()` - Called at the start of analysis to clear the seed
- `ConditionUtils.canSpawn()` - Records the seed when spawn succeeds
- `ConditionUtils.canSpawnWithSeed()` - Used by ConditionExpander to try the saved seed first, falling back to retries if needed

## Condition Types

There are two main types of optional conditions:

### Boolean Conditions (e.g., canSeeSky, isSlimeChunk, isNether)
- Two possible values: true/false
- Stored as `Boolean` in SpawnConditions (null/true/false)
- GUI displays one of two strings based on the value

### Multi-Value Conditions (e.g., moonPhase, timeOfDay, weather)
- Multiple possible values (e.g., 0-7 for moon phases, "day"/"night"/"dawn"/"dusk" for time)
- Stored as `List<T>` in SpawnConditions (null or list of valid values)
- GUI displays the list of valid values joined by separator

## Step-by-Step Guide

### 1. Add Tracking to SimulatedWorld

In `SpawnConditionAnalyzer.java`, the `SimulatedWorld` class intercepts world queries.

#### For Boolean Conditions:

```java
public boolean myCondition = false;  // Default value

@Override
public boolean getMyCondition() {
    queriedConditions.put("myCondition", true);  // Track that this was queried
    return myCondition;
}
```

#### For Multi-Value Conditions:

```java
public int myConditionValue = 0;  // Default value

@Override
public int getMyConditionValue() {
    queriedConditions.put("myCondition", true);  // Track that this was queried
    return myConditionValue;
}
```

Also update the `reset()` method and constructor to initialize the field and query tracker.

### 2. Add to SpawnConditions

In the `SpawnConditions` class (in `SpawnConditionAnalyzer.java`), add a field to store the result:

#### For Boolean Conditions:

```java
// null = not checked/doesn't matter, true = requires condition, false = excludes condition
public Boolean requiresMyCondition;
```

#### For Multi-Value Conditions:

```java
// null = doesn't matter, else list of valid values
public List<Integer> myConditionValues;
```

Update both constructors to include the new field.

### 3. Update SampleFinder

In `SampleFinder.java`, add your condition to the combination system:

#### 3.1. Add to ValidSample

```java
public static class ValidSample {
    // For boolean:
    public final boolean myCondition;
    // For multi-value:
    public final int myConditionValue;
}
```

#### 3.2. Add to OPTIONAL_CONDITIONS

Add your condition to the static list with all possible values:

```java
private static final List<OptionalCondition> OPTIONAL_CONDITIONS = Arrays.asList(
    // Boolean condition - 2 values
    new OptionalCondition("myCondition", false, true),
    // Multi-value condition - all possible values
    new OptionalCondition("myMultiCondition", 0, 1, 2, 3, 4, 5, 6, 7)
);
```

#### 3.3. Update applyConditionCombination()

```java
private void applyConditionCombination(ConditionCombination combo) {
    // For boolean:
    if (combo.has("myCondition")) world.myCondition = (Boolean) combo.get("myCondition");

    // For multi-value (Integer):
    if (combo.has("myMultiCondition")) world.myConditionValue = (Integer) combo.get("myMultiCondition");
}
```

#### 3.4. Update findWithCurrentConfig() return

Add the new field to the ValidSample constructor call.

```java
private ValidSample findWithCurrentConfig(List<Integer> lightLevels, List<Integer> yLevels) {
    Map<String, Boolean> aggregatedQueried = new HashMap<>();

    for (Integer y : yLevels) {
        for (Integer light : lightLevels) {
            world.lightLevel = light;

            if (canSpawn(entityClass, world, 0.5, y, 0.5)) {
                mergeQueriedConditions(aggregatedQueried, world.getAndResetQueriedConditions());
                // Build and return ValidSample with all fields
                return new ...; // <-- Your field should be included here
            }

            mergeQueriedConditions(aggregatedQueried, world.getAndResetQueriedConditions());
            lastQueriedConditions = aggregatedQueried;
        }
    }

    return null;
}
```

### 4. Update ConditionExpander

In `ConditionExpander.java`, use the built-in utility methods for expanding conditions:

#### 4.1. Add to ExpandedConditions

```java
public static class ExpandedConditions {
    // For boolean:
    public Boolean requiresMyCondition = null;
    // For multi-value:
    public List<Integer> myConditionValues = null;
}
```

#### 4.2. Add expand method

The `ConditionExpander` class provides utility methods to simplify condition expansion. Use these instead of writing repetitive code:

##### For Boolean Conditions:

Use `expandBooleanCondition()`:

```java
private Boolean expandMyCondition(SampleFinder.ValidSample sample) {
    return expandBooleanCondition(sample.y, v -> world.myCondition = v, sample.myCondition);
}
```

The utility method handles:
- Testing both true and false values
- Restoring the original value after testing
- Returning null if both work, true/false if only one works

##### For Multi-Value Conditions:

Use `expandListCondition()`:

```java
private List<Integer> expandMyConditionValues(SampleFinder.ValidSample sample) {
    List<Integer> allValues = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);

    return expandListCondition(
        sample.y, allValues,
        v -> world.myConditionValue = v,
        sample.myConditionValue
    );
}
```

The utility method handles:
- Testing all values in the list
- Restoring the original value after testing
- Returning null if all values work
- Returning the sample value if no values work (fallback)

#### 4.3. Call from expandAll()

Only expand if the condition was queried:

```java
if (queried != null && queried.getOrDefault("myCondition", false)) {
    result.requiresMyCondition = expandMyCondition(sample);  // Boolean
    // OR
    result.myConditionValues = expandMyConditionValues(sample);  // Multi-value
}
```

### 5. Update ConditionUtils

In `ConditionUtils.java`, add hint constant for failure messages:

```java
public static final String HINT_MY_CONDITION = "hint.mobtracker.mycondition";
```

### 6. Update GUI Display

In `GuiMobTracker.java`, in the `drawRightPanel()` method:

##### For Boolean Conditions:

```java
if (spawnConditions.requiresMyCondition != null) {
    String key = spawnConditions.requiresMyCondition ? "required" : "excluded";
    String text = I18n.format("gui.mobtracker.mycondition." + key);
    textY = drawWrappedString(fontRenderer, text, condsX, textY, 12, textW, skyColor);
}
```

##### For Multi-Value Conditions:

```java
if (spawnConditions.myConditionValues != null && !spawnConditions.myConditionValues.isEmpty()) {
    String valuesStr = spawnConditions.myConditionValues.stream()
        .map(value -> I18n.format("gui.mobtracker.mycondition." + value))
        .collect(Collectors.joining(sep));
    String text = I18n.format("gui.mobtracker.mycondition", valuesStr);
    textY = drawWrappedString(fontRenderer, text, condsX, textY, 12, textW, skyColor);
}
```

### 7. Add Localization

In `en_us.lang`:

##### For Boolean Conditions:

```properties
hint.mobtracker.mycondition=May require specific condition.

gui.mobtracker.mycondition.required=My Condition: Required
gui.mobtracker.mycondition.excluded=My Condition: Not allowed
```

##### For Multi-Value Conditions:

```properties
hint.mobtracker.mycondition=May require specific condition value.

gui.mobtracker.mycondition=My Condition: %s
gui.mobtracker.mycondition.0=Value Zero
gui.mobtracker.mycondition.1=Value One
gui.mobtracker.mycondition.2=Value Two
# ... etc for all values
```

## Real Examples

### Boolean: canSeeSky

- **SimulatedWorld**: `public boolean canSeeSky = true;`
- **SpawnConditions**: `public final Boolean canSeeSky;`
- **SampleFinder**: `new OptionalCondition("canSeeSky", true, false)`
- **ConditionExpander**: Tests true and false, returns null/true/false
- **GUI**: Displays "Location: Outside only" or "Location: Underground only"

### Multi-Value: moonPhase (8 values)

- **SimulatedWorld**: `public int moonPhase = 0;` (0-7)
- **SpawnConditions**: `public final List<Integer> moonPhases;`
- **SampleFinder**: `new OptionalCondition("moonPhase", 0, 1, 2, 3, 4, 5, 6, 7)`
- **ConditionExpander**: Tests all 8 phases, returns null or list of valid phases
- **GUI**: Displays "Moon Phase: Full Moon, Waning Gibbous" (joined list of valid phase names)
- **Localization**: `gui.mobtracker.moonphase.0=Full Moon`, `gui.mobtracker.moonphase.1=Waning Gibbous`, etc.

## Important Notes

1. **Query Tracking**: Always track when a condition is queried by putting an entry in `queriedConditions`. This prevents unnecessary combination testing.

2. **Default Values**: The default value should match vanilla behavior (usually false for boolean, 0 for multi-value).

3. **Performance**: The queue-based system tests combinations lazily. A new condition only generates combinations when it's actually queried during a failed spawn attempt.

4. **Boolean Pattern**: `null` = not queried or doesn't matter, `true` = requires true, `false` = requires false

5. **Multi-Value Pattern**: `null` = not queried or all values work, `List<T>` = only these values work

6. **GUI Display**: For boolean conditions, always handle BOTH true and false cases. For multi-value conditions, display the list of valid values.

7. **Localization Format**: Use "Prefix: %s" format for multi-value, "Prefix: Value" format for boolean.

8. **Seed Reuse**: The `ConditionExpander` automatically uses the seed that found the first valid sample. This improves consistency for mobs with random spawn checks. Use `testSpawn(y)` or the utility methods which internally use `canSpawnWithSeed()`.

9. **Utility Methods**: Always use `expandBooleanCondition()` and `expandListCondition()` for new conditions to maintain code consistency and reduce boilerplate.
