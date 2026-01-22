package com.supermobtracker.client.gui;

import java.util.Collections;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.supermobtracker.client.util.GuiDrawingUtils;


/**
 * A modal dialog that displays an enlarged entity preview.
 * The modal takes 75% of the smaller screen dimension and renders
 * a slowly rotating entity in the center.
 */
public class GuiEntityPreviewModal {

    private final GuiScreen parent;
    private final ResourceLocation entityId;
    private final Entity entity;
    private final String entityName;

    private static final int TITLE_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 10;

    private boolean visible = false;
    private Float rotationLocked = null;

    // Modal dimensions (calculated on show)
    private int modalX, modalY, modalWidth, modalHeight;

    public GuiEntityPreviewModal(GuiScreen parent, ResourceLocation entityId, Entity entity, String entityName) {
        this.parent = parent;
        this.entityId = entityId;
        this.entity = entity;
        this.entityName = entityName;
    }

    /**
     * Shows the modal and calculates dimensions based on current screen size.
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    public void show(int screenWidth, int screenHeight) {
        // Modal size is 75% of the smaller screen dimension
        int smallerDimension = Math.min(screenWidth, screenHeight);
        this.modalWidth = (int) (smallerDimension * 0.75f);
        this.modalHeight = this.modalWidth + TITLE_HEIGHT + FOOTER_HEIGHT;

        // Center the modal on screen
        this.modalX = (screenWidth - modalWidth) / 2;
        this.modalY = (screenHeight - modalHeight) / 2;

        this.visible = true;
        this.rotationLocked = null;
    }

    /**
     * Hides the modal.
     */
    public void hide() {
        this.visible = false;
        this.rotationLocked = null;
    }

    /**
     * @return true if the modal is currently visible
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Checks if mouse is over the modal area.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if mouse is within modal bounds
     */
    public boolean isMouseOver(int mouseX, int mouseY) {
        if (!visible) return false;

        return mouseX >= modalX && mouseX <= modalX + modalWidth &&
               mouseY >= modalY && mouseY <= modalY + modalHeight;
    }

    /**
     * Handles mouse clicks.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param mouseButton Which button was clicked
     * @return true if the click was handled (consumed)
     */
    public boolean handleClick(int mouseX, int mouseY, int mouseButton) {
        if (!visible) return false;

        // Clicking outside the modal closes it
        if (!isMouseOver(mouseX, mouseY)) {
            hide();

            return true;
        }

        // Consume click if inside modal (don't pass to parent)
        return true;
    }

    /**
     * Handles key presses.
     * @param keyCode The key code
     * @return true if the key was handled
     */
    public boolean handleKey(int keyCode) {
        if (!visible) return false;

        // Toggle rotation until closed
        if (keyCode == Keyboard.KEY_SPACE) {
            if (rotationLocked == null) {
                rotationLocked = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;
            } else {
                rotationLocked = null;
            }

            return true;
        }

        // Escape closes the modal
        if (keyCode == Keyboard.KEY_ESCAPE) {
            hide();

            return true;
        }

        return false;
    }

    /**
     * Draws the modal.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param partialTicks Partial tick time
     * @param fontRenderer Font renderer for text
     */
    public void draw(int mouseX, int mouseY, float partialTicks, FontRenderer fontRenderer) {
        if (!visible || entity == null) return;

        // Draw semi-transparent background overlay
        Gui.drawRect(0, 0, parent.width, parent.height, 0xC0000000);

        // Draw modal border
        Gui.drawRect(modalX - 2, modalY - 2, modalX + modalWidth + 2, modalY + modalHeight + 2, 0xFF606060);
        // Draw modal background
        Gui.drawRect(modalX, modalY, modalX + modalWidth, modalY + modalHeight, 0xFF202020);

        // Calculate preview area (leave space for title)
        int previewX = modalX + 10;
        int previewY = modalY + TITLE_HEIGHT;
        int previewSize = modalWidth - 20;

        // Draw entity name as title
        String title = entityName;
        int titleWidth = fontRenderer.getStringWidth(title);
        int titleX = modalX + (modalWidth - titleWidth) / 2;
        fontRenderer.drawString(title, titleX, modalY + 8, 0xFFFFFF);

        // Draw the entity preview (slowly rotating)
        float rotationY = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;
        if (rotationLocked != null) rotationY = rotationLocked;
        GuiDrawingUtils.drawMobPreview(entityId, entity, previewX, previewY, previewSize, rotationY);

        // Draw footer instruction
        String footer = I18n.format("gui.mobtracker.closeModal");
        int footerWidth = fontRenderer.getStringWidth(footer);
        int footerX = modalX + (modalWidth - footerWidth) / 2;
        fontRenderer.drawString(footer, footerX, modalY + modalHeight - FOOTER_HEIGHT - 4, 0xAAAAAA);
    }

    /**
     * Draws tooltips for the modal.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param fontRenderer Font renderer
     */
    public void drawTooltips(int mouseX, int mouseY, FontRenderer fontRenderer) {
        // No tooltips for now
    }
}
