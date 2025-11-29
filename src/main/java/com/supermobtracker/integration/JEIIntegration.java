package com.supermobtracker.integration;

// TODO: the API is 60kB, wtf. Make a duck for it.
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JEIPlugin;


/**
 * JEI plugin that stores the runtime for later use by JEIHelper.
 */
@JEIPlugin
public class JEIIntegration implements IModPlugin {
    private static IJeiRuntime runtime = null;

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    public static boolean isRuntimeAvailable() {
        return runtime != null;
    }
}
