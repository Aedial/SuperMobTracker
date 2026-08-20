package com.supermobtracker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import com.supermobtracker.config.ModConfig;
import com.supermobtracker.network.NetworkHandler;


@Mod(
    modid = Tags.MODID,
    name = Tags.MODNAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.12.2]",
    guiFactory = "com.supermobtracker.config.ConfigGuiFactory"
)
public class SuperMobTracker {
    public static final Logger LOGGER = LogManager.getLogger(Tags.MODID);

    @SidedProxy(
        clientSide = "com.supermobtracker.client.ClientProxy",
        serverSide = "com.supermobtracker.server.ServerProxy")
    public static IProxy proxy;

    @Mod.Instance
    public static SuperMobTracker INSTANCE;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModConfig.loadConfigs(event.getSuggestedConfigurationFile());
        NetworkHandler.registerPackets();
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
