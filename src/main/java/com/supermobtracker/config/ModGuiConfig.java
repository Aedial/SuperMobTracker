package com.supermobtracker.config;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;
import org.lwjgl.input.Keyboard;

import com.supermobtracker.Tags;


public class ModGuiConfig extends GuiConfig {

    public ModGuiConfig(GuiScreen parentScreen) {
        super(parentScreen, getConfigElements(), Tags.MODID, Tags.MODID, false, false, I18n.format(Tags.MODID + ".config.title"));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // Pull any saved GUI edits back through the managed @Config fields.
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

        for (IConfigElement el : ConfigElement.from(ModConfig.class).getChildElements()) {
            if (ModConfig.isConfigHidden(el.getName())) continue;
            list.add(el);
        }

        // Add the HUD position selector as a config entry
        list.add(new HudPositionConfigElement());

        return list;
    }
}
