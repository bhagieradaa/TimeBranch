package dev.timebranch;

import dev.timebranch.core.VersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileOperationsTest {
    @TempDir Path directory;

    @Test void importPreservesContentsAndUndoTreatsItAsOneOperation() throws IOException {
        VersionRepository repo = new VersionRepository();
        String text = "café\r\nnext\n";
        Path source = Files.writeString(directory.resolve("Notes.txt"), text);
        assertEquals("Notes.txt", TimeBranchApp.importSource(repo, source));
        assertEquals(text, repo.content("Notes.txt"));
        assertThrows(IllegalArgumentException.class, () -> TimeBranchApp.importSource(repo, source));
        assertTrue(repo.undo());
        assertFalse(repo.files().containsKey("Notes.txt"));
        assertFalse(repo.canUndo());
    }

    @Test void invalidOrOversizedImportLeavesWorkspaceUntouched() throws IOException {
        VersionRepository repo = new VersionRepository();
        Path malformed = Files.write(directory.resolve("Invalid.txt"), new byte[]{(byte) 0xff});
        Path oversized = Files.write(directory.resolve("Large.txt"), new byte[VersionRepository.MAX_FILE_BYTES + 1]);
        assertThrows(IOException.class, () -> TimeBranchApp.importSource(repo, malformed));
        assertThrows(IOException.class, () -> TimeBranchApp.importSource(repo, oversized));
        assertFalse(repo.dirty());
        assertFalse(repo.canUndo());
    }

    @Test void exportSelectedPreservesExactTextAndCannotOverwriteWorkspace() throws IOException {
        Path workspace = Files.writeString(directory.resolve("workspace.tbr"), "saved data");
        Path target = directory.resolve("File.txt");
        TimeBranchApp.exportSource(target, workspace, "é\r\n");
        assertEquals("é\r\n", Files.readString(target));
        assertThrows(IOException.class, () -> TimeBranchApp.exportSource(workspace, workspace, "replace"));
        assertEquals("saved data", Files.readString(workspace));
        assertThrows(IOException.class, () -> TimeBranchApp.exportSource(target, workspace, String.valueOf((char) 0xd800)));
        assertEquals("é\r\n", Files.readString(target));
    }

    @Test void editorPreservesMixedLineEndingsAndHiddenCharactersOutsideTheEdit() {
        String original = "one\r\ntwo\rthree\n\u0001";
        assertEquals("one\ntwo\nthree\n\uFFFD", EditorText.display(original));
        assertEquals("one\r\nnew\r\ntext\rthree\n\u0001", EditorText.applyEdit(original, 4, 7, "new\ntext"));
        assertEquals("a\r\nc", EditorText.applyEdit("a\r\nb", 2, 3, "c"));
    }
}
