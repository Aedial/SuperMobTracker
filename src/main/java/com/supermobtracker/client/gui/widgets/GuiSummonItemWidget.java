package com.supermobtracker.client.gui.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;

import com.supermobtracker.client.gui.GuiScrollableTextWidget;
import com.supermobtracker.integration.jei.JEIHelper;


public class GuiSummonItemWidget extends GuiScrollableTextWidget {
    private final Minecraft minecraft;
    private final ItemStack stack;

    public GuiSummonItemWidget(FontRenderer fontRenderer, String text, Layout layout, int horizontalOffset,
            int widthOffset, Minecraft minecraft, ItemStack stack) {
        super(fontRenderer, text, 0xFFDD55, 0xFFFF55, 12, 0, layout, horizontalOffset, widthOffset);
        this.minecraft = minecraft;
        this.stack = stack;
    }

    @Override
    public boolean onClick(int mouseButton) {
        if (mouseButton == 0) {
            JEIHelper.showItemRecipes(stack);
            return true;
        }

        if (mouseButton == 1) {
            JEIHelper.showItemUses(stack);
            return true;
        }

        return false;
    }

    @Override
    public List<String> getTooltip() {
        List<String> tooltip = new ArrayList<>();
        ITooltipFlag.TooltipFlags tooltipFlags = minecraft.gameSettings.advancedItemTooltips
            ? ITooltipFlag.TooltipFlags.ADVANCED : ITooltipFlag.TooltipFlags.NORMAL;

        tooltip.addAll(stack.getTooltip(minecraft.player, tooltipFlags));
        if (JEIHelper.isJEILoaded()) {
            tooltip.add("");
            tooltip.add(I18n.format("gui.mobtracker.summon.jei"));
        }

        return tooltip;
    }
}
