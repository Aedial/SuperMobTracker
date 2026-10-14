package com.supermobtracker.client.gui.widgets;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.client.gui.GuiScrollableWidget;
import com.supermobtracker.client.util.GuiDrawingUtils;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.tracking.SpawnTrackerManager;


public class GuiMobListEntryWidget extends GuiScrollableWidget {
    private final FontRenderer fontRenderer;
    private final GuiMobTracker tracker;
    private final ResourceLocation id;
    private final String displayName;
    public GuiMobListEntryWidget(FontRenderer fontRenderer, GuiMobTracker tracker, ResourceLocation id,
            String displayName) {
        this.fontRenderer = fontRenderer;
        this.tracker = tracker;
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    public int getHeight(int width) {
        return 12;
    }

    @Override
    public void draw(int x, int y, int width, int remainingYSpace, boolean hovered) {
        boolean selected = id.equals(tracker.getSelectedEntity());
        if (selected) Gui.drawRect(x, y, x + width, y + 12, 0x40FFFFFF);

        int maxWidth = width - 4;
        String elided = this.fontRenderer.trimStringToWidth(displayName,
            maxWidth - this.fontRenderer.getStringWidth("..."));
        if (!elided.equals(displayName)) elided += "...";
        this.fontRenderer.drawString(elided, x + 4, y + 2, selected ? 0xFFFFA0 : 0xFFFFFF);

        float iconCenterX = x + width - 10;
        float iconCenterY = y + 6;
        float iconRadius = 4.0f;
        boolean tracked = SpawnTrackerManager.isTracked(id);
        ModConfig.FilterReason reason = ModConfig.getFilterReason(id.toString());

        if (tracked) {
            GuiDrawingUtils.drawStar(iconCenterX, iconCenterY, iconRadius, 0xFFFFD700);
            if (reason != ModConfig.FilterReason.NONE) {
                // Draw red X over the star
                float bx1 = iconCenterX - iconRadius * 0.7f;
                float by1 = iconCenterY - iconRadius * 0.7f;
                float bx2 = iconCenterX + iconRadius * 0.7f;
                float by2 = iconCenterY + iconRadius * 0.7f;
                GuiDrawingUtils.drawRedX(bx1, by1, bx2, by2, 3.0f, 0xFFFF4444);
            }
        } else if (reason != ModConfig.FilterReason.NONE) {
            // Draw stop sign for filtered entities
            GuiDrawingUtils.drawStopSign(iconCenterX, iconCenterY, iconRadius + 0.5f, 0xE0FF4444);
        }
    }

    @Override
    public boolean onClick(int mouseButton) {
        if (mouseButton != 0) return false;

        tracker.handleMobListSelection(id);
        return true;
    }

    @Override
    public List<String> getTooltip() {
        ModConfig.FilterReason reason = ModConfig.getFilterReason(id.toString());
        if (reason == ModConfig.FilterReason.NONE) return Collections.emptyList();

        // Tooltip for stop sign or red X
        String tooltip = reason == ModConfig.FilterReason.BLACKLISTED
            ? I18n.format("gui.mobtracker.blacklisted")
            : I18n.format("gui.mobtracker.whitelisted");
        return Collections.singletonList(tooltip);
    }
}
