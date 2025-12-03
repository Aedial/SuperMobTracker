package com.supermobtracker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import com.supermobtracker.IProxy;
import com.supermobtracker.command.CommandAnalyze;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.tracking.SpawnEventHandler;


@Mod(modid = SuperMobTracker.MODID, name = SuperMobTracker.NAME, version = SuperMobTracker.VERSION, acceptedMinecraftVersions = "[1.12,1.12.2]", guiFactory = "com.supermobtracker.config.ConfigGuiFactory")
public class SuperMobTracker {
    public static final String MODID = "supermobtracker";
    public static final String NAME = "Super Mob Tracker";
    public static final String VERSION = "0.2.1";

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
        // Only subscribe to gameplay events if server config allows tracking
        if (ModConfig.serverEnableTracking) MinecraftForge.EVENT_BUS.register(new SpawnEventHandler());

        proxy.init();
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandAnalyze());
    }
}
