package com.supermobtracker.client.gui.widgets;

import java.util.List;

import net.minecraft.item.ItemStack;


public abstract class GuiHintArgument {
    private final String text;

    protected GuiHintArgument(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public abstract int getColor();

    public abstract int getHoverColor();

    public abstract boolean onClick(int mouseButton);

    public abstract List<String> getTooltip();

    public ItemStack getTooltipStack() {
        return ItemStack.EMPTY;
    }
}
