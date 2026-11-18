package dev.timebranch.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DiffUtilTest {
    @Test void showsMissingFilesAndLineEndingChangesWithoutInventingLines() {
        assertEquals(List.of(), DiffUtil.compare(null, ""));
        assertEquals(List.of(new DiffUtil.Line('+', "a", "\r\n")), DiffUtil.compare(null, "a\r\n"));
        assertEquals(List.of(new DiffUtil.Line('-', "a", "\n")), DiffUtil.compare("a\n", null));
        assertEquals(List.of(new DiffUtil.Line('-', "a", ""), new DiffUtil.Line('+', "a", "\n")),
                DiffUtil.compare("a", "a\n"));
        assertNotEquals(DiffUtil.compare("a\n", "a\n"), DiffUtil.compare("a\r\n", "a\n"));
    }

    @Test void largeFileInsertionDoesNotTurnMatchingSuffixIntoChanges() {
        String source = IntStream.range(0, 2000).mapToObj(String::valueOf).collect(Collectors.joining("\n"));
        List<DiffUtil.Line> diff = DiffUtil.compare(source, "new line\n" + source);
        assertEquals(2001, diff.size());
        assertEquals(new DiffUtil.Line('+', "new line", "\n"), diff.get(0));
        assertTrue(diff.subList(1, diff.size()).stream().allMatch(line -> line.kind() == ' '));
    }

    @Test void diffReconstructsBothOriginalInputs() {
        String before = "one\r\ntwo\rthree\n";
        String after = "one\r\ninserted\nthree";
        List<DiffUtil.Line> lines = DiffUtil.compare(before, after);
        assertEquals(before, reconstruct(lines, '+'));
        assertEquals(after, reconstruct(lines, '-'));
    }

    private static String reconstruct(List<DiffUtil.Line> lines, char excluded) {
        return lines.stream().filter(line -> line.kind() != excluded)
                .map(line -> line.text() + line.ending()).collect(Collectors.joining());
    }
}
