package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.FontRenderer;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.client.gui.widgets.GuiHintArgument;


public class GuiScrollableTextWidget extends GuiScrollableWidget {
    public enum Layout {
        WRAPPED,
        SINGLE,
        ELIDED
    }

    private static final Set<String> PLACEHOLDER_WARNINGS = Collections.synchronizedSet(new HashSet<>());

    private final int ellipsisWidth;

    private final FontRenderer fontRenderer;
    private final String text;
    private final List<ArgumentRange> argumentRanges;
    private final int color;
    private final int hoverColor;
    private final int lineHeight;
    private final int lineSpacing;
    private final int horizontalOffset;
    private final int widthOffset;
    private final Layout layout;
    private List<TextLine> lines = Collections.emptyList();
    private GuiHintArgument hoveredArgument;
    private int cachedWidth = -1;

    public GuiScrollableTextWidget(FontRenderer fontRenderer, String text, int color, int lineHeight, Layout layout,
            int horizontalOffset, int widthOffset) {
        this(fontRenderer, text, color, color, lineHeight, 0, layout, horizontalOffset, widthOffset);
    }

    public GuiScrollableTextWidget(FontRenderer fontRenderer, String text, int color, int hoverColor, int lineHeight,
            int lineSpacing, Layout layout, int horizontalOffset, int widthOffset) {
        this(fontRenderer, text, color, hoverColor, lineHeight, lineSpacing, layout, horizontalOffset, widthOffset,
            null, Collections.emptyList());
    }

    public GuiScrollableTextWidget(FontRenderer fontRenderer, String translationKey, String translation, int color,
            int hoverColor, int lineHeight, int lineSpacing, Layout layout, int horizontalOffset, int widthOffset,
            List<GuiHintArgument> arguments) {
        this(fontRenderer, translation, color, hoverColor, lineHeight, lineSpacing, layout, horizontalOffset,
            widthOffset, translationKey, arguments);
    }

    private GuiScrollableTextWidget(FontRenderer fontRenderer, String text, int color, int hoverColor, int lineHeight,
            int lineSpacing, Layout layout, int horizontalOffset, int widthOffset, String translationKey,
            List<GuiHintArgument> arguments) {
        FormattedText formatted = formatText(translationKey, text, arguments);
        this.fontRenderer = fontRenderer;
        this.text = formatted.text;
        this.argumentRanges = formatted.argumentRanges;
        this.color = color;
        this.hoverColor = hoverColor;
        this.lineHeight = lineHeight;
        this.lineSpacing = lineSpacing;
        this.layout = layout;
        this.horizontalOffset = horizontalOffset;
        this.widthOffset = widthOffset;
        this.ellipsisWidth = Math.max(1, fontRenderer.getStringWidth("..."));
    }

    @Override
    public int getHeight(int width) {
        updateLines(width);
        return lineHeight * lines.size() + lineSpacing * Math.max(0, lines.size() - 1);
    }

    @Override
    public void draw(int x, int y, int width, int remainingYSpace, boolean hovered) {
        updateLines(width);
        int drawY = y;
        int drawColor = hovered ? hoverColor : color;
        GuiHintArgument activeArgument = hovered ? hoveredArgument : null;
        for (int i = 0; i < lines.size(); i++) {
            drawLine(lines.get(i), x + horizontalOffset, drawY, drawColor, activeArgument);
            drawY += lineHeight + (i < lines.size() - 1 ? lineSpacing : 0);
        }
    }

    @Override
    public boolean onClick(int mouseX, int mouseY, int x, int y, int width, int mouseButton) {
        GuiHintArgument argument = getArgumentAt(mouseX, mouseY, x, y, width);
        if (argument != null) return argument.onClick(mouseButton);

        return super.onClick(mouseX, mouseY, x, y, width, mouseButton);
    }

    @Override
    public void onHover(int mouseX, int mouseY, int x, int y, int width) {
        hoveredArgument = getArgumentAt(mouseX, mouseY, x, y, width);
        super.onHover(mouseX, mouseY, x, y, width);
    }

    @Override
    public List<String> getTooltip(int mouseX, int mouseY, int x, int y, int width) {
        GuiHintArgument argument = getArgumentAt(mouseX, mouseY, x, y, width);
        if (argument != null) return argument.getTooltip();

        return super.getTooltip(mouseX, mouseY, x, y, width);
    }

    private void drawLine(TextLine line, int x, int y, int defaultColor, GuiHintArgument activeArgument) {
        if (line.text.isEmpty()) return;

        int segmentStart = 0;
        GuiHintArgument segmentArgument = getArgument(line.sourceIndexes[0]);
        for (int index = 1; index <= line.text.length(); index++) {
            GuiHintArgument argument = index < line.text.length() ? getArgument(line.sourceIndexes[index]) : null;
            if (argument == segmentArgument && index < line.text.length()) continue;

            String segment = line.text.substring(segmentStart, index);
            int segmentColor = segmentArgument == null ? defaultColor
                : segmentArgument == activeArgument ? segmentArgument.getHoverColor() : segmentArgument.getColor();
            fontRenderer.drawString(segment, x, y, segmentColor);
            x += fontRenderer.getStringWidth(segment);
            segmentStart = index;
            segmentArgument = argument;
        }
    }

    private GuiHintArgument getArgumentAt(int mouseX, int mouseY, int x, int y, int width) {
        updateLines(width);
        int lineY = y;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            TextLine line = lines.get(lineIndex);
            if (mouseY >= lineY && mouseY < lineY + lineHeight) {
                int charX = x + horizontalOffset;
                for (int index = 0; index < line.text.length(); index++) {
                    int charWidth = fontRenderer.getStringWidth(line.text.substring(index, index + 1));
                    if (mouseX >= charX && mouseX < charX + charWidth) {
                        return getArgument(line.sourceIndexes[index]);
                    }

                    charX += charWidth;
                }

                return null;
            }

            lineY += lineHeight + (lineIndex < lines.size() - 1 ? lineSpacing : 0);
        }

        return null;
    }

    private GuiHintArgument getArgument(int sourceIndex) {
        for (ArgumentRange range : argumentRanges) {
            if (sourceIndex >= range.start && sourceIndex < range.end) return range.argument;
        }

        return null;
    }

    private void updateLines(int width) {
        int textAreaWidth = Math.max(1, width + widthOffset);
        if (cachedWidth == textAreaWidth) return;

        cachedWidth = textAreaWidth;
        if (layout == Layout.WRAPPED) {
            lines = wrapText(textAreaWidth);
        } else if (layout == Layout.ELIDED) {
            String elided = fontRenderer.trimStringToWidth(text, textAreaWidth - ellipsisWidth);
            if (!elided.equals(text)) elided += "...";
            lines = Collections.singletonList(createTextLine(elided, 0));
        } else {
            lines = Collections.singletonList(createTextLine(text, 0));
        }
    }

    private List<TextLine> wrapText(int maxWidth) {
        List<TextLine> result = new ArrayList<>();
        if (text.isEmpty() || maxWidth <= 0) return result;

        int paragraphStart = 0;
        while (paragraphStart <= text.length()) {
            int newline = text.indexOf('\n', paragraphStart);
            int paragraphEnd = newline < 0 ? text.length() : newline;
            if (paragraphEnd > paragraphStart && text.charAt(paragraphEnd - 1) == '\r') paragraphEnd--;

            wrapParagraph(paragraphStart, paragraphEnd, maxWidth, result);
            if (newline < 0) break;

            paragraphStart = newline + 1;
        }

        return result;
    }

    private void wrapParagraph(int start, int end, int maxWidth, List<TextLine> result) {
        LineBuilder line = new LineBuilder();
        int index = start;
        while (index < end) {
            while (index < end && Character.isWhitespace(text.charAt(index))) index++;
            if (index >= end) break;

            int wordStart = index;
            while (index < end && !Character.isWhitespace(text.charAt(index))) index++;
            line = appendWord(line, wordStart, index, maxWidth, result);
        }

        if (!line.isEmpty()) result.add(line.build());
        if (start == end) result.add(createTextLine("", start));
    }

    private LineBuilder appendWord(LineBuilder line, int wordStart, int wordEnd, int maxWidth,
            List<TextLine> result) {
        int wordWidth = fontRenderer.getStringWidth(text.substring(wordStart, wordEnd));
        if (!line.isEmpty()) {
            int separatorIndex = wordStart - 1;
            int separatorWidth = fontRenderer.getStringWidth(" ");
            if (line.width + separatorWidth + wordWidth <= maxWidth) {
                line.append(' ', separatorIndex, separatorWidth);
                line.append(text, wordStart, wordEnd, fontRenderer);
                return line;
            }

            result.add(line.build());
            line = new LineBuilder();
        }

        int index = wordStart;
        while (index < wordEnd) {
            int charWidth = fontRenderer.getStringWidth(text.substring(index, index + 1));
            if (!line.isEmpty() && line.width + charWidth > maxWidth) {
                result.add(line.build());
                line = new LineBuilder();
            }

            line.append(text.charAt(index), index, charWidth);
            index++;

            if (line.width >= maxWidth && index < wordEnd) {
                result.add(line.build());
                line = new LineBuilder();
            }
        }

        return line;
    }

    private TextLine createTextLine(String lineText, int startIndex) {
        int[] sourceIndexes = new int[lineText.length()];
        for (int index = 0; index < lineText.length(); index++) {
            sourceIndexes[index] = startIndex + index < text.length() ? startIndex + index : -1;
        }

        return new TextLine(lineText, sourceIndexes);
    }

    private static FormattedText formatText(String translationKey, String translation,
            List<GuiHintArgument> arguments) {
        if (translationKey == null) return new FormattedText(translation, Collections.emptyList());

        int placeholderCount = countPlaceholders(translation);
        if (placeholderCount != arguments.size()) {
            String warning = translationKey + ':' + arguments.size() + ':' + placeholderCount;
            if (PLACEHOLDER_WARNINGS.add(warning)) {
                SuperMobTracker.LOGGER.warn(
                    "Spawn hint '{}' has {} arguments but its active translation contains {} placeholders.",
                    translationKey, arguments.size(), placeholderCount);
            }

            return new FormattedText(translation, Collections.emptyList());
        }

        StringBuilder formatted = new StringBuilder();
        List<ArgumentRange> ranges = new ArrayList<>();
        int start = 0;
        for (GuiHintArgument argument : arguments) {
            int placeholder = translation.indexOf("{}", start);
            formatted.append(translation, start, placeholder);
            int argumentStart = formatted.length();
            formatted.append(argument.getText());
            ranges.add(new ArgumentRange(argumentStart, formatted.length(), argument));
            start = placeholder + 2;
        }

        formatted.append(translation.substring(start));
        return new FormattedText(formatted.toString(), ranges);
    }

    private static int countPlaceholders(String text) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("{}", index)) >= 0) {
            count++;
            index += 2;
        }

        return count;
    }

    private static class FormattedText {
        final String text;
        final List<ArgumentRange> argumentRanges;

        FormattedText(String text, List<ArgumentRange> argumentRanges) {
            this.text = text;
            this.argumentRanges = argumentRanges;
        }
    }

    private static class ArgumentRange {
        final int start;
        final int end;
        final GuiHintArgument argument;

        ArgumentRange(int start, int end, GuiHintArgument argument) {
            this.start = start;
            this.end = end;
            this.argument = argument;
        }
    }

    private static class TextLine {
        final String text;
        final int[] sourceIndexes;

        TextLine(String text, int[] sourceIndexes) {
            this.text = text;
            this.sourceIndexes = sourceIndexes;
        }
    }

    private static class LineBuilder {
        final StringBuilder text = new StringBuilder();
        final List<Integer> sourceIndexes = new ArrayList<>();
        int width;

        boolean isEmpty() {
            return text.length() == 0;
        }

        void append(char character, int sourceIndex, int characterWidth) {
            text.append(character);
            sourceIndexes.add(sourceIndex);
            width += characterWidth;
        }

        void append(String source, int start, int end, FontRenderer fontRenderer) {
            for (int index = start; index < end; index++) {
                append(source.charAt(index), index, fontRenderer.getStringWidth(source.substring(index, index + 1)));
            }
        }

        TextLine build() {
            int[] indexes = new int[sourceIndexes.size()];
            for (int index = 0; index < indexes.length; index++) indexes[index] = sourceIndexes.get(index);
            return new TextLine(text.toString(), indexes);
        }
    }
}
