package com.supermobtracker.client.gui.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;

import com.supermobtracker.integration.jei.JEIHelper;


public class GuiHintItemArgument extends GuiHintArgument {
    private final Minecraft minecraft;
    private final ItemStack stack;

    public GuiHintItemArgument(Minecraft minecraft, ItemStack stack) {
        super(formatName(stack));
        this.minecraft = minecraft;
        this.stack = stack;
    }

    @Override
    public int getColor() {
        return 0x55AAFF;
    }

    @Override
    public int getHoverColor() {
        return 0x88CCFF;
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
            tooltip.add(I18n.format("gui.mobtracker.hint.jei"));
        }

        return tooltip;
    }

    private static String formatName(ItemStack stack) {
        String name = stack.getDisplayName();
        return stack.getCount() > 1 ? stack.getCount() + "x " + name : name;
    }
}
