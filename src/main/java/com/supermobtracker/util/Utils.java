package com.supermobtracker.util;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

import net.minecraft.client.gui.FontRenderer;


public class Utils {
    /**
     * Formats a list of integers into a range string.
     * @param list List of integers (e.g., [1,2,3,5,7,8,9])
     * @return Formatted range string (e.g., "1-3, 5, 7-9")
     */
    public static String formatRangeFromList(List<Integer> list, String separator) {
        if (list.isEmpty()) return "";

        if (separator == null) separator = ", ";

        list = list.stream().distinct().collect(Collectors.toList());
        Collections.sort(list);

        StringBuilder rangeStr = new StringBuilder();

        for (int i = 0; i < list.size() - 1; i++) {
            int start = i;
            while (i < list.size() - 1 && list.get(i + 1) == list.get(i) + 1) i++;

            if (start == i) {
                rangeStr.append(list.get(start));
            } else {
                rangeStr.append(list.get(start)).append("-").append(list.get(i));
            }

            rangeStr.append(separator);
        }

        String s = rangeStr.toString();
        if (s.endsWith(separator)) s = s.substring(0, s.length() - separator.length());

        return s;
    }

    /**
     * Wrap text into multiple lines based on maximum width.
     * @param fr       FontRenderer to measure text width
     * @param text     Text to wrap
     * @param maxWidth Maximum width in pixels
     * @return Array of wrapped lines
     */
    public static List<String> wrapText(FontRenderer fr, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) return lines;

        String[] paragraphs = text.split("\\r?\\n");
        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty()) { lines.add(""); continue; }

            String[] words = p.split(" ");
            StringBuilder cur = new StringBuilder();

            for (String w : words) {
                if (w.isEmpty()) continue;  // Skip multiple spaces

                int wordWidth = fr.getStringWidth(w);
                if (wordWidth <= maxWidth) {
                    // Word fits on its own.
                    if (cur.length() == 0) {
                        cur.append(w);
                    } else {
                        String test = cur.toString() + " " + w;
                        if (fr.getStringWidth(test) <= maxWidth) {
                            cur.append(" ").append(w);
                        } else {
                            lines.add(cur.toString());
                            cur.setLength(0);
                            cur.append(w);
                        }
                    }
                } else {
                    // Word itself is too long: split into chunks that fit maxWidth.
                    int start = 0;
                    int wlen = w.length();

                    while (start < wlen) {
                        if (cur.length() > 0) {
                            // Try to append as many chars from the word to the current line as will fit.
                            int end = start;
                            for (int e = start; e < wlen; e++) {
                                String test = cur.toString() + w.substring(start, e + 1);
                                if (fr.getStringWidth(test) <= maxWidth) {
                                    end = e + 1;
                                } else break;
                            }

                            if (end == start) {
                                // Can't fit even a single additional char on the current line: flush it.
                                lines.add(cur.toString());
                                cur.setLength(0);
                                continue;
                            }

                            cur.append(w.substring(start, end));
                            start = end;

                            // After filling the current line, flush it so further chunks start on a new line.
                            lines.add(cur.toString());
                            cur.setLength(0);
                        } else {
                            // Current line is empty: build the largest chunk starting at 'start' that fits.
                            int end = start + 1;
                            for (int e = start + 1; e <= wlen; e++) {
                                String piece = w.substring(start, e);
                                if (fr.getStringWidth(piece) <= maxWidth) {
                                    end = e;
                                } else break;
                            }

                            // Ensure progress: if a single char is wider than maxWidth, force one char.
                            if (end == start) end = start + 1;

                            String piece = w.substring(start, end);
                            cur.append(piece);
                            start = end;

                            // If the chunk filled the line exactly (or can't accept more), flush it now.
                            if (fr.getStringWidth(cur.toString()) >= maxWidth) {
                                lines.add(cur.toString());
                                cur.setLength(0);
                            }
                        }
                    }
                }
            }

            if (cur.length() > 0) lines.add(cur.toString());
        }

        return lines;
    }
}
