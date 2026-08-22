package com.supermobtracker.client.gui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;


/**
 * A vanilla button whose lower edge remains visible when its height is less
 * than the 20-pixel height of the vanilla button texture.
 */
public class SmallVanillaButton extends GuiButton {

    /** Provides the tooltip text dynamically, incompatible with static tooltip */
    private Supplier<String> tooltipProvider;
    /** Static tooltip text, incompatible with dynamic tooltip */
    private String staticTooltip;

    /**
     * Create a small vanilla button with a given size, position, and text.
     */
    public SmallVanillaButton(int buttonId, int x, int y, int width, int height, String text) {
        super(buttonId, x, y, width, height, text);
    }

    /**
     * Create a small square vanilla button with a given size, position, and text.
     */
    public SmallVanillaButton(int buttonId, int x, int y, int size, String text) {
        super(buttonId, x, y, size, size, text);
    }

    /**
     * Create a small square vanilla button with a given size and text, positioned at (0, 0).
     */
    public SmallVanillaButton(int buttonId, int size, String text) {
        super(buttonId, 0, 0, size, size, text);
    }

    @Nullable
    public String getTooltip() {
        if (tooltipProvider != null) {
            return tooltipProvider.get();
        } else {
            return staticTooltip;
        }
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!visible) return;

        FontRenderer fontRenderer = mc.fontRenderer;
        mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        int hoverState = getHoverState(hovered);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO);
        GlStateManager.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        int leftWidth = width / 2;
        int rightWidth = width - leftWidth;
        int bottomHeight = Math.min(2, height);
        int middleHeight = height - bottomHeight;
        int textureY = 46 + hoverState * 20;

        drawTexturedModalRect(x, y, 0, textureY, leftWidth, middleHeight);
        drawTexturedModalRect(x + leftWidth, y, 200 - rightWidth, textureY, rightWidth, middleHeight);
        drawTexturedModalRect(x, y + middleHeight, 0, textureY + 20 - bottomHeight, leftWidth, bottomHeight);
        drawTexturedModalRect(
            x + leftWidth,
            y + middleHeight,
            200 - rightWidth,
            textureY + 20 - bottomHeight,
            rightWidth,
            bottomHeight);

        mouseDragged(mc, mouseX, mouseY);

        int textColor = 0xE0E0E0;
        if (packedFGColour != 0) {
            textColor = packedFGColour;
        } else if (!enabled) {
            textColor = 0xA0A0A0;
        } else if (hovered) {
            textColor = 0xFFFFA0;
        }

        drawCenteredString(fontRenderer, displayString, x + width / 2, y + (height - 8) / 2, textColor);
    }

    /**
     * Sets the tooltip provider for this button. The provider will be called
     * each time the tooltip is requested, allowing for dynamic tooltips.
     * For static tooltips, use {@link #setStaticTooltip(String)} instead.
     */
    public void setTooltipProvider(Supplier<String> tooltipProvider) {
        this.tooltipProvider = tooltipProvider;
    }

    /**
     * Sets a static tooltip for this button. This tooltip will not change
     * dynamically. For dynamic tooltips, use {@link #setTooltipProvider(Supplier)} instead.
     */
    public void setStaticTooltip(String staticTooltip) {
        this.staticTooltip = staticTooltip;
    }

    public List<String> getTooltipHovered() {
        if (!visible || !hovered) return Collections.emptyList();

        String tooltip = getTooltip();
        return tooltip != null ? Arrays.asList(tooltip.split("\n")) : Collections.emptyList();
    }

    public boolean drawTooltip(GuiScreen gui, int mouseX, int mouseY) {
        if (hovered && getTooltip() != null) {
            List<String> tooltipLines = Arrays.asList(getTooltip().split("\n"));
            gui.drawHoveringText(tooltipLines, mouseX, mouseY);
            return true;
        }

        return false;
    }
}