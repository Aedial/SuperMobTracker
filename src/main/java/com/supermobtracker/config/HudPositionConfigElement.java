package com.supermobtracker.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiEditArrayEntries;
import net.minecraftforge.fml.client.config.IConfigElement;

/**
 * A dummy IConfigElement that creates a HudPositionConfigEntry in the config GUI.
 */
public class HudPositionConfigElement implements IConfigElement {

    @Override
    public boolean isProperty() {
        return true;
    }

    @Override
    public Class<? extends GuiConfigEntries.IConfigEntry> getConfigEntryClass() {
        return HudPositionConfigEntry.class;
    }

    @Override
    public Class<? extends GuiEditArrayEntries.IArrayEntry> getArrayEntryClass() {
        return null;
    }

    @Override
    public String getName() {
        return "hudPosition";
    }

    @Override
    public String getQualifiedName() {
        return "client.hudPosition";
    }

    @Override
    public String getLanguageKey() {
        return "gui.supermobtracker.hudPosition.label";
    }

    @Override
    public String getComment() {
        return "Position of the tracking overlay on screen.";
    }

    @Override
    public List<IConfigElement> getChildElements() {
        return new ArrayList<>();
    }

    @Override
    public ConfigGuiType getType() {
        return ConfigGuiType.STRING;
    }

    @Override
    public boolean isList() {
        return false;
    }

    @Override
    public boolean isListLengthFixed() {
        return false;
    }

    @Override
    public int getMaxListLength() {
        return -1;
    }

    @Override
    public boolean isDefault() {
        return ModConfig.getClientHudPosition() == ModConfig.HudPosition.TOP_LEFT;
    }

    @Override
    public Object getDefault() {
        return ModConfig.HudPosition.TOP_LEFT.name();
    }

    @Override
    public Object[] getDefaults() {
        return new Object[] { getDefault() };
    }

    @Override
    public void setToDefault() {
        ModConfig.setClientHudPosition(ModConfig.HudPosition.TOP_LEFT);
    }

    @Override
    public boolean requiresWorldRestart() {
        return false;
    }

    @Override
    public boolean showInGui() {
        return true;
    }

    @Override
    public boolean requiresMcRestart() {
        return false;
    }

    @Override
    public Object get() {
        return ModConfig.getClientHudPosition().name();
    }

    @Override
    public Object[] getList() {
        return new Object[] { get() };
    }

    @Override
    public void set(Object value) {
        if (value instanceof String) {
            try {
                ModConfig.setClientHudPosition(ModConfig.HudPosition.valueOf((String) value));
            } catch (IllegalArgumentException e) {
                // Ignore invalid values
            }
        }
    }

    @Override
    public void set(Object[] aVal) {
        if (aVal != null && aVal.length > 0) {
            set(aVal[0]);
        }
    }

    @Override
    public String[] getValidValues() {
        ModConfig.HudPosition[] positions = ModConfig.HudPosition.values();
        String[] result = new String[positions.length];
        for (int i = 0; i < positions.length; i++) result[i] = positions[i].name();

        return result;
    }

    @Override
    public Object getMinValue() {
        return null;
    }

    @Override
    public Object getMaxValue() {
        return null;
    }

    @Override
    public Pattern getValidationPattern() {
        return null;
    }
}
