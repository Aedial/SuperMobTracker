package com.supermobtracker.integration.jei;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JEIPlugin;

import javax.annotation.Nonnull;


/**
 * JEI plugin that stores the runtime for later use by JEIHelper.
 */
@JEIPlugin
public class JEIIntegration implements IModPlugin {
    private static IJeiRuntime runtime = null;

    @Override
    public void onRuntimeAvailable(@Nonnull IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    public static boolean isRuntimeAvailable() {
        return runtime != null;
    }
}
