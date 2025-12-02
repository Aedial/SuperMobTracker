package com.supermobtracker.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiConfigEntries.ButtonEntry;
import net.minecraftforge.fml.client.config.IConfigElement;

/**
 * Custom config entry that displays a button to open the HUD position selector GUI.
 */
public class HudPositionConfigEntry extends ButtonEntry {

    public HudPositionConfigEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement) {
        super(owningScreen, owningEntryList, configElement);
        updateButtonText();
    }

    private void updateButtonText() {
        String currentPos = I18n.format("gui.supermobtracker.hudPosition." + ModConfig.getClientHudPosition().name().toLowerCase());
        this.btnValue.displayString = I18n.format("gui.supermobtracker.hudPosition.button", currentPos);
    }

    @Override
    public void valueButtonPressed(int slotIndex) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiHudPositionSelector(this.owningScreen));
    }

    @Override
    public void updateValueButtonText() {
        updateButtonText();
    }

    @Override
    public boolean isDefault() {
        return ModConfig.getClientHudPosition() == ModConfig.HudPosition.TOP_LEFT;
    }

    @Override
    public void setToDefault() {
        ModConfig.setClientHudPosition(ModConfig.HudPosition.TOP_LEFT);
        updateButtonText();
    }

    @Override
    public boolean isChanged() {
        // This entry doesn't track changes the usual way since it opens a sub-GUI
        return false;
    }

    @Override
    public void undoChanges() {
        // Nothing to undo since changes are saved immediately in the sub-GUI
    }

    @Override
    public boolean saveConfigElement() {
        // Changes are saved immediately in the sub-GUI
        return false;
    }

    @Override
    public Object getCurrentValue() {
        return ModConfig.getClientHudPosition().name();
    }

    @Override
    public Object[] getCurrentValues() {
        return new Object[] { getCurrentValue() };
    }
}
