package com.supermobtracker.util;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;


/**
 * Utility to temporarily mute specific loggers during drop simulation.
 * This prevents log spam from entity AI warnings and loot function warnings
 * that trigger thousands of times during simulation runs.
 */
public class LogMuter {

    /** Loggers that spam warnings during entity construction */
    private static final String[] MUTED_LOGGERS = {
        // Warns "Use NearestAttackableTargetGoal.class for PathfinderMob mobs!" for entities
        "net.minecraft.entity.ai.EntityAIFindEntityNearestPlayer",
        // Warns "Couldn't set data of loot item" for items
        "net.minecraft.world.storage.loot.functions.SetMetadata",
        // Doesn't like that we strip metadata from spell books
        "wizardry"
    };

    private static final Map<String, Level> originalLevels = new HashMap<>();
    private static boolean muted = false;

    /**
     * Mute the known spammy loggers.
     * Call this before starting a simulation run.
     */
    public static synchronized void muteLoggers() {
        if (muted) return;

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);

        for (String loggerName : MUTED_LOGGERS) {
            LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);

            // If exact logger doesn't exist, we need to add one
            if (!loggerConfig.getName().equals(loggerName)) {
                // Create a new logger config for this specific logger
                LoggerConfig newConfig = new LoggerConfig(loggerName, Level.OFF, true);
                ctx.getConfiguration().addLogger(loggerName, newConfig);
                originalLevels.put(loggerName, null); // Mark as newly created
            } else {
                originalLevels.put(loggerName, loggerConfig.getLevel());
                loggerConfig.setLevel(Level.OFF);
            }
        }

        ctx.updateLoggers();
        muted = true;
    }

    /**
     * Restore the original log levels.
     * Call this after simulation is complete.
     */
    public static synchronized void restoreLoggers() {
        if (!muted) return;

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);

        for (Map.Entry<String, Level> entry : originalLevels.entrySet()) {
            String loggerName = entry.getKey();
            Level originalLevel = entry.getValue();

            if (originalLevel == null) {
                // Was newly created, remove it
                ctx.getConfiguration().removeLogger(loggerName);
            } else {
                LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
                if (loggerConfig.getName().equals(loggerName)) {
                    loggerConfig.setLevel(originalLevel);
                }
            }
        }

        ctx.updateLoggers();
        originalLevels.clear();
        muted = false;
    }

    /**
     * Run an action with loggers muted, ensuring restoration even on exception.
     */
    public static void withMutedLoggers(Runnable action) {
        muteLoggers();
        try {
            action.run();
        } finally {
            restoreLoggers();
        }
    }
}
