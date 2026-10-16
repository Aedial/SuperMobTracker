package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.supermobtracker.Tags;


public final class GuiHintTooltipRenderer {
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_TEXTURE_SIZE = 32;
    private static final int SPACES = 3;
    private static final int TEXT_Y_START = SPACES * 10 + 2;  // 10px/space + 2px 1st line gap
    private static final ResourceLocation SLOT_TEXTURE = new ResourceLocation(Tags.MODID,
        "textures/gui/slot.png");
    private static final GuiHintTooltipRenderer INSTANCE = new GuiHintTooltipRenderer();

    private ItemStack activeStack = ItemStack.EMPTY;

    private GuiHintTooltipRenderer() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    public static List<String> addIconHeader(List<String> tooltip, FontRenderer fontRenderer) {
        List<String> lines = new ArrayList<>(tooltip.size() + SPACES);
        // reserve enough vertical *and* horizontal space for the icon header
        lines.add(createSpacer(fontRenderer));
        for (int i = 0; i < SPACES - 1; i++) lines.add("");
        lines.addAll(tooltip);
        return lines;
    }

    public static void beginIconTooltip(ItemStack stack) {
        INSTANCE.activeStack = stack;
    }

    public static void endIconTooltip() {
        INSTANCE.activeStack = ItemStack.EMPTY;
    }

    @SubscribeEvent
    public void onTooltipBackground(RenderTooltipEvent.PostBackground event) {
        if (activeStack.isEmpty() || event.getStack() != activeStack) return;

        int slotX = event.getX() + (event.getWidth() - SLOT_SIZE) / 2;
        int slotY = event.getY() + (TEXT_Y_START - SLOT_SIZE) / 2;
        Minecraft minecraft = Minecraft.getMinecraft();

        minecraft.getTextureManager().bindTexture(SLOT_TEXTURE);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        Gui.drawModalRectWithCustomSizedTexture(slotX, slotY, 0.0F, 0.0F, SLOT_SIZE, SLOT_SIZE,
            SLOT_TEXTURE_SIZE, SLOT_TEXTURE_SIZE);

        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        minecraft.getRenderItem().renderItemAndEffectIntoGUI(activeStack, slotX + 1, slotY + 1);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static String createSpacer(FontRenderer fontRenderer) {
        int spaceWidth = fontRenderer.getStringWidth(" ");
        int spaces = (SLOT_SIZE + spaceWidth - 1) / spaceWidth;
        return String.format("%" + spaces + "s", "");
    }
}
