package com.supermobtracker.client.gui.widgets;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;

import com.supermobtracker.client.gui.GuiScrollableTextWidget;
import com.supermobtracker.spawn.BiomeDimensionMapper;


public class GuiUnknownDimensionWidget extends GuiScrollableTextWidget {
    public GuiUnknownDimensionWidget(FontRenderer fontRenderer, String text, int color, int lineHeight,
            int horizontalOffset, int widthOffset) {
        super(fontRenderer, text, color, lineHeight, Layout.WRAPPED, horizontalOffset, widthOffset);
    }

    @Override
    public List<String> getTooltip() {
        String key = BiomeDimensionMapper.isBackgroundSamplingActive()
            ? "gui.mobtracker.dimension.unknown.tooltip.scanning"
            : "gui.mobtracker.dimension.unknown.tooltip.complete";

        return Collections.singletonList(I18n.format(key));
    }
}
