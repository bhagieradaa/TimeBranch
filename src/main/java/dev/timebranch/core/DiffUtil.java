package dev.timebranch.core;

import java.util.*;

/** LCS line diff: O(n*m) time and space for files up to 500 lines each. */
public final class DiffUtil {
    public record Line(char kind, String text) {}
    private DiffUtil() {}
    public static List<Line> compare(String before, String after) {
        String[] a = (before == null ? "" : before).split("\\R", -1);
        String[] b = (after == null ? "" : after).split("\\R", -1);
        // Keep the UI responsive and memory bounded for very large text files.
        if ((long)a.length * b.length > 250_000L) return positional(a,b);
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--)
            for (int j = b.length - 1; j >= 0; j--)
                dp[i][j] = a[i].equals(b[j]) ? 1 + dp[i+1][j+1] : Math.max(dp[i+1][j],dp[i][j+1]);
        List<Line> result = new ArrayList<>();
        int i = 0,j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                result.add(new Line(' ', a[i])); i++; j++;
            } else if (j < b.length && (i == a.length || dp[i][j+1] > dp[i+1][j])) {
                result.add(new Line('+', b[j++])); 
            } else {
                result.add(new Line('-', a[i++]));
            }
        }
        return result;
    }
    private static List<Line> positional(String[] a, String[] b) {
        List<Line> result = new ArrayList<>();
        for (int i = 0; i < Math.max(a.length,b.length); i++) {
            if (i >= a.length) result.add(new Line('+', b[i]));
            else if (i >= b.length) result.add(new Line('-', a[i]));
            else if (a[i].equals(b[i])) result.add(new Line(' ', a[i]));
            else { result.add(new Line('-',a[i])); result.add(new Line('+',b[i])); }
        }
        return result;
    }
}
