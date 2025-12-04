package com.supermobtracker.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.List;


/**
 * Client-side utility for getting visual rendering information about entities.
 * This is used to improve entity preview rendering in the mob tracker GUI.
 * 
 * Provides two methods for estimating entity visual size:
 * 1. Shadow-based: Uses the renderer's shadowSize field (fast, but may be inaccurate)
 * 2. Model-based: Traverses model boxes to calculate actual bounding box (accurate, but slower)
 */
public final class EntityRenderHelper {

    private static Field shadowSizeField;
    private static boolean shadowFieldInitialized = false;

    private static Field mainModelField;
    private static boolean modelFieldsInitialized = false;

    private EntityRenderHelper() {}

    /**
     * Calculate the scale factor to fit an entity within a preview box.
     * Uses model-based size calculation for accurate scaling.
     *
     * @param entity the entity to scale
     * @param boxSize the size of the preview box
     * @return the scale factor to apply when rendering
     */
    public static float getVisualRenderScale(Entity entity, float boxSize) {
        Vec3d modelSize = getModelBasedSize(entity);
        float maxDimension = Math.max(1.0f, (float) Math.max(modelSize.x, Math.max(modelSize.y, modelSize.z)));

        return boxSize / maxDimension / 1.3f;
    }

    /**
     * Calculate the scale factor using shadow-based estimation (legacy method).
     * Falls back to collision box if shadow size is unavailable.
     *
     * @param entity the entity to scale
     * @param boxSize the size of the preview box
     * @return the scale factor to apply when rendering
     */
    public static float getShadowBasedRenderScale(Entity entity, float boxSize) {
        float maxDimension = Math.max(1.0f, getMaxShadowBasedDimension(entity));

        return boxSize / maxDimension / 1.3f;
    }

    /**
     * Gets the visual size of an entity by analyzing its model's boxes.
     * This traverses all ModelRenderer boxes and their children to compute
     * the actual bounding box of the rendered model.
     *
     * @param entity the entity to measure
     * @return Vec3d containing (width, height, depth) in block units
     */
    public static Vec3d getModelBasedSize(Entity entity) {
        if (entity == null) return new Vec3d(1, 1, 1);

        // Try to get model from renderer
        ModelBase model = getEntityModel(entity);
        if (model == null) return new Vec3d(entity.width, entity.height, entity.width);

        // Calculate bounds from all model boxes
        float[] bounds = calculateModelBounds(model);

        // bounds = [minX, minY, minZ, maxX, maxY, maxZ] in model units (1/16 of a block)
        // Convert to block units
        float width = (bounds[3] - bounds[0]) / 16.0f;
        float height = (bounds[4] - bounds[1]) / 16.0f;
        float depth = (bounds[5] - bounds[2]) / 16.0f;

        // Ensure minimum size
        width = Math.max(0.25f, width);
        height = Math.max(0.25f, height);
        depth = Math.max(0.25f, depth);

        return new Vec3d(width, height, depth);
    }

    /**
     * Gets the entity's model from its renderer.
     */
    private static ModelBase getEntityModel(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) return null;

        try {
            RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();
            if (renderManager == null) return null;

            Render<Entity> renderer = renderManager.getEntityRenderObject(entity);
            if (!(renderer instanceof RenderLivingBase)) return null;

            if (!modelFieldsInitialized) initModelFields();

            if (mainModelField != null) return (ModelBase) mainModelField.get(renderer);
        } catch (Exception e) {
            // Fall through to return null
        }

        return null;
    }

    /**
     * Calculate the bounding box of all boxes in a model.
     * Returns [minX, minY, minZ, maxX, maxY, maxZ] in model units.
     */
    private static float[] calculateModelBounds(ModelBase model) {
        float[] bounds = {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };

        try {
            // Get the boxList field from ModelBase
            Field boxListModelField = null;
            for (Field field : ModelBase.class.getDeclaredFields()) {
                if (field.getType() == List.class) {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value instanceof List && !((List<?>) value).isEmpty()) {
                        Object first = ((List<?>) value).get(0);
                        if (first instanceof ModelRenderer) {
                            boxListModelField = field;
                            break;
                        }
                    }
                }
            }

            if (boxListModelField != null) {
                @SuppressWarnings("unchecked")
                List<ModelRenderer> renderers = (List<ModelRenderer>) boxListModelField.get(model);
                for (ModelRenderer renderer : renderers) accumulateRendererBounds(renderer, bounds, 0, 0, 0);
            }
        } catch (Exception e) {
            // Return current bounds (may be invalid)
        }

        // If bounds are invalid, return default
        if (bounds[0] > bounds[3]) return new float[] { -8, -24, -8, 8, 0, 8 }; // Default 1x1.5x1 block entity

        return bounds;
    }

    /**
     * Accumulate bounds from a ModelRenderer and all its children.
     */
    private static void accumulateRendererBounds(ModelRenderer renderer, float[] bounds,
                                                  float parentOffsetX, float parentOffsetY, float parentOffsetZ) {
        if (renderer == null || renderer.isHidden) return;

        // Calculate this renderer's offset (rotation point + parent offset)
        float offsetX = parentOffsetX + renderer.rotationPointX;
        float offsetY = parentOffsetY + renderer.rotationPointY;
        float offsetZ = parentOffsetZ + renderer.rotationPointZ;

        // Process all boxes in this renderer
        if (renderer.cubeList != null) {
            for (ModelBox box : renderer.cubeList) {
                // Box coordinates are relative to the renderer's rotation point
                float minX = offsetX + box.posX1;
                float minY = offsetY + box.posY1;
                float minZ = offsetZ + box.posZ1;
                float maxX = offsetX + box.posX2;
                float maxY = offsetY + box.posY2;
                float maxZ = offsetZ + box.posZ2;

                bounds[0] = Math.min(bounds[0], minX);
                bounds[1] = Math.min(bounds[1], minY);
                bounds[2] = Math.min(bounds[2], minZ);
                bounds[3] = Math.max(bounds[3], maxX);
                bounds[4] = Math.max(bounds[4], maxY);
                bounds[5] = Math.max(bounds[5], maxZ);
            }
        }

        // Process child renderers recursively
        if (renderer.childModels != null) {
            for (ModelRenderer child : renderer.childModels) accumulateRendererBounds(child, bounds, offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Gets the visual size using shadow-based estimation.
     * This is the legacy method that uses shadow size as a hint.
     *
     * @param entity the entity to measure
     * @return Vec3d containing (width, height, depth) visual dimensions
     */
    public static Vec3d getShadowBasedSize(Entity entity) {
        if (entity == null) return new Vec3d(1, 1, 1);

        float baseWidth = entity.width;
        float baseHeight = entity.height;

        float shadowSize = getShadowSize(entity);
        if (shadowSize > 0) {
            float shadowRatio = shadowSize / Math.max(0.5f, baseWidth);
            if (shadowRatio > 1.2f || shadowRatio < 0.8f) baseWidth = shadowSize;
        }

        return new Vec3d(baseWidth, baseHeight, baseWidth);
    }

    /**
     * Gets the maximum shadow-based dimension for scaling purposes.
     */
    public static float getMaxShadowBasedDimension(Entity entity) {
        Vec3d size = getShadowBasedSize(entity);

        return (float) Math.max(size.x, Math.max(size.y, size.z));
    }

    /**
     * Attempts to get the shadow size from an entity's renderer.
     *
     * @param entity the entity
     * @return the shadow size, or 0 if unavailable
     */
    private static float getShadowSize(Entity entity) {
        try {
            RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();
            if (renderManager == null) return 0;

            Render<Entity> renderer = renderManager.getEntityRenderObject(entity);
            if (renderer == null) return 0;

            if (!shadowFieldInitialized) initShadowField();

            if (shadowSizeField != null) return shadowSizeField.getFloat(renderer);
        } catch (Exception e) {
            // Silently fail
        }

        return 0;
    }

    /**
     * Initialize the reflection field for shadowSize.
     */
    private static void initShadowField() {
        shadowFieldInitialized = true;

        try {
            shadowSizeField = Render.class.getDeclaredField("shadowSize");
            shadowSizeField.setAccessible(true);
            return;
        } catch (NoSuchFieldException e) {
            // Try obfuscated name
        }

        try {
            shadowSizeField = Render.class.getDeclaredField("field_76989_e");
            shadowSizeField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            shadowSizeField = null;
        }
    }

    /**
     * Initialize reflection fields for model access.
     */
    private static void initModelFields() {
        modelFieldsInitialized = true;

        try {
            mainModelField = RenderLivingBase.class.getDeclaredField("mainModel");
            mainModelField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            try {
                mainModelField = RenderLivingBase.class.getDeclaredField("field_77045_g");
                mainModelField.setAccessible(true);
            } catch (NoSuchFieldException e2) {
                mainModelField = null;
            }
        }
    }

    /**
     * Gets the visual center offset for an entity.
     *
     * @param entity the entity
     * @return the vertical offset to the visual center
     */
    public static float getVisualCenterY(Entity entity) {
        if (entity == null) return 0.5f;

        Vec3d size = getModelBasedSize(entity);
        return (float) (size.y / 2.0);
    }
}
