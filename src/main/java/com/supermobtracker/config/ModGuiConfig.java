package com.supermobtracker.config;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;
import org.lwjgl.input.Keyboard;


public class ModGuiConfig extends GuiConfig {

    public ModGuiConfig(GuiScreen parentScreen) {
        super(parentScreen, getConfigElements(), "supermobtracker", false, false, "Super Mob Tracker Config");
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // Reload config values from in-memory Configuration into static fields after GUI closes
        ModConfig.syncFromConfig();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // ESC key (keyCode 1) should save config like Done button
        if (keyCode == Keyboard.KEY_ESCAPE && this.entryList != null) this.entryList.saveConfigElements();

        super.keyTyped(typedChar, keyCode);
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> list = new ArrayList<>();

        for (IConfigElement el : new ConfigElement(ModConfig.getConfig().getCategory("client")).getChildElements()) {
            if (ModConfig.isConfigHidden(el.getName())) continue;
            list.add(el);
        }

        // Add the HUD position selector as a config entry
        list.add(new HudPositionConfigElement());

        return list;
    }
}
