package com.supermobtracker.integration.jei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.client.gui.SmallVanillaButton;
import com.supermobtracker.client.util.GuiDrawingUtils;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.drops.LootDump;
import com.supermobtracker.drops.LootDump.LootEntry;
import com.supermobtracker.drops.LootDump.MobLoot;
import com.supermobtracker.ModItems;
import com.supermobtracker.util.TranslationUtils;


/**
 * Dumped-loot JEI view for a single mob
 * <p>
 * Layout :
 * <ul>
 *   <li>Left : mob preview, name at the top, open button at the bottom</li>
 *   <li>Right : grid of item stacks</li>
 *   <li>Bottom right : page buttons</li>
 */
public class MobLootJeiRecipe implements IRecipeWrapper {
    private static final String ANCHOR_TAG = "SMTMobLoot";

    private static final int SLOT_SIZE = 18;
    private static final int BUTTON_SIZE = 10;
    private static final int GRID_COLS = 4;

    private static final int PREVIEW_W = SLOT_SIZE * 5;
    private static final int GAP_W = 6;
    private static final int GRID_W = SLOT_SIZE * GRID_COLS;

    private static final int HEADER_PADDING = 3;
    private static final int HEADER_H = 14;
    private static final int HEADER_W = PREVIEW_W - 2 * HEADER_PADDING;
    private static final int HEADER_RIGHT_X = HEADER_PADDING + HEADER_W;
    private static final int HEADER_BOTTOM_Y = HEADER_PADDING + HEADER_H;
    private static final int GRID_X = PREVIEW_W + GAP_W;
    private static final int GRID_Y = 1;

    public static final int WIDTH = PREVIEW_W + GAP_W + GRID_W + 2;

    private final ResourceLocation entityId;
    @Nullable
    private IDrawable jeiSlotDrawable;
    @Nullable
    private IGuiItemStackGroup jeiItemStacks;
    private int page = 0;
    private int syncedPage = -1;
    private int syncedPageSize = -1;
    @Nullable
    private MobLoot syncedMob;
    @Nullable
    private World cachedWorld;
    @Nullable
    private Entity cachedEntity;

    private SmallVanillaButton leftPageButton = new SmallVanillaButton(
        0, GRID_X - 2, getButtonsY(), BUTTON_SIZE, "<");
    private SmallVanillaButton rightPageButton = new SmallVanillaButton(
        1, WIDTH - BUTTON_SIZE, getButtonsY(), BUTTON_SIZE, ">");
    private SmallVanillaButton openSMTButton = new SmallVanillaButton(
        2,  2, getHeight() - 14 - 2, PREVIEW_W - 4, 14,
        I18n.format("jei.supermobtracker.loot.button.open"));

    public MobLootJeiRecipe(ResourceLocation entityId) {
        this.entityId = entityId;

        leftPageButton.setStaticTooltip(I18n.format("jei.supermobtracker.loot.button.previous"));
        rightPageButton.setStaticTooltip(I18n.format("jei.supermobtracker.loot.button.next"));
        refreshLayoutMetrics();
    }

    @Override
    public void getIngredients(@Nonnull IIngredients ingredients) {
        MobLoot mob = LootDump.getMob(entityId);
        if (mob == null || mob.drops.isEmpty()) return;

        List<ItemStack> stacks = new ArrayList<>(mob.drops.size());
        for (LootEntry entry : mob.drops) stacks.add(entry.stack.copy());
        ingredients.setOutputs(VanillaTypes.ITEM, stacks);
    }

    void bindJeiLayout(IDrawable slotDrawable, IGuiItemStackGroup itemStacks) {
        refreshLayoutMetrics();
        this.jeiSlotDrawable = slotDrawable;

        if (jeiItemStacks != itemStacks) {
            itemStacks.addTooltipCallback(
                (slotIndex, input, ingredient, tooltip) -> appendDropTooltip(slotIndex, tooltip));
        }

        jeiItemStacks = itemStacks;
        invalidateSlots();
        syncSlots(LootDump.getMob(entityId));
    }

    @Override
    public void drawInfo(@Nonnull Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        refreshLayoutMetrics();
        MobLoot mob = LootDump.getMob(entityId);
        drawBackground(minecraft, mob, mouseX, mouseY);
        if (mob == null) {
            minecraft.fontRenderer.drawString(I18n.format("jei.supermobtracker.loot.missing"), GRID_X, 76, 0xFF777777);
            return;
        }

        clampPage(mob);
        syncSlots(mob);
    }

    /**
     * Called by the JEI RecipeLayout mixin after JEI has rendered its item stacks,
     * to render additional information on top of the item stacks.
     */
    void drawOverlay(Minecraft minecraft, int offsetX, int offsetY) {
        refreshLayoutMetrics();
        MobLoot mob = LootDump.getMob(entityId);
        if (mob == null) return;

        clampPage(mob);
        drawPercentLabels(minecraft, mob, offsetX, offsetY);
    }

    @Nonnull
    @Override
    public List<String> getTooltipStrings(int mouseX, int mouseY) {
        MobLoot mob = LootDump.getMob(entityId);
        if (mob == null) return Collections.emptyList();

        // Left and right page buttons
        List<String> leftPageTooltip = leftPageButton.getTooltipHovered();
        if (!leftPageTooltip.isEmpty()) return leftPageTooltip;

        List<String> rightPageTooltip = rightPageButton.getTooltipHovered();
        if (!rightPageTooltip.isEmpty()) return rightPageTooltip;

        // Mob name
        if (mouseX >= HEADER_PADDING && mouseX < PREVIEW_W - HEADER_PADDING
                && mouseY >= HEADER_PADDING && mouseY < HEADER_PADDING + HEADER_H) {
            return Collections.singletonList(getDisplayName());
        }

        return Collections.emptyList();
    }

    @Override
    public boolean handleClick(@Nonnull Minecraft minecraft, int mouseX, int mouseY, int mouseButton) {
        refreshLayoutMetrics();
        MobLoot mob = LootDump.getMob(entityId);
        if (mob == null) return false;

        if (openSMTButton.mousePressed(minecraft, mouseX, mouseY)) {
            GuiScreen returnScreen = minecraft.currentScreen;
            minecraft.displayGuiScreen(new GuiMobTracker(entityId, returnScreen));
            return true;
        }

        if (getPageCount(mob) > 1) {
            if (leftPageButton.mousePressed(minecraft, mouseX, mouseY)) {
                if (page > 0) {
                    page--;
                    invalidateSlots();
                }

                return true;
            }

            if (rightPageButton.mousePressed(minecraft, mouseX, mouseY)) {
                if (page + 1 < getPageCount(mob)) {
                    page++;
                    invalidateSlots();
                }

                return true;
            }
        }

        return false;
    }

    public static ItemStack createAnchorStack(ResourceLocation entityId) {
        ItemStack anchor = new ItemStack(ModItems.JEI_ANCHOR);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ANCHOR_TAG, entityId.toString());
        anchor.setTagCompound(tag);

        return anchor;
    }

    public static boolean isAnchorStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == ModItems.JEI_ANCHOR
            && stack.hasTagCompound() && stack.getTagCompound().hasKey(ANCHOR_TAG);
    }

    @Nullable
    public static ResourceLocation getAnchorEntityId(ItemStack stack) {
        if (!isAnchorStack(stack)) return null;
        try {
            return new ResourceLocation(stack.getTagCompound().getString(ANCHOR_TAG));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void drawBackground(Minecraft minecraft, @Nullable MobLoot mob, int mouseX, int mouseY) {
        // Preview area - #303030 #909090
        Entity entity = getEntity(minecraft);
        if (entity != null) {
            GuiDrawingUtils.drawMobPreview(entityId, entity, 0, 0, PREVIEW_W,
                getHeight(), getPreviewRotation());
            GlStateManager.enableTexture2D();
        } else {
            Gui.drawRect(0, 0, PREVIEW_W, getHeight(), 0xFF404040);
        }

        Gui.drawRect(HEADER_PADDING - 1, HEADER_PADDING - 1, HEADER_RIGHT_X + 1, HEADER_BOTTOM_Y + 1, 0xFF303030);
        Gui.drawRect(HEADER_PADDING, HEADER_PADDING, HEADER_RIGHT_X, HEADER_BOTTOM_Y, 0xFF909090);

        FontRenderer font = minecraft.fontRenderer;
        String name = font.trimStringToWidth(getDisplayName(), HEADER_W - 2);
        int nameWidth = font.getStringWidth(name);
        font.drawStringWithShadow(
            name,
            HEADER_PADDING + (HEADER_W - nameWidth) / 2,
            HEADER_PADDING + (HEADER_H - font.FONT_HEIGHT) / 2 + 1,
            0xFFFFFF);

        openSMTButton.visible = mob != null;
        openSMTButton.drawButton(minecraft, mouseX, mouseY, 0.0F);

        // Slots - #F5F5F5 #C8C8C8
        Gui.drawRect(GRID_X - 2, -1, WIDTH, GRID_Y + getGridHeight() + 2, 0xFFF5F5F5);
        Gui.drawRect(GRID_X - 1, 0, WIDTH - 1, GRID_Y + getGridHeight() + 1, 0xFFC8C8C8);
        drawSlotBackgrounds(minecraft);

        // Page buttons
        int pageCount = mob != null ? getPageCount(mob) : 1;
        leftPageButton.visible = pageCount > 1;
        leftPageButton.enabled = page > 0;
        leftPageButton.drawButton(minecraft, mouseX, mouseY, 0.0F);

        if (pageCount > 1) {
            String pageText = I18n.format("jei.supermobtracker.loot.page", page + 1, pageCount);
            int pageTextWidth = font.getStringWidth(pageText);
            font.drawString(
                pageText,
                GRID_X + (GRID_W - pageTextWidth) / 2,
                getButtonsY() + (BUTTON_SIZE - font.FONT_HEIGHT + 1) / 2,
                0xFF000000);
        }

        rightPageButton.visible = pageCount > 1;
        rightPageButton.enabled = page + 1 < pageCount;
        rightPageButton.drawButton(minecraft, mouseX, mouseY, 0.0F);
    }

    private void drawSlotBackgrounds(Minecraft minecraft) {
        if (jeiSlotDrawable == null) return;

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        for (int slotIndex = 0; slotIndex < getPageSize(); slotIndex++) {
            jeiSlotDrawable.draw(minecraft, getSlotX(slotIndex), getSlotY(slotIndex));
        }
    }

    private void drawPercentLabels(Minecraft minecraft, MobLoot mob, int offsetX, int offsetY) {
        for (int slot = 0; slot < getPageSize(); slot++) {
            LootEntry entry = getDisplayedEntry(slot, mob);
            if (entry == null) continue;

            // render the percent in the bottom-right corner of the slot
            int x = offsetX + getSlotX(slot) + SLOT_SIZE - 1;
            int y = offsetY + getSlotY(slot) + SLOT_SIZE - 1;
            drawSmallText(minecraft.fontRenderer, formatPercent(entry.getPercent()), x, y, 0xFFFFFFFF);
        }
    }

    private void syncSlots(@Nullable MobLoot mob) {
        if (jeiItemStacks == null) return;
        int pageSize = getPageSize();
        if (syncedMob == mob && syncedPage == page && syncedPageSize == pageSize) return;

        for (int slot = 0; slot < Math.max(pageSize, syncedPageSize); slot++) {
            jeiItemStacks.init(slot, false, -10000, -10000);

            if (slot >= pageSize) continue;

            LootEntry entry = mob != null ? getDisplayedEntry(slot, mob) : null;
            if (entry == null) continue;

            ItemStack display = entry.stack.copy();
            display.setCount(1);
            jeiItemStacks.init(slot, false, getSlotX(slot), getSlotY(slot));
            jeiItemStacks.set(slot, display);
        }

        syncedMob = mob;
        syncedPage = page;
        syncedPageSize = pageSize;
    }

    private void appendDropTooltip(int slotIndex, List<String> tooltip) {
        MobLoot mob = LootDump.getMob(entityId);
        if (mob == null) return;

        LootEntry entry = getDisplayedEntry(slotIndex, mob);
        if (entry == null) return;

        tooltip.add("");
        tooltip.add(TextFormatting.GRAY + I18n.format("jei.supermobtracker.loot.chance",
            entry.getPercent()));
    }

    @Nullable
    private LootEntry getDisplayedEntry(int slotIndex, MobLoot mob) {
        int index = page * getPageSize() + slotIndex;
        return index >= 0 && index < mob.drops.size() ? mob.drops.get(index) : null;
    }

    @Nullable
    private LootEntry getDisplayedEntry(int mouseX, int mouseY, MobLoot mob) {
        if (mouseX < GRID_X || mouseY < GRID_Y) return null;

        int column = (mouseX - GRID_X) / SLOT_SIZE;
        int row = (mouseY - GRID_Y) / SLOT_SIZE;
        if (column < 0 || column >= GRID_COLS || row < 0 || row >= getGridRows()) return null;

        return getDisplayedEntry(row * GRID_COLS + column, mob);
    }

    private int getPageCount(MobLoot mob) {
        int pageSize = getPageSize();
        return Math.max(1, (mob.drops.size() + pageSize - 1) / pageSize);
    }

    private void clampPage(MobLoot mob) {
        page = Math.max(0, Math.min(page, getPageCount(mob) - 1));
    }

    private void invalidateSlots() {
        syncedMob = null;
        syncedPage = -1;
        syncedPageSize = -1;
    }

    private void refreshLayoutMetrics() {
        leftPageButton.x = GRID_X - 2;
        leftPageButton.y = getButtonsY();
        rightPageButton.x = WIDTH - BUTTON_SIZE;
        rightPageButton.y = getButtonsY();
        openSMTButton.x = 2;
        openSMTButton.y = getHeight() - openSMTButton.height - 2;
    }

    private static int getGridRows() {
        return ModConfig.clientJeiMobLootRows;
    }

    private static int getGridHeight() {
        return SLOT_SIZE * getGridRows();
    }

    private static int getPageSize() {
        return GRID_COLS * getGridRows();
    }

    private static int getButtonsY() {
        return getGridHeight() + 4 + 1;
    }

    public static int getHeight() {
        return 2 + getGridHeight() + 2 + BUTTON_SIZE;
    }

    private int getSlotX(int slotIndex) {
        return GRID_X + (slotIndex % GRID_COLS) * SLOT_SIZE;
    }

    private int getSlotY(int slotIndex) {
        return GRID_Y + (slotIndex / GRID_COLS) * SLOT_SIZE;
    }

    @Nullable
    private Entity getEntity(Minecraft minecraft) {
        if (minecraft.world == null) return null;
        if (cachedWorld == minecraft.world) return cachedEntity;

        cachedWorld = minecraft.world;
        try {
            cachedEntity = EntityList.createEntityByIDFromName(entityId, minecraft.world);
        } catch (Exception ignored) {
            cachedEntity = null;
        }

        return cachedEntity;
    }

    private String getDisplayName() {
        Entity entity = getEntity(Minecraft.getMinecraft());
        return TranslationUtils.formatEntityName(entityId, entity, ClientSettings.i18nNames);
    }

    private static float getPreviewRotation() {
        return (System.currentTimeMillis() % 10000L) * 360.0F / 10000.0F;
    }

    private static void drawSmallText(FontRenderer font, String text, int x, int y, int color) {
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableBlend();
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.5F, 0.5F, 1.0F);
        font.drawStringWithShadow(text, x * 2 - font.getStringWidth(text), y * 2 - font.FONT_HEIGHT, color);
        GlStateManager.popMatrix();
        GlStateManager.enableBlend();
        GlStateManager.enableDepth();
    }

    private static String formatPercent(double percent) {
        if (percent >= 100.0D) return String.format(Locale.ROOT, "%.0f", percent / 100.0D);
        if (percent >= 10.0D) return String.format(Locale.ROOT, "%.0f%%", percent);
        if (percent >= 1.0D) return String.format(Locale.ROOT, "%.1f%%", percent);
        if (percent >= 0.01D) return String.format(Locale.ROOT, "%.2f%%", percent);

        return String.format(Locale.ROOT, "%.3f%%", percent);
    }
}
