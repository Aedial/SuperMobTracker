package com.supermobtracker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import com.supermobtracker.IProxy;
import com.supermobtracker.config.ModConfig;


@Mod(modid = SuperMobTracker.MODID, name = SuperMobTracker.NAME, version = SuperMobTracker.VERSION, acceptedMinecraftVersions = "[1.12,1.12.2]", guiFactory = "com.supermobtracker.config.ConfigGuiFactory", clientSideOnly = true)
public class SuperMobTracker {
    public static final String MODID = "supermobtracker";
    public static final String NAME = "Super Mob Tracker";
    public static final String VERSION = "1.2.1";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.supermobtracker.client.ClientProxy", serverSide = "com.supermobtracker.server.ServerProxy")
    public static IProxy proxy;

    @Mod.Instance
    public static SuperMobTracker INSTANCE;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModConfig.loadConfigs(event.getSuggestedConfigurationFile());
        proxy.preInit();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }
}
