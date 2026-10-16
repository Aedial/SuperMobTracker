package com.supermobtracker.client.gui.widgets;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;


public class GuiHintBlockArgument extends GuiHintArgument {
    private final GuiHintItemArgument itemArgument;
    private final int textColor;

    public GuiHintBlockArgument(Minecraft minecraft, String text, ResourceLocation blockId, int textColor) {
        super(text);
        ItemStack stack = getItemStack(blockId);
        this.itemArgument = stack.isEmpty() ? null : new GuiHintItemArgument(minecraft, stack);
        this.textColor = textColor;
    }

    @Override
    public int getColor() {
        return itemArgument == null ? textColor : itemArgument.getColor();
    }

    @Override
    public int getHoverColor() {
        return itemArgument == null ? textColor : itemArgument.getHoverColor();
    }

    @Override
    public boolean onClick(int mouseButton) {
        return itemArgument != null && itemArgument.onClick(mouseButton);
    }

    @Override
    public List<String> getTooltip() {
        return itemArgument == null ? Collections.emptyList() : itemArgument.getTooltip();
    }

    @Override
    public ItemStack getTooltipStack() {
        return itemArgument == null ? ItemStack.EMPTY : itemArgument.getTooltipStack();
    }

    private static ItemStack getItemStack(ResourceLocation blockId) {
        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        if (block == null) return ItemStack.EMPTY;

        Item item = Item.getItemFromBlock(block);
        if (item == null) return ItemStack.EMPTY;

        return new ItemStack(item);
    }
}
