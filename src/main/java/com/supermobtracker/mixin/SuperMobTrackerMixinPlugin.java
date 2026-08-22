package com.supermobtracker.mixin;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;

import zone.rong.mixinbooter.ILateMixinLoader;


/**
 * Registers the JEI-only overlay mixin when JEI is installed.
 */
@Optional.Interface(iface = "zone.rong.mixinbooter.ILateMixinLoader", modid = "mixinbooter")
public class SuperMobTrackerMixinPlugin implements ILateMixinLoader {

    @Override
    @Optional.Method(modid = "mixinbooter")
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<>();
        configs.add("mixins.supermobtracker.json");

        if (Loader.isModLoaded("jei")) configs.add("mixins.supermobtracker.jei.json");

        return configs;
    }
}
