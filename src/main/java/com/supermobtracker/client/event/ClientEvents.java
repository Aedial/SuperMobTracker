package com.supermobtracker.client.event;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.client.input.KeyBindings;
import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.tracking.SpawnTrackerManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class ClientEvents {

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
        if (event.getGui() instanceof GuiInventory) {
            event.getButtonList().add(new GuiButton(9001, event.getGui().width - 60, 5, 55, 20, "Tracker"));
        }
    }

    @SubscribeEvent
    public void onGuiButton(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.getButton().id == 9001) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiMobTracker());
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (SpawnTrackerManager.getTrackedIds().isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;

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

        // Add background box for better readability
        // TODO: box is kinda ugly and lines do not align with it
        // TODO: we need a configutable HUD system for proper placement, to avoid conflicts with other mods
        if (!lines.isEmpty()) {
            int maxWidth = 0;
            for (String line : lines) {
                maxWidth = Math.max(maxWidth, mc.fontRenderer.getStringWidth(line));
            }

            int boxX = 2;
            int boxY = 2;
            int boxW = maxWidth + 6;
            int boxH = lines.size() * 10 + 4;

            Gui.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, 0x80000000);
        }

        event.getLeft().addAll(lines);
    }
}
