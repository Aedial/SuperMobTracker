package com.supermobtracker.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

public class GuiIconButton extends GuiButton {
    private static final int TEXTURE_SIZE = 32;

    private final ResourceLocation icon;

    public GuiIconButton(int buttonId, int x, int y, int widthIn, int heightIn, ResourceLocation icon) {
        super(buttonId, x, y, widthIn, heightIn, "");
        this.icon = icon;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) return;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        // Draw frame and background of the button
        boolean hovered = mouseX >= this.x - 1 && mouseY >= this.y - 1 && mouseX < this.x + this.width + 1 && mouseY < this.y + this.height + 1;
        int color = hovered ? 0xFFBBBBBB : 0xFFCCCCCC;
        int borderColor = 0xFF333333;
        this.drawRect(this.x, this.y - 1, this.x + this.width, this.y, borderColor); // Top border
        this.drawRect(this.x, this.y + this.height, this.x + this.width, this.y + this.height + 1, borderColor); // Bottom border
        this.drawRect(this.x - 1, this.y, this.x, this.y + this.height, borderColor); // Left border
        this.drawRect(this.x + this.width, this.y, this.x + this.width + 1, this.y + this.height, borderColor); // Right border
        this.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, color);

        // Draw the icon scaled from texture size to button size
        mc.getTextureManager().bindTexture(icon);
        GlStateManager.color(1f, 1f, 1f, 1f);
        this.drawScaledCustomSizeModalRect(this.x, this.y, 0, 0, 16, 16, this.width, this.height, 16, 16);

        GlStateManager.disableBlend();
    }
}

