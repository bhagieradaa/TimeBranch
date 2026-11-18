package dev.timebranch.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Line diff using LCS within a bounded comparison window. */
public final class DiffUtil {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final int MAX_LCS_CELLS = 250_000;

    public record Line(char kind, String text, String ending) {}

    private record SourceLine(String text, String ending) {
        Line display(char kind) {
            return new Line(kind, text, ending);
        }
    }

    private DiffUtil() {}

    private static List<SourceLine> split(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<SourceLine> lines = new ArrayList<>();
        var matcher = LINE_BREAK.matcher(text);
        int start = 0;
        while (matcher.find()) {
            lines.add(new SourceLine(text.substring(start, matcher.start()), matcher.group()));
            start = matcher.end();
        }
        if (start < text.length()) lines.add(new SourceLine(text.substring(start), ""));
        return lines;
    }

    public static List<Line> compare(String before, String after) {
        List<SourceLine> oldLines = split(before);
        List<SourceLine> newLines = split(after);
        List<Line> result = new ArrayList<>();
        int start = 0;
        int oldEnd = oldLines.size();
        int newEnd = newLines.size();
        while (start < oldEnd && start < newEnd && oldLines.get(start).equals(newLines.get(start))) {
            result.add(oldLines.get(start++).display(' '));
        }
        while (oldEnd > start && newEnd > start && oldLines.get(oldEnd - 1).equals(newLines.get(newEnd - 1))) {
            oldEnd--;
            newEnd--;
        }
        List<SourceLine> a = oldLines.subList(start, oldEnd);
        List<SourceLine> b = newLines.subList(start, newEnd);
        if (a.isEmpty() || b.isEmpty() || (long) a.size() * b.size() > MAX_LCS_CELLS) {
            positional(a, b, result);
        } else {
            lcs(a, b, result);
        }
        for (int i = oldEnd; i < oldLines.size(); i++) result.add(oldLines.get(i).display(' '));
        return result;
    }

    private static void lcs(List<SourceLine> a, List<SourceLine> b, List<Line> result) {
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--)
            for (int j = b.size() - 1; j >= 0; j--)
                dp[i][j] = a.get(i).equals(b.get(j)) ? 1 + dp[i + 1][j + 1] : Math.max(dp[i + 1][j], dp[i][j + 1]);
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                result.add(a.get(i++).display(' '));
                j++;
            } else if (j < b.size() && (i == a.size() || dp[i][j + 1] > dp[i + 1][j])) {
                result.add(b.get(j++).display('+'));
            } else {
                result.add(a.get(i++).display('-'));
            }
        }
    }

    private static void positional(List<SourceLine> a, List<SourceLine> b, List<Line> result) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i >= a.size()) result.add(b.get(i).display('+'));
            else if (i >= b.size()) result.add(a.get(i).display('-'));
            else if (a.get(i).equals(b.get(i))) result.add(a.get(i).display(' '));
            else {
                result.add(a.get(i).display('-'));
                result.add(b.get(i).display('+'));
            }
        }
    }
}
