package com.supermobtracker.config;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.util.ArrayList;
import java.util.List;

public class ModGuiConfig extends GuiConfig {

    public ModGuiConfig(GuiScreen parentScreen) {
        super(parentScreen, getConfigElements(), "supermobtracker", false, false, "Super Mob Tracker Config");
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> list = new ArrayList<>();

        for (IConfigElement el : new ConfigElement(ModConfig.getConfig().getCategory("client")).getChildElements()) {
            if (ModConfig.isConfigHidden(el.getName())) continue;
            list.add(el);
        }

        // Add the HUD position selector as a config entry
        list.add(new HudPositionConfigElement());

        list.addAll(new ConfigElement(ModConfig.getConfig().getCategory("server")).getChildElements());

        return list;
    }
}
