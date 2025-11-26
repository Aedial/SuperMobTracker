package com.supermobtracker.util;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

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

    public static String[] wrapText(String text, int maxLineLength) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > maxLineLength) {
                lines.add(currentLine.toString().trim());
                currentLine = new StringBuilder();
            }
            currentLine.append(word).append(" ");
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString().trim());
        }

        return lines.toArray(new String[0]);
    }
}
