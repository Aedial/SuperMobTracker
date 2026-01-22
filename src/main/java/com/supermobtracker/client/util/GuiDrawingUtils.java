package com.supermobtracker.client.util;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.supermobtracker.SuperMobTracker;


/**
 * Utility class for drawing shapes and entity previews in GUIs.
 */
public final class GuiDrawingUtils {

    private static final int ENTITY_BG_COLOR = 0xFF404040;
    private static final int ENTITY_BORDER_COLOR = 0xFF808080;

    // Track entities that have already had render errors reported
    private static final Set<ResourceLocation> entitiesWithRenderErrors = new HashSet<>();

    private GuiDrawingUtils() {}

    /**
     * Draws a 5-pointed star using the Tessellator for proper GUI rendering.
     * The star is drawn as 5 triangles, each connecting the center to two adjacent points
     * (alternating between outer tips and inner valleys).
     *
     * @param centerX center X coordinate
     * @param centerY center Y coordinate
     * @param outerRadius distance from center to outer points (tips)
     * @param color ARGB color (e.g., 0xFFFFD700 for gold)
     */
    public static void drawStar(float centerX, float centerY, float outerRadius, int color) {
        // Extract color components
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        // Inner radius is typically ~38% of outer radius for a classic 5-pointed star
        float innerRadius = outerRadius * 0.38f;

        // Calculate 10 points: alternating outer (tips) and inner (valleys)
        // Starting from top point, going clockwise (36 degrees apart)
        float[] pointsX = new float[10];
        float[] pointsY = new float[10];
        for (int i = 0; i < 10; i++) {
            double angle = Math.toRadians(-90 + i * 36);
            float radius = (i % 2 == 0) ? outerRadius : innerRadius;
            pointsX[i] = centerX + (float) (radius * Math.cos(angle));
            pointsY[i] = centerY + (float) (radius * Math.sin(angle));
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(r, g, b, a);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);

        // Center point
        buffer.pos(centerX, centerY, 0.0).endVertex();

        // Add all 10 points around the star, plus close the loop
        for (int i = 0; i <= 10; i++) buffer.pos(pointsX[i % 10], pointsY[i % 10], 0.0).endVertex();

        tessellator.draw();

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /**
     * Draws a simple red stop-sign-like octagon.
     */
    public static void drawStopSign(float centerX, float centerY, float radius, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        float innerRadius = radius * 0.75f;

        // Octagon points, flat top
        float[] px = new float[8];
        float[] py = new float[8];
        float[] pxInner = new float[8];
        float[] pyInner = new float[8];
        // Start at -22.5 degrees to flatten top/bottom edges
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(-22.5 + i * 45.0);
            px[i] = centerX + (float) (radius * Math.cos(angle));
            py[i] = centerY + (float) (radius * Math.sin(angle));

            pxInner[i] = centerX + (float) (innerRadius * Math.cos(angle));
            pyInner[i] = centerY + (float) (innerRadius * Math.sin(angle));
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // Outer octagon
        GlStateManager.color(r, g, b, a);

        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
        buffer.pos(centerX, centerY, 0.0).endVertex();
        for (int i = 0; i <= 8; i++) buffer.pos(px[i % 8], py[i % 8], 0.0).endVertex();
        tessellator.draw();

        // Inner octagon (cutout)
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
        buffer.pos(centerX, centerY, 0.0).endVertex();
        for (int i = 0; i <= 8; i++) buffer.pos(pxInner[i % 8], pyInner[i % 8], 0.0).endVertex();
        tessellator.draw();

        // Diagonal bar
        GlStateManager.color(r, g, b, a);

        GL11.glLineWidth(4.0f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3f((px[7] + px[0]) / 2, (py[7] + py[0]) / 2, 0f);
        GL11.glVertex3f((px[3] + px[4]) / 2, (py[3] + py[4]) / 2, 0f);
        GL11.glEnd();

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /**
     * Draws a red X between two corners with configurable thickness.
     */
    public static void drawRedX(float x1, float y1, float x2, float y2, float thickness, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(r, g, b, a);

        GL11.glLineWidth(thickness);

        // First diagonal
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3f(x1, y1, 0f);
        GL11.glVertex3f(x2, y2, 0f);
        GL11.glEnd();

        // Second diagonal
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3f(x1, y2, 0f);
        GL11.glVertex3f(x2, y1, 0f);
        GL11.glEnd();

        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /**
     * Draws an entity preview in a box with border.
     *
     * @param id The entity's ResourceLocation
     * @param entity The entity instance to render
     * @param x Top-left X coordinate
     * @param y Top-left Y coordinate
     * @param size Size of the preview box
     * @param rotationY Y-axis rotation for the entity
     */
    public static void drawMobPreview(ResourceLocation id, Entity entity, int x, int y, int size, float rotationY) {
        if (entity == null || size <= 0) return;

        // Draw background with border
        Gui.drawRect(x - 1, y - 1, x + size + 1, y + size + 1, ENTITY_BORDER_COLOR);
        Gui.drawRect(x, y, x + size, y + size, ENTITY_BG_COLOR);

        // Calculate scale based on entity's visual model size (via shadow size or collision box)
        // FIXME: both render scale methods have issues with certain entities, find a middle ground?
        // float scale = EntityRenderHelper.getVisualRenderScale(entity, (float) size);  // issues with tall/big entities
        // float scale = EntityRenderHelper.getShadowBasedRenderScale(entity, (float) size);    // issues with wide entities
        float maxDimension = Math.max(1.0f, Math.max(entity.height, entity.width));
        float scale = size / maxDimension / 1.5f;

        // Center position of the preview box
        int centerX = x + size / 2;
        int centerY = y + size / 2;

        GlStateManager.pushMatrix();
        GlStateManager.color(1f, 1f, 1f);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableColorMaterial();
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 50F);
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(20F, 1.0F, 0.0F, 0.0F); // isometric tilt
        GlStateManager.rotate(rotationY, 0.0F, 1.0F, 0.0F);

        // Translate entity so its bounding box center aligns with the preview center
        // Entity origin (0,0,0) is at their feet, so we shift up by half their height
        float verticalOffset = entity.height / 2.0f;
        // Also apply entity's intrinsic Y offset (e.g., for hanging entities)
        verticalOffset += (float) entity.getYOffset();
        GlStateManager.translate(0.0F, -verticalOffset, 0.0F);
        Minecraft.getMinecraft().getRenderManager().playerViewY = 180F;

        try {
            if (!entitiesWithRenderErrors.contains(id)) {
                // FIXME: Gaia 3 seems to throw FML errors, which are logged deeper. It doesn't throw, so we cannot catch them here.
                Minecraft.getMinecraft().getRenderManager().renderEntity(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
            }
        } catch (Throwable t) {
            if (!entitiesWithRenderErrors.contains(id)) {
                entitiesWithRenderErrors.add(id);
                SuperMobTracker.LOGGER.warn("Failed to render entity preview for " + id + ": " + t.getMessage());
            }
        }

        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();

        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.popMatrix();
        GlStateManager.enableDepth();
        GlStateManager.disableColorMaterial();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /**
     * Checks if an entity has had render errors.
     * @param id The entity's ResourceLocation
     * @return true if the entity has had render errors
     */
    public static boolean hasRenderError(ResourceLocation id) {
        return entitiesWithRenderErrors.contains(id);
    }
}
