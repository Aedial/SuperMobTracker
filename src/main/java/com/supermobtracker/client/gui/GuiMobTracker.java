package com.supermobtracker.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.block.Block;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.client.util.EntityRenderHelper;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.spawn.BiomeDimensionMapper;
import com.supermobtracker.spawn.ConditionUtils;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;
import com.supermobtracker.tracking.SpawnTrackerManager;
import com.supermobtracker.util.JEIHelper;
import com.supermobtracker.util.Utils;


public class GuiMobTracker extends GuiScreen {
    private GuiTextField filterField;
    private MobListWidget listWidget;
    private ResourceLocation selected;
    private SpawnConditionAnalyzer analyzer = new SpawnConditionAnalyzer();
    private SpawnConditionAnalyzer.SpawnConditions spawnConditions;
    private long lastClickTime = 0L;
    private ResourceLocation lastClickId = null;

    // Track entities that have already had render errors reported
    private static final Set<ResourceLocation> entitiesWithRenderErrors = new HashSet<>();

    // Cache for spawn conditions to avoid regenerating on window resize
    private static ResourceLocation cachedEntityId = null;
    private static SpawnConditionAnalyzer.SpawnConditions cachedSpawnConditions = null;

    // JEI button bounds (updated during draw)
    private int jeiButtonX, jeiButtonY, jeiButtonW, jeiButtonH;
    private boolean jeiButtonVisible = false;

    // Panel bounds for text clipping
    private int panelMaxY = Integer.MAX_VALUE;

    // Biome tooltip data (set during drawRightPanel, rendered in drawBiomeTooltip)
    private List<String> tooltipBiomes = null;
    private int tooltipBiomesLabelX, tooltipBiomesLabelY, tooltipBiomesLabelW;

    // Unknown dimension tooltip data (set during drawRightPanel, rendered in drawDimensionTooltip)
    private boolean showDimensionUnknownTooltip = false;
    private int dimensionLabelX, dimensionLabelY, dimensionLabelW;

    private static final int entityBgColor = 0xFF404040;
    private static final int entityBorderColor = 0xFF808080;
    private static final int lightColor = 0xFFFFAA;
    private static final int ylevelColor = 0xAAAAFF;
    private static final int groundColor = 0xAAFFAA;
    private static final int timeOfDayColor = 0xFFAAFF;
    private static final int weatherColor = 0xAABBFF;
    private static final int skyColor = 0xAAFFFF;
    private static final int dimensionColor = 0xFFDDAA;
    private static final int biomeColor = 0xAADDFF;
    private static final int hintColor = 0xFFAAAA;

    private String getI18nButtonString() {
        return ClientSettings.i18nNames ? I18n.format("gui.supermobtracker.i18nIDs.on") : I18n.format("gui.supermobtracker.i18nIDs.off");
    }

    @Override
    public void initGui() {
        int leftWidth = Math.min(width / 2, 250);
        filterField = new GuiTextField(0, fontRenderer, 10, 10, leftWidth - 20, 14);
        filterField.setText(ModConfig.getClientFilterText());
        listWidget = new MobListWidget(10, 30, leftWidth - 20, height - 70, fontRenderer, this);
        this.buttonList.clear();

        this.buttonList.add(new GuiButton(1, 10, height - 30, leftWidth - 20, 20, getI18nButtonString()));

        // Restore last selected entity and its cached spawn conditions
        String lastEntity = ModConfig.getClientLastSelectedEntity();
        if (lastEntity != null && !lastEntity.isEmpty()) {
            ResourceLocation lastId = new ResourceLocation(lastEntity);
            if (analyzer.getEntityInstance(lastId) != null) {
                this.selected = lastId;

                // Reuse cached spawn conditions if the entity ID matches
                if (lastId.equals(cachedEntityId) && cachedSpawnConditions != null) {
                    this.spawnConditions = cachedSpawnConditions;
                } else {
                    this.spawnConditions = analyzer.analyze(lastId);
                    cachedEntityId = lastId;
                    cachedSpawnConditions = this.spawnConditions;
                }

                listWidget.ensureVisible(lastId);
            }
        }
    }

    public void selectEntity(ResourceLocation id) {
        this.selected = id;
        this.spawnConditions = analyzer.analyze(id);
        cachedEntityId = id;
        cachedSpawnConditions = this.spawnConditions;
        ModConfig.setClientLastSelectedEntity(id != null ? id.toString() : "");
    }

    public ResourceLocation getSelectedEntity() {
        return this.selected;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (selected == null) return;

        if (button.id == 1) {
            ClientSettings.toggleI18n();
            button.displayString = getI18nButtonString();
        }
    }

    @Override
    public void updateScreen() {
        filterField.updateCursorCounter();
        String newFilter = filterField.getText();
        listWidget.setFilter(newFilter);
        ModConfig.setClientFilterText(newFilter);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (filterField.textboxKeyTyped(typedChar, keyCode)) return;
        if (listWidget.handleKey(keyCode)) return;

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Right-click on filter field clears it
        if (mouseButton == 1 &&
            mouseX >= filterField.x && mouseX <= filterField.x + filterField.width &&
            mouseY >= filterField.y && mouseY <= filterField.y + filterField.height) {
            filterField.setText("");
        }

        filterField.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0) {
            // Handle biomes label click - copy to clipboard
            if (tooltipBiomes != null && !tooltipBiomes.isEmpty() &&
                mouseX >= tooltipBiomesLabelX && mouseX <= tooltipBiomesLabelX + tooltipBiomesLabelW &&
                mouseY >= tooltipBiomesLabelY && mouseY <= tooltipBiomesLabelY + 12) {
                String biomeList = String.join("\n", tooltipBiomes);
                GuiScreen.setClipboardString(biomeList);

                String entityName = formatEntityName(selected, true);
                String msg = I18n.format("gui.mobtracker.biomesCopied", entityName);
                mc.player.sendMessage(new net.minecraft.util.text.TextComponentString(msg));

                return;
            }

            // Handle JEI button click
            if (jeiButtonVisible && selected != null &&
                mouseX >= jeiButtonX && mouseX <= jeiButtonX + jeiButtonW &&
                mouseY >= jeiButtonY && mouseY <= jeiButtonY + jeiButtonH) {
                JEIHelper.showMobPage(selected);

                return;
            }

            // Handle list widget click
            ResourceLocation click = listWidget.handleClick(mouseX, mouseY);

            if (click != null) {
                long now = Minecraft.getSystemTime();
                boolean isDouble = click.equals(lastClickId) && (now - lastClickTime) < 500L;
                lastClickId = click;
                lastClickTime = now;
                selectEntity(click);

                if (isDouble) {
                    SpawnTrackerManager.toggleTracked(click);
                    ModConfig.setClientTrackedIds(SpawnTrackerManager.getTrackedIdStrings());
                    SpawnTrackerManager.scanWorld(mc.world);
                    listWidget.rebuildFiltered();
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (listWidget != null) listWidget.onMouseDrag(mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (listWidget != null) listWidget.onMouseRelease();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getDWheel();
        if (wheel != 0) listWidget.scroll(wheel < 0 ? 1 : -1);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        filterField.drawTextBox();
        listWidget.draw();
        drawRightPanel(mouseX, mouseY, partialTicks);

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawBiomeTooltip(mouseX, mouseY);
        drawDimensionTooltip(mouseX, mouseY);
    }

    private int drawElidedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        if (y + lineHeight > panelMaxY) return y;

        String elided = renderer.trimStringToWidth(text, maxWidth - renderer.getStringWidth("…"));
        if (!elided.equals(text)) elided += "…";

        renderer.drawString(elided, x, y, color);

        return y + lineHeight;
    }

    private int drawWrappedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        return drawWrappedString(renderer, text, x, y, lineHeight, 0, maxWidth, color);
    }

    private int drawWrappedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int extraSpacing, int maxWidth, int color) {
        List<String> wrapped = Utils.wrapText(renderer, text, maxWidth);
        int totalHeight = lineHeight * wrapped.size() + extraSpacing * Math.max(0, wrapped.size() - 1);
        if (y + totalHeight > panelMaxY) return y;

        for (int i = 0; i < wrapped.size(); i++) {
            renderer.drawString(wrapped.get(i), x, y, color);
            y += lineHeight + (i < wrapped.size() - 1 ? extraSpacing : 0);
        }

        return y;
    }

    private int drawSingleString(FontRenderer renderer, String text, int x, int y, int lineHeight, int color) {
        if (y + lineHeight > panelMaxY) return y;

        renderer.drawString(text, x, y, color);

        return y + lineHeight;
    }

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
    private void drawStar(float centerX, float centerY, float outerRadius, int color) {
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

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(r, g, b, a);

        Tessellator tessellator = net.minecraft.client.renderer.Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        buffer.begin(GL11.GL_TRIANGLE_FAN,DefaultVertexFormats.POSITION);

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
    }

    private String format(double d) {
        String s = String.format("%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);

        return s;
    }

    private String translateBlockName(String blockName) {
        if (blockName == null || blockName.isEmpty()) return "";

        // Try resolving the Block instance to leverage its translation key or localized name
        Block blk = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockName));
        if (blk != null) {
            // In 1.12, many mods set a custom translation key based on a display-name-like identifier
            String transKey = blk.getTranslationKey();
            if (transKey != null && !transKey.isEmpty()) {
                String k1 = transKey.endsWith(".name") ? transKey : (transKey + ".name");
                if (I18n.hasKey(k1)) return I18n.format(k1);

                // Some resource packs/mods provide direct key without .name
                if (I18n.hasKey(transKey)) return I18n.format(transKey);
            }

            // If the block has a localized name available already, use it directly
            // getLocalizedName() triggers I18n lookup using the block's unlocalized key
            try {
                String localized = blk.getLocalizedName();
                if (localized != null && !localized.isEmpty()) return localized;
            } catch (Throwable ignored) {
                // Some blocks may throw during early GUI contexts; ignore and continue with fallbacks
            }
        }

        // Parse namespace and path from registry name strings like "minecraft:grass"
        String namespace = "minecraft";
        String path = blockName;
        int colonIdx = blockName.indexOf(":");
        if (colonIdx >= 0) {
            namespace = blockName.substring(0, colonIdx);
            path = blockName.substring(colonIdx + 1);
        }

        // Common 1.12 language key patterns used by vanilla and many mods
        // 1) tile.<namespace>.<path>.name (typical modded style)
        String key1 = "tile." + namespace + "." + path + ".name";
        if (I18n.hasKey(key1)) return I18n.format(key1);

        // 2) tile.<path>.name (older vanilla style)
        String key2 = "tile." + path + ".name";
        if (I18n.hasKey(key2)) return I18n.format(key2);

        // 3) tile.<namespace>:<path>.name (some mods use a colon in keys)
        String key3 = "tile." + namespace + ":" + path + ".name";
        if (I18n.hasKey(key3)) return I18n.format(key3);

        // 4) block.<namespace>.<path> (newer style seen in resource packs)
        String key4 = "block." + namespace + "." + path;
        if (I18n.hasKey(key4)) return I18n.format(key4);

        // 5) block.<path> (fallback variant)
        String key5 = "block." + path;
        if (I18n.hasKey(key5)) return I18n.format(key5);

        // 6) Direct registry string when mods register explicit keys
        if (I18n.hasKey(blockName)) return I18n.format(blockName);

        // Fallback: give up and return the raw registry name
        return blockName;
    }

    /**
     * Translates a biome registry name to its localized display name.
     * @param biomeRegistryName The biome registry name (e.g., "minecraft:plains")
     * @return The localized biome name, or the registry name if not found
     */
    private String translateBiomeName(String biomeRegistryName) {
        Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(biomeRegistryName));
        if (biome == null) return biomeRegistryName;

        // Parse namespace and path from registry name string for translation lookup
        String namespace = "minecraft";
        String path = biomeRegistryName;
        int colonIdx = biomeRegistryName.indexOf(':');
        if (colonIdx >= 0) {
            namespace = biomeRegistryName.substring(0, colonIdx);
            path = biomeRegistryName.substring(colonIdx + 1);
        }

        // Try common translation key patterns used by mods
        // Pattern 1: biome.<namespace>.<path> (common modded pattern)
        String key1 = "biome." + namespace + "." + path;
        String translated1 = I18n.format(key1);
        if (!translated1.equals(key1)) return translated1;

        // Pattern 2: biome.<namespace>:<path> (alternate pattern)
        String key2 = "biome." + biomeRegistryName;
        String translated2 = I18n.format(key2);
        if (!translated2.equals(key2)) return translated2;

        // Pattern 3: biome.<path>.name (some mods use this)
        String key3 = "biome." + path + ".name";
        String translated3 = I18n.format(key3);
        if (!translated3.equals(key3)) return translated3;

        // Try the biome's internal name - works for most mods which set display names directly in BiomeProperties
        // It will, however, not be "localized"
        String biomeName = biome.getBiomeName();
        if (biomeName != null && !biomeName.isEmpty() && !biomeName.equals(biomeRegistryName) && !biomeName.contains(":") && !biomeName.contains("_")) {
            return biomeName;
        }

        return biomeRegistryName;
    }

    /**
     * Translates a dimension name to a localized display string.
     * Tries various translation key patterns used by Minecraft and mods.
     */
    private String translateDimensionName(String dimName) {
        if (dimName == null || dimName.isEmpty()) return "?";

        // Parse namespace and path
        String namespace = "minecraft";
        String path = dimName;
        int colonIdx = dimName.indexOf(':');
        if (colonIdx >= 0) {
            namespace = dimName.substring(0, colonIdx);
            path = dimName.substring(colonIdx + 1);
        }

        // Try common translation key patterns
        // Pattern 1: dimension.<namespace>.<path> (modded pattern)
        String key1 = "dimension." + namespace + "." + path;
        String translated1 = I18n.format(key1);
        if (!translated1.equals(key1)) return translated1;

        // Pattern 2: dimension.<path> (vanilla-style)
        String key2 = "dimension." + path;
        String translated2 = I18n.format(key2);
        if (!translated2.equals(key2)) return translated2;

        // Pattern 3: <namespace>.dimension.<path> (alternate modded)
        String key3 = namespace + ".dimension." + path;
        String translated3 = I18n.format(key3);
        if (!translated3.equals(key3)) return translated3;

        // Pattern 4: dimension.<namespace>:<path>
        String key4 = "dimension." + dimName;
        String translated4 = I18n.format(key4);
        if (!translated4.equals(key4)) return translated4;

        // No translation found - return raw name so user knows what key to add
        return dimName;
    }

    private void drawRightPanel(int mouseX, int mouseY, float partialTicks) {
        int leftWidth = Math.min(width / 2, 250);
        int panelX = leftWidth + 10;
        int panelY = 10;
        int panelW = width - panelX - 20;
        int panelH = height - 40;
        panelMaxY = panelY + panelH - 6;

        String sep = I18n.format("gui.mobtracker.separator");

        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        if (selected == null) {
            fontRenderer.drawString(I18n.format("gui.mobtracker.noMobSelected"), panelX + 6, panelY + 6, 0xFFFFFF);
            return;
        }

        int textX = panelX + 6;
        int textY = panelY + 6;
        int textW = panelW - 12;
        int color = 0xFFFFFF;

        // preview: up to a quarter of panel height, slowly rotating (full rotation every 10s)
        int previewSizeLocal = Math.min(textW, panelH / 4);
        int previewX = textX + (textW - previewSizeLocal) / 2;
        int previewY = textY;
        int previewSize = previewSizeLocal;
        float previewRotationY = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;

        drawMobPreview(selected, previewX, previewY, previewSize, previewRotationY);

        textY += previewSize + 16;

        ArrayList<String> attributes = new ArrayList<>();
        if (analyzer.isBoss(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.boss"));
        if (analyzer.cannotDespawn(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.cannotDespawn"));
        if (analyzer.isPassive(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.passive"));
        if (analyzer.isNeutral(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.neutral"));
        if (analyzer.isHostile(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.hostile"));
        if (analyzer.isAquatic(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.aquatic"));
        if (analyzer.isFlying(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.flying"));
        if (analyzer.isTameable(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.tameable"));

        String name = I18n.format("gui.mobtracker.entityName", formatEntityName(selected, true));
        textY = drawElidedString(fontRenderer, name, textX, textY, 14, textW, color);

        String entityIdStr = I18n.format("gui.mobtracker.entityId", selected.toString());
        textY = drawElidedString(fontRenderer, entityIdStr, textX, textY, 14, textW, color);

        Vec3d size = analyzer.getEntitySize(selected);
        String sizeStr = I18n.format("gui.mobtracker.entitySize", format(size.x), format(size.y), format(size.z));
        textY = drawElidedString(fontRenderer, sizeStr, textX, textY, 14, textW, color);

        String attributeString = I18n.format("gui.mobtracker.attributes", String.join(sep, attributes));
        textY = drawWrappedString(fontRenderer, attributeString, textX, textY, 14, textW, color);

        // JEI button (only if JEI is loaded and can show mob info)
        jeiButtonVisible = false;
        if (JEIHelper.canShowMobPage(selected)) {
            String jeiText = I18n.format("gui.mobtracker.jeiButton");
            jeiButtonW = fontRenderer.getStringWidth(jeiText) + 8;
            jeiButtonH = 12;
            jeiButtonX = textX;
            jeiButtonY = textY;
            jeiButtonVisible = true;

            boolean hovered = mouseX >= jeiButtonX && mouseX <= jeiButtonX + jeiButtonW &&
                                mouseY >= jeiButtonY && mouseY <= jeiButtonY + jeiButtonH;
            int btnBg = hovered ? 0x60FFFFFF : 0x40FFFFFF;
            int btnColor = hovered ? 0xFFFFAA : 0xCCCCCC;

            drawRect(jeiButtonX, jeiButtonY, jeiButtonX + jeiButtonW, jeiButtonY + jeiButtonH, btnBg);
            fontRenderer.drawString(jeiText, jeiButtonX + 4, jeiButtonY + 2, btnColor);
            textY += jeiButtonH + 4;
        }

        // Check if analysis failed (crashed) vs entity cannot spawn naturally
        List<String> errorHints = analyzer.getErrorHints();
        boolean analysisCrashed = !errorHints.isEmpty() && errorHints.stream().anyMatch(h -> h.contains("crashed"));

        // Entities without native biomes (not in any spawn table) cannot spawn naturally.
        // But if analysis crashed, show that instead.
        if (spawnConditions == null) {
            textY += 10;

            if (analysisCrashed) {
                textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.analysisCrashed"), textX, textY, 12, textW, 0xFFAAAA);
                textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.analysisCrashedHint"), textX, textY, 12, textW, 0xAAAAAA);
            } else {
                textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.cannotSpawnNaturally"), textX, textY, 12, textW, 0xFFAAAA);
                textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.cannotSpawnNaturallyHint"), textX, textY, 12, textW, 0xAAAAAA);
            }

            return;
        }

        textY += 20;

        textY = drawSingleString(fontRenderer, I18n.format("gui.mobtracker.spawnConditions"), textX, textY, 12, color);

        int condsX = textX + 6;

        // Only show spawn condition details if they have valid data
        if (!spawnConditions.failed()) {
            String lightLevels = I18n.format("gui.mobtracker.lightLevels", Utils.formatRangeFromList(spawnConditions.lightLevels, sep));
            textY = drawWrappedString(fontRenderer, lightLevels, condsX, textY, 12, textW, lightColor);

            String yPos = I18n.format("gui.mobtracker.yPos", Utils.formatRangeFromList(spawnConditions.yLevels, sep));
            textY = drawWrappedString(fontRenderer, yPos, condsX, textY, 12, textW, ylevelColor);

            // Show ground blocks only if this condition was queried (list is non-null)
            if (spawnConditions.groundBlocks != null) {
                // Limit displayed ground blocks to avoid excessively long lines
                int maxGroundBlocksToShow = 20;
                List<String> groundBlocksList = spawnConditions.groundBlocks;
                List<String> groundBlocksLimited = groundBlocksList;
                if (groundBlocksList.size() > maxGroundBlocksToShow) {
                    groundBlocksLimited = groundBlocksList.subList(0, maxGroundBlocksToShow);
                    groundBlocksLimited.add("…");
                }

                String groundBlocksTranslated = groundBlocksLimited.stream()
                    .map(this::translateBlockName)
                    .collect(Collectors.joining(sep));
                String groundBlocks = I18n.format("gui.mobtracker.groundBlocks", groundBlocksTranslated);
                textY = drawWrappedString(fontRenderer, groundBlocks, condsX, textY, 12, textW, groundColor);
            }

            // Show time of day only if queried (list is non-null)
            if (spawnConditions.timeOfDay != null) {
                String timeOfDayTL = String.join(sep, ConditionUtils.translateList(spawnConditions.timeOfDay, "gui.mobtracker.timeOfDay"));
                String timeOfDay = I18n.format("gui.mobtracker.timeOfDay", timeOfDayTL);
                textY = drawWrappedString(fontRenderer, timeOfDay, condsX, textY, 12, textW, timeOfDayColor);
            }

            // Show weather only if queried (list is non-null)
            if (spawnConditions.weather != null) {
                String weatherTL = String.join(sep, ConditionUtils.translateList(spawnConditions.weather, "gui.mobtracker.weather"));
                String weather = I18n.format("gui.mobtracker.weather", weatherTL);
                textY = drawWrappedString(fontRenderer, weather, condsX, textY, 12, textW, weatherColor);
            }

            // Display sky requirement if determined
            if (spawnConditions.requiresSky != null) {
                String skyKey = spawnConditions.requiresSky ? "outside" : "underground";
                String skyText = I18n.format("gui.mobtracker.sky." + skyKey);
                textY = drawWrappedString(fontRenderer, skyText, condsX, textY, 12, textW, skyColor);
            }

            // Display moon phases if determined
            if (spawnConditions.moonPhases != null && !spawnConditions.moonPhases.isEmpty()) {
                String moonPhasesStr = spawnConditions.moonPhases.stream()
                    .map(phase -> I18n.format("gui.mobtracker.moonphase." + phase))
                    .collect(Collectors.joining(sep));
                String moonText = I18n.format("gui.mobtracker.moonphase", moonPhasesStr);
                textY = drawWrappedString(fontRenderer, moonText, condsX, textY, 12, textW, skyColor);
            }

            // Display slime chunk requirement if determined
            if (spawnConditions.requiresSlimeChunk != null) {
                String slimeKey = spawnConditions.requiresSlimeChunk ? "required" : "excluded";
                String slimeText = I18n.format("gui.mobtracker.slimechunk." + slimeKey);
                textY = drawWrappedString(fontRenderer, slimeText, condsX, textY, 12, textW, skyColor);
            }

            // Display nether requirement if determined
            if (spawnConditions.requiresNether != null) {
                String netherKey = spawnConditions.requiresNether ? "required" : "excluded";
                String netherText = I18n.format("gui.mobtracker.nether." + netherKey);
                textY = drawWrappedString(fontRenderer, netherText, condsX, textY, 12, textW, skyColor);
            }
        } else {
            String noConditions = I18n.format("gui.mobtracker.noSpawnConditions");
            textY = drawWrappedString(fontRenderer, noConditions, condsX, textY, 12, textW, 0xFFAAAA);
        }

        // Format dimension as "ID (name)" where name is the translated dimension name
        String dimDisplay;
        boolean isDimensionUnknown = false;
        if (spawnConditions.dimension != null) {
            String translatedName = translateDimensionName(spawnConditions.dimension);
            if (spawnConditions.dimensionId != Integer.MIN_VALUE) {
                dimDisplay = spawnConditions.dimensionId + " (" + translatedName + ")";
            } else {
                dimDisplay = "? (" + translatedName + ")";
            }
        } else {
            dimDisplay = "?";
            isDimensionUnknown = true;
        }
        String dimension = I18n.format("gui.mobtracker.dimension", dimDisplay);
        int dimensionLabelYPos = textY;
        textY = drawWrappedString(fontRenderer, dimension, condsX, textY, 12, textW, dimensionColor);

        // Store dimension tooltip data for rendering when dimension is unknown
        showDimensionUnknownTooltip = isDimensionUnknown;
        if (isDimensionUnknown) {
            dimensionLabelX = condsX;
            dimensionLabelY = dimensionLabelYPos;
            dimensionLabelW = fontRenderer.getStringWidth(dimension);
        }

        int biomesCount = spawnConditions.biomes.size();
        boolean hasBiomes = biomesCount > 0;
        boolean isUnknownBiome = biomesCount == 1 && spawnConditions.biomes.get(0).equals("unknown");
        boolean isAnyBiome = biomesCount == 1 && spawnConditions.biomes.get(0).equals("any");

        // Deduplicate biomes
        List<String> uniqueBiomes = new ArrayList<>(new LinkedHashSet<>(spawnConditions.biomes));
        int uniqueBiomesCount = uniqueBiomes.size();

        String biomesLabel;
        if (!hasBiomes) {
            biomesLabel = I18n.format("gui.mobtracker.biomes.none");
        } else if (isUnknownBiome) {
            biomesLabel = I18n.format("gui.mobtracker.biomes.unknown");
        } else if (isAnyBiome) {
            biomesLabel = I18n.format("gui.mobtracker.biomes.any");
        } else if (uniqueBiomesCount == 1) {
            biomesLabel = I18n.format("gui.mobtracker.biomes", translateBiomeName(uniqueBiomes.get(0)));
        } else {
            biomesLabel = I18n.format("gui.mobtracker.biomes", uniqueBiomesCount);
        }

        int biomesLabelY = textY;
        textY = drawSingleString(fontRenderer, biomesLabel, condsX, textY, 12, biomeColor);

        List<String> hints = spawnConditions.hints;
        if (!hints.isEmpty()) {
            textY += 10;
            textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.hintsHeader"), textX, textY, 10, textW, color);

            for (String hint : hints) textY = drawWrappedString(fontRenderer, "- " + hint, textX + 6, textY, 12, textW, hintColor);
        }

        // Store biome tooltip data for later rendering (after buttons are drawn)
        tooltipBiomes = null;
        if (uniqueBiomesCount > 1) {
            // Sort biomes: minecraft: first, then alphabetically by localized name
            List<String> sortedBiomes = new ArrayList<>(uniqueBiomes);
            sortedBiomes.sort((a, b) -> {
                boolean aMinecraft = a.startsWith("minecraft:");
                boolean bMinecraft = b.startsWith("minecraft:");
                if (aMinecraft != bMinecraft) return aMinecraft ? -1 : 1;

                return translateBiomeName(a).compareToIgnoreCase(translateBiomeName(b));
            });

            // Translate biome names for display
            tooltipBiomes = sortedBiomes.stream().map(this::translateBiomeName).collect(Collectors.toList());
            tooltipBiomesLabelX = condsX;
            tooltipBiomesLabelY = biomesLabelY;
            tooltipBiomesLabelW = fontRenderer.getStringWidth(biomesLabel);
        }
    }

    private void drawBiomeTooltip(int mouseX, int mouseY) {
        if (tooltipBiomes == null || tooltipBiomes.isEmpty()) return;

        boolean showTooltip = mouseX >= tooltipBiomesLabelX && mouseX <= tooltipBiomesLabelX + tooltipBiomesLabelW &&
            mouseY >= tooltipBiomesLabelY && mouseY <= tooltipBiomesLabelY + 12;
        if (!showTooltip) return;

        int leftWidth = Math.min(width / 2, 250);
        int panelH = height - 40;

        // Screen edge padding (5% of height)
        int edgePadding = panelH / 20;

        // Calculate multi-column layout to show all biomes
        int lineHeight = 10;
        int columnPadding = 8;
        int availableHeight = height - edgePadding * 2;
        int maxLinesPerColumn = Math.max(1, availableHeight / lineHeight);

        // Calculate how many columns we need
        int totalBiomes = tooltipBiomes.size();
        int numColumns = (int) Math.ceil((double) totalBiomes / maxLinesPerColumn);

        // Calculate column widths based on content
        List<Integer> columnWidths = new ArrayList<>();
        for (int col = 0; col < numColumns; col++) {
            int startIdx = col * maxLinesPerColumn;
            int endIdx = Math.min(startIdx + maxLinesPerColumn, totalBiomes);
            int maxWidth = 0;
            for (int i = startIdx; i < endIdx; i++) {
                int w = fontRenderer.getStringWidth(tooltipBiomes.get(i));
                if (w > maxWidth) maxWidth = w;
            }
            columnWidths.add(maxWidth);
        }

        // Calculate total tooltip dimensions
        int tooltipW = columnWidths.stream().mapToInt(Integer::intValue).sum() + columnPadding * (numColumns + 1);
        int linesInTooltip = Math.min(totalBiomes, maxLinesPerColumn);
        int tooltipH = linesInTooltip * lineHeight + 6;

        // Clamp tooltip width to screen width
        int maxTooltipW = width - 8;
        if (tooltipW > maxTooltipW) tooltipW = maxTooltipW;

        // Position tooltip: prefer top-left of cursor, then try other positions if it overflows
        int boxX = mouseX - tooltipW - 12;
        if (boxX < edgePadding) {
            boxX = mouseX + 12;
            if (boxX + tooltipW > width - edgePadding) boxX = edgePadding;
        }

        // Position vertically: prefer above cursor, then below if it overflows
        int boxY = mouseY - tooltipH - 12;
        if (boxY < edgePadding) {
            boxY = mouseY + 12;
            if (boxY + tooltipH > height - edgePadding) boxY = edgePadding;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 400.0F); // ensure tooltip is on top

        // Draw border and background
        drawRect(boxX - 1, boxY - 1, boxX + tooltipW + 1, boxY + tooltipH + 1, 0xFF505050);
        drawRect(boxX, boxY, boxX + tooltipW, boxY + tooltipH, 0xF0100010);

        // Draw biomes in columns
        int xOffset = boxX + columnPadding;
        for (int col = 0; col < numColumns; col++) {
            int startIdx = col * maxLinesPerColumn;
            int endIdx = Math.min(startIdx + maxLinesPerColumn, totalBiomes);
            for (int i = startIdx; i < endIdx; i++) {
                int row = i - startIdx;
                fontRenderer.drawString(tooltipBiomes.get(i), xOffset, boxY + 3 + row * lineHeight, 0xDDDDDD);
            }
            if (col < columnWidths.size()) {
                xOffset += columnWidths.get(col) + columnPadding;
            }
        }
        GlStateManager.popMatrix();
    }

    private void drawDimensionTooltip(int mouseX, int mouseY) {
        if (!showDimensionUnknownTooltip) return;

        boolean showTooltip = mouseX >= dimensionLabelX && mouseX <= dimensionLabelX + dimensionLabelW &&
            mouseY >= dimensionLabelY && mouseY <= dimensionLabelY + 12;
        if (!showTooltip) return;

        String tooltipKey = BiomeDimensionMapper.isBackgroundSamplingActive()
            ? "gui.mobtracker.dimension.unknown.tooltip.scanning"
            : "gui.mobtracker.dimension.unknown.tooltip.complete";
        String tooltipText = I18n.format(tooltipKey);
        drawHoveringText(java.util.Collections.singletonList(tooltipText), mouseX, mouseY);
    }

    private void drawMobPreview(ResourceLocation id, int x, int y, int size, float rotationY) {
        Entity entity = analyzer.getEntityInstance(id);
        if (entity == null || size <= 0) return;

        // Draw background with border
        drawRect(x - 1, y - 1, x + size + 1, y + size + 1, entityBorderColor);
        drawRect(x, y, x + size, y + size, entityBgColor);

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
            // FIXME: Gaia 3 seems to throw FML errors, which are logged deepder. It doesn't throw, so we cannot catch them here.
            if (!entitiesWithRenderErrors.contains(id)) {
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
     * Formats the entity name based on settings.
     * @param id Entity ResourceLocation
     * @param applyI18n Whether to apply internationalization
     * @return Formatted entity name
     */
    private String formatEntityName(ResourceLocation id, boolean applyI18n) {
        if (id == null) return "";
        if (!applyI18n) return id.toString();

        Entity entity = analyzer.getEntityInstance(id);
        if (entity != null) return entity.getDisplayName().getUnformattedText();

        // Fallback for modded entities missing translation mapping
        String[] parts = id.toString().split(":" , 2);
        String domain = parts.length > 0 ? parts[0] : "minecraft";
        String path = parts.length > 1 ? parts[1] : parts[0];
        String altKey = "entity." + domain + "." + path + ".name";
        if (I18n.hasKey(altKey)) return I18n.format(altKey);

        return id.toString();
    }

    class MobListWidget {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private FontRenderer fontRenderer;
        private GuiMobTracker tracker;

        private List<ResourceLocation> all = new ArrayList<>();
        // Store entries as (displayName, id) pairs to handle duplicate names
        private List<MobEntry> filteredSortedRaw = new ArrayList<>();
        private List<MobEntry> filteredSortedI18n = new ArrayList<>();
        private int scrollOffset = 0;
        private String filter = "";
        private boolean draggingScrollbar = false;
        private boolean lastI18n = ClientSettings.i18nNames;

        // Simple entry class to pair display name with resource location
        private class MobEntry {
            final String displayName;
            final ResourceLocation id;

            MobEntry(String displayName, ResourceLocation id) {
                this.displayName = displayName;
                this.id = id;
            }
        }

        MobListWidget(int x, int y, int w, int h, FontRenderer fontRenderer, GuiMobTracker tracker) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.fontRenderer = fontRenderer;
            this.tracker = tracker;

            all.addAll(ForgeRegistries.ENTITIES.getKeys().stream()
                .filter(id -> analyzer.getEntityInstance(id) != null)
                .collect(Collectors.toList()));

            rebuildFiltered();
            lastI18n = ClientSettings.i18nNames;
        }

        void setFilter(String filter) {
            if (filter == null) filter = "";

            if (!filter.equals(this.filter)) {
                this.filter = filter.toLowerCase();
                rebuildFiltered();
                scrollOffset = 0;
            }
        }

        private void rebuildFiltered() {
            String filterLower = this.filter.toLowerCase();

            // Filter by both raw ID and i18n name
            List<ResourceLocation> base = all.stream()
                .filter(id -> {
                    if (id.toString().toLowerCase().contains(filterLower)) return true;

                    String i18nName = tracker.formatEntityName(id, true).toLowerCase();

                    return i18nName.contains(filterLower);
                })
                .collect(Collectors.toList());

            List<ResourceLocation> trackedTop = base.stream().filter(SpawnTrackerManager::isTracked).collect(Collectors.toList());
            List<ResourceLocation> rest = base.stream().filter(id -> !SpawnTrackerManager.isTracked(id)).collect(Collectors.toList());

            // Build entries with unique display names
            filteredSortedRaw.clear();
            filteredSortedI18n.clear();

            // Count occurrences of each i18n name to detect duplicates (across all entries)
            Map<String, Integer> i18nNameCounts = new HashMap<>();
            for (ResourceLocation id : base) {
                String i18nName = tracker.formatEntityName(id, true);
                i18nNameCounts.merge(i18nName, 1, Integer::sum);
            }

            // Helper to create entries with disambiguation for duplicates
            BiConsumer<List<ResourceLocation>, Boolean> buildEntries = (ids, isTracked) -> {
                List<MobEntry> rawEntries = new ArrayList<>();
                List<MobEntry> i18nEntries = new ArrayList<>();

                for (ResourceLocation id : ids) {
                    String rawName = tracker.formatEntityName(id, false);
                    String i18nName = tracker.formatEntityName(id, true);

                    // If this i18n name appears multiple times, append the entity ID to disambiguate
                    if (i18nNameCounts.get(i18nName) > 1) i18nName = i18nName + " (" + id.toString() + ")";

                    rawEntries.add(new MobEntry(rawName, id));
                    i18nEntries.add(new MobEntry(i18nName, id));
                }

                // Sort by display name
                rawEntries.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                i18nEntries.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

                filteredSortedRaw.addAll(rawEntries);
                filteredSortedI18n.addAll(i18nEntries);
            };

            // Build tracked entries first (they appear at top, sorted within themselves)
            buildEntries.accept(trackedTop, true);

            // Then build non-tracked entries (sorted within themselves)
            buildEntries.accept(rest, false);
        }

        // Helper to return current display list depending on i18n setting
        private List<MobEntry> getDisplayList() {
            return ClientSettings.i18nNames ? filteredSortedI18n : filteredSortedRaw;
        }

        // Find index of entry by ResourceLocation id
        private int findIndexById(ResourceLocation id) {
            if (id == null) return -1;

            List<MobEntry> display = getDisplayList();
            for (int i = 0; i < display.size(); i++) {
                if (display.get(i).id.equals(id)) return i;
            }

            return -1;
        }

        // When i18n mode changes, rebuild filtered names and keep selection visible
        private void ensureI18nSync() {
            boolean curr = ClientSettings.i18nNames;
            if (curr == lastI18n) return;

            ResourceLocation sel = tracker.getSelectedEntity();
            rebuildFiltered();
            lastI18n = curr;

            ensureVisible(sel);
        }

        ResourceLocation handleClick(int mouseX, int mouseY) {
            if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return null;
            ensureI18nSync();

            List<MobEntry> display = getDisplayList();

            int total = display.size();
            int visible = h / 12;
            if (total > visible) {
                int barX1 = x + w - 4;
                int barX2 = x + w - 2;
                if (mouseX >= barX1 && mouseX <= barX2) {
                    draggingScrollbar = true;
                    updateScrollFromMouse(mouseY);
                    return null;
                }
            }

            int index = (mouseY - y) / 12 + scrollOffset;
            if (index >= 0 && index < display.size()) return display.get(index).id;

            return null;
        }

        /**
         * Ensure the entry at the given index is visible in the list.
         * @param index Index of the entry to make visible
         */
        public void ensureVisible(int index) {
            if (index == -1) return;

            int visible = h / 12;
            if (index < scrollOffset) {
                scrollOffset = index;
            } else if (index >= scrollOffset + visible) {
                scrollOffset = index - visible + 1;
            }
        }

        /**
         * Ensure the entry with the given ResourceLocation id is visible in the list.
         * @param id ResourceLocation of the entry to make visible
         */
        public void ensureVisible(ResourceLocation id) {
            ensureVisible(findIndexById(id));
        }

        public boolean handleKey(int keyCode) {
            int direction = 0;
            if (keyCode == 200) direction = -1; // up
            else if (keyCode == 208) direction = 1; // down
            else if (keyCode == 201) direction = -10; // page up
            else if (keyCode == 209) direction = 10; // page down
            else return false;
            ensureI18nSync();

            ResourceLocation selected = tracker.getSelectedEntity();
            if (selected == null) return false;

            List<MobEntry> display = getDisplayList();
            int selectedIndex = findIndexById(selected);
            if (selectedIndex == -1) return false;

            int oldIndex = selectedIndex;
            selectedIndex = Math.min(display.size() - 1, Math.max(0, selectedIndex + direction));
            if (selectedIndex == oldIndex) return true;

            ResourceLocation newId = display.get(selectedIndex).id;
            if (newId != null) {
                tracker.selectEntity(newId);
                ensureVisible(selectedIndex);
            }

            return true;
        }

        void draw() {
            ensureI18nSync();

            List<MobEntry> displayList = getDisplayList();
            ResourceLocation selectedId = tracker.getSelectedEntity();
            int selectedIndex = selectedId != null ? findIndexById(selectedId) : -1;

            GlStateManager.pushMatrix();
            int visible = h / 12;
            int totalToDraw = Math.max(0, Math.min(displayList.size() - scrollOffset, visible));
            for (int i = 0; i < totalToDraw; i++) {
                int drawY = y + i * 12;
                int listIndex = scrollOffset + i;
                boolean isSel = selectedIndex == listIndex;
                if (isSel) Gui.drawRect(x, drawY, x + w - 6, drawY + 12, 0x40FFFFFF);

                MobEntry entry = displayList.get(listIndex);
                String text = entry.displayName;
                int maxWidth = w - 10;
                String elided = fontRenderer.trimStringToWidth(text, maxWidth - fontRenderer.getStringWidth("…"));
                if (!elided.equals(text)) elided += "…";
                fontRenderer.drawString(elided, x + 4, drawY + 2, isSel ? 0xFFFFA0 : 0xFFFFFF);

                if (SpawnTrackerManager.isTracked(entry.id)) {
                    float starCenterX = x + w - 16;
                    float starCenterY = drawY + 6;
                    float starRadius = 4.0f;
                    drawStar(starCenterX, starCenterY, starRadius, 0xFFFFD700);
                }
            }

            drawScrollbar();
            GlStateManager.popMatrix();
        }

        private void drawScrollbar() {
            List<MobEntry> display = getDisplayList();
            int total = display.size();
            int visible = h / 12;
            if (total <= visible) return;

            int barX1 = x + w - 4;
            int barX2 = x + w - 2;
            int trackY1 = y;
            int trackY2 = y + h;
            Gui.drawRect(barX1, trackY1, barX2, trackY2, 0x40000000);

            float ratio = (float) visible / (float) total;
            int barH = Math.max(8, (int) (h * ratio));
            float posRatio = (float) scrollOffset / (float) (total - visible);
            int barY1 = y + (int) ((h - barH) * posRatio);
            int barY2 = barY1 + barH;
            Gui.drawRect(barX1, barY1, barX2, barY2, 0x80FFFFFF);
        }

        void scroll(int amount) {
            List<MobEntry> display = getDisplayList();
            int visible = h / 12;
            int maxOffset = Math.max(0, display.size() - visible);
            scrollOffset = Math.min(maxOffset, Math.max(0, scrollOffset + amount));
        }

        void onMouseDrag(int mouseY) {
            if (!draggingScrollbar) return;

            updateScrollFromMouse(mouseY);
        }

        void onMouseRelease() {
            draggingScrollbar = false;
        }

        private void updateScrollFromMouse(int mouseY) {
            List<MobEntry> display = getDisplayList();
            int total = display.size();
            int visible = h / 12;
            if (total <= visible) return;

            int barH = Math.max(8, (int) (h * ((float) visible / (float) total)));
            int trackTop = y;
            int trackBottom = y + h - barH;
            int clamped = Math.max(trackTop, Math.min(mouseY - barH / 2, trackBottom));
            float ratio = (float) (clamped - trackTop) / (float) (h - barH);
            int maxOffset = total - visible;
            scrollOffset = Math.max(0, Math.min(maxOffset, Math.round(ratio * maxOffset)));
        }
    }
}
