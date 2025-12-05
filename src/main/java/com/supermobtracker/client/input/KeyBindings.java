package com.supermobtracker.client.input;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;

import com.supermobtracker.client.gui.GuiHandler;
import com.supermobtracker.client.gui.GuiMobTracker;

public class KeyBindings {
    private final static String category = "key.categories.supermobtracker";
    private final static String openTrackerDesc = "key.supermobtracker.open";

    public static KeyBinding openTracker;

    public static void register() {
        openTracker = new KeyBinding(openTrackerDesc, KeyConflictContext.IN_GAME, Keyboard.KEY_O, category);
        ClientRegistry.registerKeyBinding(openTracker);
    }

    public static void onClientTick() {
        if (openTracker.isPressed()) Minecraft.getMinecraft().displayGuiScreen(new GuiMobTracker());
    }
}
