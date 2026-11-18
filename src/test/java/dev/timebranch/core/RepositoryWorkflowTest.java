package dev.timebranch.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryWorkflowTest {
    @TempDir Path directory;

    @Test void branchCommitSwitchDeleteBranchDeleteVersionAndRestart() throws IOException {
        Path workspace = directory.resolve("workspace.tbr");
        VersionRepository repo = new VersionRepository();
        repo.editFile("Main.java", "main base");
        int base = repo.commit("Shared base");
        repo.createAndCheckoutBranch("feature");
        repo.createFile("Feature.java", "only on feature");
        int feature = repo.commit("Feature work");
        repo.createBranch("keep");
        repo.editFile("Feature.java", "exclusive feature edit");
        int exclusive = repo.commit("Exclusive work");

        repo.checkout("main", workspace);
        assertFalse(repo.files().containsKey("Feature.java"));
        repo.editFile("Main.java", "main tip");
        int mainTip = repo.commit("Main work");
        repo.deleteBranch("feature", false, workspace);
        assertThrows(IllegalArgumentException.class, () -> repo.revision(exclusive));
        assertEquals(List.of(feature, mainTip), repo.revision(base).children());
        repo.deleteVersion(mainTip, workspace);

        VersionRepository restarted = VersionRepository.load(workspace);
        assertEquals("main", restarted.branch());
        assertEquals(base, restarted.headId());
        assertEquals("main tip", restarted.content("Main.java"));
        assertEquals(List.of("Main.java"), VersionRepository.changedFiles(
                restarted.revision(base).files, restarted.files()));
        assertEquals(List.of(feature, base, 1), restarted.revisions().stream().map(r -> r.id).toList());
        assertEquals(List.of(feature), restarted.revision(base).children());
        assertThrows(IllegalArgumentException.class, () -> restarted.restoreVersion(mainTip));
        restarted.checkout("keep", workspace);
        assertEquals("only on feature", restarted.content("Feature.java"));
        assertFalse(restarted.dirty());
        assertTrue(restarted.commit("After restart") > mainTip);
    }

    @Test void renameDeleteUndoRedoAndRecoverUseCommittedSnapshots() {
        VersionRepository repo = new VersionRepository();
        repo.createFile("Original.txt", "committed content");
        int original = repo.commit("Saved original");
        repo.renameFile("Original.txt", "Renamed.txt");
        assertEquals(List.of("Original.txt", "Renamed.txt"),
                VersionRepository.changedFiles(repo.revision(original).files, repo.files()));
        assertTrue(repo.undo());
        assertFalse(repo.dirty());
        assertTrue(repo.redo());
        repo.editFile("Renamed.txt", "unsaved new content");
        repo.deleteFile("Renamed.txt");
        assertEquals(List.of("Original.txt"), repo.recoverableFiles());
        repo.recoverFile("Original.txt");
        assertEquals("committed content", repo.content("Original.txt"));
        assertFalse(repo.dirty());
        assertTrue(repo.undo());
        assertFalse(repo.files().containsKey("Original.txt"));
        assertTrue(repo.redo());
        repo.restoreVersion(original);
        assertFalse(repo.dirty());
        assertEquals("committed content", repo.revision(original).files.get("Original.txt"));
    }

    @Test void branchCreationDoesNotCarryUndoHistoryAndCheckoutFailureKeepsEdits() throws IOException {
        VersionRepository repo = new VersionRepository();
        String committed = repo.content("Main.java");
        repo.editFile("Main.java", "draft");
        repo.createAndCheckoutBranch("feature");
        assertEquals("draft", repo.content("Main.java"));
        assertFalse(repo.canUndo());
        repo.editFile("Main.java", "feature edits");
        Path blocked = Files.createDirectory(directory.resolve("blocked"));
        Files.writeString(blocked.resolve("existing"), "preserve");
        assertThrows(IOException.class, () -> repo.checkout("main", blocked));
        assertEquals("feature", repo.branch());
        assertEquals("feature edits", repo.content("Main.java"));
        assertTrue(repo.canUndo());

        repo.checkout("main", directory.resolve("workspace.tbr"));
        assertEquals(committed, repo.content("Main.java"));
        assertFalse(repo.canUndo());
        assertFalse(repo.canRedo());
    }

    @Test void rejectsInvalidFileChangesBeforeAddingUndoEntries() {
        VersionRepository repo = new VersionRepository();
        assertThrows(IllegalArgumentException.class, () -> repo.createFile("../outside", "text"));
        assertThrows(IllegalArgumentException.class, () -> repo.createFile("Main.java", "replacement"));
        assertThrows(IllegalArgumentException.class, () ->
                repo.editFile("Main.java", "é".repeat(VersionRepository.MAX_FILE_BYTES)));
        assertThrows(IllegalArgumentException.class, () -> repo.renameFile("Main.java", "."));
        assertFalse(repo.dirty());
        assertFalse(repo.canUndo());
    }

    @Test void selectingTheActiveBranchDoesNotResetItsWorkspace() throws IOException {
        VersionRepository repo = new VersionRepository();
        repo.editFile("Main.java", "Uncommitted content");
        repo.checkout("main");
        repo.checkout("main", directory.resolve("workspace.tbr"));
        assertEquals("Uncommitted content", repo.content("Main.java"));
        assertTrue(repo.canUndo());
    }
}
