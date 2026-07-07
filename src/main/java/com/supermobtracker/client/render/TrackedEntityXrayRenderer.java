package com.supermobtracker.client.render;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.tracking.SpawnTrackerManager;


/**
 * Draws tracked entities through world geometry with their normal texture.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@SideOnly(Side.CLIENT)
public class TrackedEntityXrayRenderer {
    private static final int PASS_STENCIL = 0;
    private static final int PASS_DEPTH = 1;
    private static final int PASS_COLOR = 2;

    private static Field shadowSizeField;
    private static boolean shadowFieldInitialized;

    private final Set<String> renderErrorIds = new HashSet<>();
    private int stencilMask = -1;
    private boolean renderingXrayPass;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (renderingXrayPass) return;
        if (!ModConfig.isClientUseModelXRay()) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc.getRenderViewEntity();
        if (mc.world == null || mc.player == null || viewer == null) return;
        if (SpawnTrackerManager.getTrackedIds().isEmpty()) return;

        float partialTicks = event.getPartialTicks();
        double viewerX = interpolate(viewer.lastTickPosX, viewer.posX, partialTicks);
        double viewerY = interpolate(viewer.lastTickPosY, viewer.posY, partialTicks);
        double viewerZ = interpolate(viewer.lastTickPosZ, viewer.posZ, partialTicks);
        List<XrayRenderEntry> entries = collectRenderEntries(mc, viewer, partialTicks, viewerX, viewerY, viewerZ);
        if (entries.isEmpty() || !ensureStencilBuffer(mc)) return;

        float lastBrightnessX = OpenGlHelper.lastBrightnessX;
        float lastBrightnessY = OpenGlHelper.lastBrightnessY;
        renderingXrayPass = true;
        try {
            renderXray(entries);
        } finally {
            restoreXrayState(lastBrightnessX, lastBrightnessY);
            renderingXrayPass = false;
        }
    }

    private List<XrayRenderEntry> collectRenderEntries(Minecraft mc, Entity viewer, float partialTicks,
                                                       double viewerX, double viewerY, double viewerZ) {
        List<XrayRenderEntry> entries = new ArrayList<>();

        for (Entity entity : mc.world.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;

            EntityLivingBase living = (EntityLivingBase) entity;
            ResourceLocation entityId = getRenderableTrackedEntityId(mc, viewer, living);
            if (entityId == null) continue;

            Render renderer = mc.getRenderManager().getEntityRenderObject(living);
            if (!(renderer instanceof RenderLivingBase)) continue;

            double x = interpolate(living.lastTickPosX, living.posX, partialTicks) - viewerX;
            double y = interpolate(living.lastTickPosY, living.posY, partialTicks) - viewerY;
            double z = interpolate(living.lastTickPosZ, living.posZ, partialTicks) - viewerZ;
            entries.add(new XrayRenderEntry(entityId, living, (RenderLivingBase) renderer, x, y, z, living.rotationYaw, partialTicks));
        }

        return entries;
    }

    private ResourceLocation getRenderableTrackedEntityId(Minecraft mc, Entity viewer, Entity entity) {
        if (entity == viewer || entity.isDead) return null;
        if (!SpawnTrackerManager.isTracked(entity)) return null;

        double range = ModConfig.clientDetectionRange;
        if (mc.player.getDistanceSq(entity) > range * range) return null;

        ResourceLocation entityId = EntityList.getKey(entity);
        if (entityId == null) return null;

        String idString = entityId.toString();
        if (!ModConfig.isEntityAllowed(idString)) return null;
        if (ModConfig.shouldRenderEntity(idString)) return null;
        if (renderErrorIds.contains(idString)) return null;

        return entityId;
    }

    private void renderXray(List<XrayRenderEntry> entries) {
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(stencilMask);
        GL11.glClearStencil(0);
        GlStateManager.clear(GL11.GL_STENCIL_BUFFER_BIT);

        try {
            renderPass(entries, PASS_STENCIL);

            GlStateManager.clearDepth(1.0D);
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            renderPass(entries, PASS_DEPTH);

            renderPass(entries, PASS_COLOR);
        } finally {
            GlStateManager.colorMask(true, true, true, true);
            GL11.glStencilMask(0xFF);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
    }

    private void renderPass(List<XrayRenderEntry> entries, int pass) {
        for (XrayRenderEntry entry : entries) {
            if (renderErrorIds.contains(entry.entityId.toString())) continue;

            preparePass(pass);
            try {
                renderEntry(entry);
            } catch (Throwable t) {
                String idString = entry.entityId.toString();
                if (renderErrorIds.add(idString)) {
                    SuperMobTracker.LOGGER.warn("Disabled xray rendering for {} after render failure: {}", idString, t.getMessage());
                }
            }
        }
    }

    private void preparePass(int pass) {
        if (pass == PASS_STENCIL) {
            GL11.glStencilMask(stencilMask);
            GL11.glStencilFunc(GL11.GL_ALWAYS, stencilMask, stencilMask);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
            prepareXrayState(false, false, GL11.GL_GREATER);
            return;
        }

        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, stencilMask, stencilMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        prepareXrayState(pass == PASS_COLOR, pass == PASS_DEPTH, pass == PASS_DEPTH ? GL11.GL_LEQUAL : GL11.GL_EQUAL);
    }

    private static void renderEntry(XrayRenderEntry entry) throws IllegalAccessException {
        Float originalShadowSize = suppressShadow(entry.renderer);
        try {
            entry.renderer.doRender(entry.entity, entry.x, entry.y, entry.z, entry.yaw, entry.partialTicks);
        } finally {
            restoreShadow(entry.renderer, originalShadowSize);
        }
    }

    private boolean ensureStencilBuffer(Minecraft minecraft) {
        Framebuffer framebuffer = minecraft.getFramebuffer();
        if (framebuffer == null || !framebuffer.isStencilEnabled() && !framebuffer.enableStencil()) {
            return false;
        }

        if (stencilMask < 0) {
            int stencilBit = MinecraftForgeClient.reserveStencilBit();
            if (stencilBit < 0) return false;
            stencilMask = 1 << stencilBit;
        }

        return true;
    }

    private static void prepareXrayState(boolean writeColor, boolean writeDepth, int depthFunc) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(depthFunc);
        GlStateManager.depthMask(writeDepth);
        GlStateManager.colorMask(writeColor, writeColor, writeColor, writeColor);
        GlStateManager.disableLighting();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void restoreXrayState(float lastBrightnessX, float lastBrightnessY) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBrightnessX, lastBrightnessY);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);

        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static Float suppressShadow(RenderLivingBase renderer) throws IllegalAccessException {
        if (!shadowFieldInitialized) initShadowField();
        if (shadowSizeField == null) return null;

        float original = shadowSizeField.getFloat(renderer);
        shadowSizeField.setFloat(renderer, 0.0F);
        return original;
    }

    private static void restoreShadow(RenderLivingBase renderer, Float originalShadowSize) {
        if (shadowSizeField == null || originalShadowSize == null) return;

        try {
            shadowSizeField.setFloat(renderer, originalShadowSize);
        } catch (IllegalAccessException ignored) {
            // Rendering can continue; the field is restored on the next renderer instance.
        }
    }

    private static void initShadowField() {
        shadowFieldInitialized = true;

        try {
            shadowSizeField = Render.class.getDeclaredField("shadowSize");
            shadowSizeField.setAccessible(true);
            return;
        } catch (NoSuchFieldException ignored) {
            // Try obfuscated name below.
        }

        try {
            shadowSizeField = Render.class.getDeclaredField("field_76989_e");
            shadowSizeField.setAccessible(true);
        } catch (NoSuchFieldException ignored) {
            shadowSizeField = null;
        }
    }

    private static double interpolate(double previous, double current, float partialTicks) {
        return previous + (current - previous) * partialTicks;
    }

    private static class XrayRenderEntry {
        private final ResourceLocation entityId;
        private final EntityLivingBase entity;
        private final RenderLivingBase renderer;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float partialTicks;

        private XrayRenderEntry(ResourceLocation entityId, EntityLivingBase entity, RenderLivingBase renderer,
                                double x, double y, double z, float yaw, float partialTicks) {
            this.entityId = entityId;
            this.entity = entity;
            this.renderer = renderer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.partialTicks = partialTicks;
        }
    }
}
