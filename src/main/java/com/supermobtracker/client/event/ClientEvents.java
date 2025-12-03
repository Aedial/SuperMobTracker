package com.supermobtracker.client.event;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.client.input.KeyBindings;
import com.supermobtracker.client.gui.GuiIconButton;
import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.config.GuiHudPositionSelector;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.config.ModConfig.HudPosition;
import com.supermobtracker.tracking.SpawnTrackerManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class ClientEvents {

    private static final int BUTTON_ID = 9001;
    private static final int BUTTON_SIZE = 8;

    // Cached inventory tracker button bounds for tooltip rendering
    private static int trackerX = -1;
    private static int trackerY = -1;
    private static int trackerW = 0;
    private static int trackerH = 0;

    // Reflection fields for accessing guiLeft/guiTop
    private static Field guiLeftField;
    private static Field guiTopField;

    static {
        try {
            // Try to find guiLeft and guiTop fields (they may have different names due to obfuscation)
            for (Field field : GuiContainer.class.getDeclaredFields()) {
                if (field.getType() == int.class) field.setAccessible(true);
            }

            guiLeftField = GuiContainer.class.getDeclaredField("guiLeft");
            guiTopField = GuiContainer.class.getDeclaredField("guiTop");
            guiLeftField.setAccessible(true);
            guiTopField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // Try obfuscated names
            try {
                guiLeftField = GuiContainer.class.getDeclaredField("field_147003_i");
                guiTopField = GuiContainer.class.getDeclaredField("field_147009_r");
                guiLeftField.setAccessible(true);
                guiTopField.setAccessible(true);
            } catch (NoSuchFieldException e2) {
                // Leave as null, will use fallback positioning
            }
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        KeyBindings.onClientTick();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;

        for (Entity entity : mc.world.loadedEntityList) {
            if (SpawnTrackerManager.isTracked(entity) && mc.player.getDistanceSq(entity) <= ModConfig.clientDetectionRange * ModConfig.clientDetectionRange) {
                if (!entity.isGlowing()) {
                    entity.setGlowing(true);
                    entity.getEntityData().setBoolean("smt_temp_glow", true);
                }
            } else {
                if (entity.getEntityData().getBoolean("smt_temp_glow")) {
                    entity.setGlowing(false);
                    entity.getEntityData().removeTag("smt_temp_glow");
                }
            }
        }
    }

    @SubscribeEvent
    public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.getGui() instanceof GuiInventory) && !(event.getGui() instanceof GuiContainerCreative)) return;

        int guiLeft = 0;
        int guiTop = 0;

        // Get the GUI position using reflection
        try {
            if (guiLeftField != null && guiTopField != null) {
                guiLeft = guiLeftField.getInt(event.getGui());
                guiTop = guiTopField.getInt(event.getGui());
            }
        } catch (IllegalAccessException e) {
            // Use fallback: center of screen minus half standard inventory size
            guiLeft = (event.getGui().width - 176) / 2;
            guiTop = (event.getGui().height - 166) / 2;
        }

        // Starting position: top of the inventory GUI, right of the player preview
        int startX = guiLeft + 78;
        int startY = guiTop + 8;

        // For creative inventory, the layout is different, put it right to the search box
        if (event.getGui() instanceof GuiContainerCreative) {
            startX = guiLeft + 185 - BUTTON_SIZE;
            startY = guiTop + 6;
        }

        // Find a non-colliding position
        int[] position = findNonCollidingPosition(startX, startY, BUTTON_SIZE, BUTTON_SIZE, event.getButtonList(), guiLeft, guiTop);

        if (position != null) {
            int bx = position[0];
            int by = position[1];

            event.getButtonList().add(new GuiIconButton(BUTTON_ID, bx, by, BUTTON_SIZE, BUTTON_SIZE,
                    new ResourceLocation("supermobtracker", "textures/gui/radar.png")));

            // Include button frame in tracker bounds
            trackerX = bx - 1;
            trackerY = by - 1;
            trackerW = BUTTON_SIZE + 2;
            trackerH = BUTTON_SIZE + 2;
        } else {
            // Reset tracker bounds if no valid position found
            trackerX = -1;
            trackerY = -1;
            trackerW = 0;
            trackerH = 0;
        }
    }

    /**
     * Finds a non-colliding position for a button, starting from the given position.
     * Tries moving down until reaching the off-hand slot area, then moves right and tries down again.
     *
     * @return int[] with {x, y} or null if no valid position found
     */
    private int[] findNonCollidingPosition(int startX, int startY, int btnW, int btnH, List<GuiButton> buttons, int guiLeft, int guiTop) {
        // Off-hand slot Y position in survival inventory is around guiTop + 62
        int offhandSlotY = guiTop + 62;

        int x = startX;
        int y = startY;

        // As keeping the positions of slots within the inventory GUI is annoying, only try one column
        for (int col = 0; col < 1; col++) {
            // Try moving down in this column until we hit the off-hand slot area
            while (y + btnH < offhandSlotY) {
                if (!collidesWithAnyButton(x, y, btnW, btnH, buttons)) return new int[] { x, y };

                // Move down
                y += btnH + 3;
            }

            x += btnW + 3;
            y = startY;
        }

        return null; // No valid position found
    }

    /**
     * Checks if a rectangle collides with any existing button.
     */
    private boolean collidesWithAnyButton(int x, int y, int w, int h, List<GuiButton> buttons) {
        for (GuiButton btn : buttons) {
            if (btn.id == BUTTON_ID) continue; // Skip our own button

            if (rectsOverlap(x, y, w, h, btn.x, btn.y, btn.width, btn.height)) return true;
        }

        return false;
    }

    /**
     * Checks if two rectangles overlap.
     */
    private boolean rectsOverlap(int x1, int y1, int w1, int h1, int x2, int y2, int w2, int h2) {
        return x1 < x2 + w2 && x1 + w1 > x2 && y1 < y2 + h2 && y1 + h1 > y2;
    }

    @SubscribeEvent
    public void onGuiButton(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.getButton().id == BUTTON_ID) Minecraft.getMinecraft().displayGuiScreen(new GuiMobTracker());
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!ModConfig.isClientHudEnabled()) return;
        if (SpawnTrackerManager.getTrackedIds().isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.showDebugInfo) return;  // skip when F3 is shown

        double rangeSq = ModConfig.clientDetectionRange * ModConfig.clientDetectionRange;

        // Build class -> id map for O(n) single pass counting. Exact class match assumed.
        Map<Class<?>, ResourceLocation> classToId = new HashMap<>();
        for (ResourceLocation id : SpawnTrackerManager.getTrackedIds()) {
            EntityEntry entry = ForgeRegistries.ENTITIES.getValue(id);
            if (entry != null) classToId.put(entry.getEntityClass(), id);
        }

        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (ResourceLocation id : SpawnTrackerManager.getTrackedIds()) counts.put(id, 0);

        for (Entity e : mc.world.loadedEntityList) {
            if (mc.player.getDistanceSq(e) > rangeSq) continue;

            ResourceLocation id = classToId.get(e.getClass());
            if (id != null) counts.put(id, counts.get(id) + 1);
        }

        // Build display strings
        List<String> lines = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> entry : counts.entrySet()) {
            ResourceLocation id = entry.getKey();

            String name = id.toString();
            if (ModConfig.clientI18nNames) {
                Entity entity = EntityList.createEntityByIDFromName(id, mc.world);
                if (entity != null) name = entity.getDisplayName().getUnformattedText();
            }

            lines.add(name + ": " + entry.getValue());
        }

        if (lines.isEmpty()) return;

        // Get config values
        HudPosition position = ModConfig.getClientHudPosition();
        int paddingExternal = ModConfig.getClientHudPaddingExternal();
        int paddingInternal = ModConfig.getClientHudPaddingInternal();
        int lineSpacing = ModConfig.getClientHudLineSpacing();

        // Calculate dimensions
        int lineHeight = mc.fontRenderer.FONT_HEIGHT;
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, mc.fontRenderer.getStringWidth(line));
        }

        int boxW = maxWidth + paddingInternal * 2;
        int boxH = lines.size() * lineHeight + (lines.size() - 1) * lineSpacing + paddingInternal * 2;

        // Calculate position based on HudPosition
        ScaledResolution res = new ScaledResolution(mc);
        int screenW = res.getScaledWidth();
        int screenH = res.getScaledHeight();

        int boxX, boxY;
        switch (position) {
            case TOP_LEFT:
                boxX = paddingExternal;
                boxY = paddingExternal;
                break;
            case TOP_CENTER:
                boxX = (screenW - boxW) / 2;
                boxY = paddingExternal;
                break;
            case TOP_RIGHT:
                boxX = screenW - boxW - paddingExternal;
                boxY = paddingExternal;
                break;
            case CENTER_LEFT:
                boxX = paddingExternal;
                boxY = (screenH - boxH) / 2;
                break;
            case CENTER:
                boxX = (screenW - boxW) / 2;
                boxY = (screenH - boxH) / 2;
                break;
            case CENTER_RIGHT:
                boxX = screenW - boxW - paddingExternal;
                boxY = (screenH - boxH) / 2;
                break;
            case BOTTOM_LEFT:
                boxX = paddingExternal;
                boxY = screenH - boxH - paddingExternal;
                break;
            case BOTTOM_CENTER:
                boxX = (screenW - boxW) / 2;
                boxY = screenH - boxH - paddingExternal;
                break;
            case BOTTOM_RIGHT:
                boxX = screenW - boxW - paddingExternal;
                boxY = screenH - boxH - paddingExternal;
                break;
            default:
                boxX = paddingExternal;
                boxY = paddingExternal;
        }

        // Draw box with border
        int bgColor = 0xC0101010;
        int borderColor = 0xFF404040;

        // Border (1px)
        Gui.drawRect(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + boxH + 1, borderColor);
        // Background
        Gui.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, bgColor);

        // Draw text
        int textX = boxX + paddingInternal;
        int textY = boxY + paddingInternal;
        for (int i = 0; i < lines.size(); i++) {
            mc.fontRenderer.drawString(lines.get(i), textX, textY, 0xFFFFFF);
            textY += lineHeight + lineSpacing;
        }
    }

    @SubscribeEvent
    public void onGuiDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        // FIXME: tooltip not showing correctly in creative inventory
        // Note: The tooltip may not display correctly in GuiContainerCreative due to its
        // custom rendering pipeline with tabs. The button itself works, but the hovering
        // text may be occluded by the creative inventory's tab rendering which happens
        // after the DrawScreenEvent.Post fires.
        if (event.getGui() instanceof GuiInventory || event.getGui() instanceof GuiContainerCreative) {
            int mx = event.getMouseX();
            int my = event.getMouseY();
            GuiScreen screen = (GuiScreen) event.getGui();
            if (trackerX >= 0 && mx >= trackerX && mx <= trackerX + trackerW && my >= trackerY && my <= trackerY + trackerH) {
                screen.drawHoveringText(Arrays.asList(I18n.format("key.categories.supermobtracker")), mx, my);
            }
        }
    }
}
