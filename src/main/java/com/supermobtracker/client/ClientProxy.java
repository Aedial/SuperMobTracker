package com.supermobtracker.client;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.common.MinecraftForge;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.IProxy;
import com.supermobtracker.command.CommandAnalyze;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.client.event.ClientEvents;
import com.supermobtracker.client.gui.GuiHandler;
import com.supermobtracker.client.input.KeyBindings;
import com.supermobtracker.tracking.SpawnEventHandler;
import com.supermobtracker.tracking.SpawnTrackerManager;


public class ClientProxy implements IProxy {
    @Override
    public void preInit() {
        KeyBindings.register();
        NetworkRegistry.INSTANCE.registerGuiHandler(SuperMobTracker.INSTANCE, new GuiHandler());
    }

    @Override
    public void init() {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());

        // Register spawn event handler if tracking is enabled
        if (ModConfig.clientEnableTracking) MinecraftForge.EVENT_BUS.register(new SpawnEventHandler());

        // Restore client-tracked IDs
        SpawnTrackerManager.restoreTrackedIds(ModConfig.getClientTrackedIds());

        // Register client commands
        ClientCommandHandler.instance.registerCommand(new CommandAnalyze());
    }

    @Override
    public void postInit() {
    }
}
