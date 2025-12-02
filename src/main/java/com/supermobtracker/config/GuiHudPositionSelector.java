package com.supermobtracker.config;

import java.io.IOException;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import com.supermobtracker.config.ModConfig.HudPosition;


public class GuiHudPositionSelector extends GuiScreen {
    private static final int PADDING = 10;

    // Button IDs for positions (0-8 for grid)
    private static final int BTN_TOP_LEFT = 0;
    private static final int BTN_TOP_CENTER = 1;
    private static final int BTN_TOP_RIGHT = 2;
    private static final int BTN_CENTER_LEFT = 3;
    private static final int BTN_CENTER = 4;
    private static final int BTN_CENTER_RIGHT = 5;
    private static final int BTN_BOTTOM_LEFT = 6;
    private static final int BTN_BOTTOM_CENTER = 7;
    private static final int BTN_BOTTOM_RIGHT = 8;

    private final GuiScreen parentScreen;

    public GuiHudPositionSelector() {
        this(null);
    }

    public GuiHudPositionSelector(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int gridStartX = PADDING;
        int gridStartY = PADDING;

        int gridWidth = width - 2 * PADDING;
        int gridHeight = height - 2 * PADDING;

        // Create 3x3 grid of position buttons
        HudPosition[] positions = {
            HudPosition.TOP_LEFT, HudPosition.TOP_CENTER, HudPosition.TOP_RIGHT,
            HudPosition.CENTER_LEFT, HudPosition.CENTER, HudPosition.CENTER_RIGHT,
            HudPosition.BOTTOM_LEFT, HudPosition.BOTTOM_CENTER, HudPosition.BOTTOM_RIGHT
        };
        String[] labels = {
            "top_left", "top_center", "top_right",
            "center_left", "center", "center_right",
            "bottom_left", "bottom_center", "bottom_right"
        };
        String[] buttonLabels = new String[9];
        for (int i = 0; i < labels.length; i++) buttonLabels[i] = I18n.format("gui.supermobtracker.hudPosition." + labels[i]);

        int btnH = mc.fontRenderer.FONT_HEIGHT + 4;
        int btnW = 0;
        for (String label : buttonLabels) {
            int labelW = mc.fontRenderer.getStringWidth(label) + 10;
            if (labelW > btnW) btnW = labelW;
        }

        int[] positionsX = {
            gridStartX,
            gridStartX + gridWidth / 2 - btnW / 2,
            gridStartX + gridWidth - btnW
        };
        int[] positionsY = {
            gridStartY,
            gridStartY + gridHeight / 2 - btnH / 2,
            gridStartY + gridHeight - btnH
        };

        for (int btn = 0; btn < positions.length; btn++) {
            int btnX = positionsX[btn % 3];
            int btnY = positionsY[btn / 3];
            HudPosition pos = positions[btn];
            String label = buttonLabels[btn];

            // FIXME: text is not vertically centered
            buttonList.add(new GuiButton(btn, btnX, btnY, btnW, btnH, label));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id >= BTN_TOP_LEFT && button.id <= BTN_BOTTOM_RIGHT) {
            HudPosition[] positions = {
                HudPosition.TOP_LEFT, HudPosition.TOP_CENTER, HudPosition.TOP_RIGHT,
                HudPosition.CENTER_LEFT, HudPosition.CENTER, HudPosition.CENTER_RIGHT,
                HudPosition.BOTTOM_LEFT, HudPosition.BOTTOM_CENTER, HudPosition.BOTTOM_RIGHT
            };

            ModConfig.setClientHudPosition(positions[button.id]);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // ESC key to return to parent
        if (keyCode == 1 && parentScreen != null) {
            mc.displayGuiScreen(parentScreen);
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }
}
