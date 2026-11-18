package dev.timebranch.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchDeletionTest {
    @TempDir Path directory;
    private final VersionRepository repo = new VersionRepository();

    private Path workspace() {
        return directory.resolve("workspace.tbr");
    }

    @Test void protectsMainAndRejectsUnknownBranches() {
        repo.createAndCheckoutBranch("feature");
        assertThrows(IllegalArgumentException.class, () -> repo.deleteBranch("main", true, workspace()));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteBranch("missing", false, workspace()));
        assertEquals(List.of("main", "feature"), repo.branches());
        assertEquals("feature", repo.branch());
        assertFalse(Files.exists(workspace()));
    }

    @Test void deletingInactiveBranchRequiresConsentForUncommittedMainChanges() throws IOException {
        int shared = repo.commit("Shared snapshot");
        String committed = repo.content("Main.java");
        repo.createBranch("feature");
        repo.editFile("Main.java", "Uncommitted main edit");

        assertThrows(IllegalArgumentException.class, () -> repo.deleteBranch("feature", false, workspace()));
        assertEquals("Uncommitted main edit", repo.content("Main.java"));
        assertTrue(repo.canUndo());
        repo.deleteBranch("feature", true, workspace());

        assertFalse(repo.canUndo());
        VersionRepository saved = VersionRepository.load(workspace());
        for (VersionRepository repository : List.of(repo, saved)) {
            assertEquals(List.of("main"), repository.branches());
            assertEquals("main", repository.branch());
            assertEquals(shared, repository.headId());
            assertEquals(committed, repository.content("Main.java"));
            assertEquals(List.of(shared), repository.revision(1).children());
            assertThrows(IllegalArgumentException.class, () -> repository.checkout("feature"));
        }
    }

    @Test void switchesToMainWhenDeletingACleanActiveBranch() throws IOException {
        repo.editFile("Main.java", "Main snapshot");
        int main = repo.commit("Main commit");
        repo.createAndCheckoutBranch("feature");
        repo.editFile("Main.java", "Feature snapshot");
        int feature = repo.commit("Feature commit");
        repo.createBranch("shared-tip");

        repo.deleteBranch("feature", false, workspace());

        VersionRepository saved = VersionRepository.load(workspace());
        assertEquals(List.of("main", "shared-tip"), saved.branches());
        assertEquals("main", saved.branch());
        assertEquals(main, saved.headId());
        assertEquals("Main snapshot", saved.content("Main.java"));
        assertEquals(List.of(feature), saved.revision(main).children());
        saved.checkout("shared-tip");
        assertEquals(feature, saved.headId());
        assertEquals("Feature snapshot", saved.content("Main.java"));
    }

    @Test void requiresConsentToDiscardEditsAndRemovesExclusiveCommits() throws IOException {
        String mainContents = repo.content("Main.java");
        repo.createAndCheckoutBranch("feature");
        int feature = repo.commit("Feature commit");
        repo.editFile("Main.java", "Uncommitted feature edits");

        assertThrows(IllegalArgumentException.class, () -> repo.deleteBranch("feature", false, workspace()));
        assertEquals("feature", repo.branch());
        assertEquals("Uncommitted feature edits", repo.content("Main.java"));
        assertFalse(Files.exists(workspace()));

        repo.deleteBranch("feature", true, workspace());

        assertFalse(repo.canUndo());
        VersionRepository saved = VersionRepository.load(workspace());
        assertEquals(List.of("main"), saved.branches());
        assertEquals("main", saved.branch());
        assertEquals(1, saved.headId());
        assertEquals(mainContents, saved.content("Main.java"));
        assertEquals(List.of(1), saved.revisions().stream().map(revision -> revision.id).toList());
        assertTrue(saved.revision(1).children().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> saved.revision(feature));
    }

    @Test void restoresActiveBranchAndWorkingStateIfSavingFails() throws IOException {
        repo.createAndCheckoutBranch("feature");
        repo.createBranch("another");
        int feature = repo.commit("Exclusive feature snapshot");
        repo.editFile("Main.java", "Keep on failure");
        repo.save(workspace());
        Path badDestination = Files.createDirectory(directory.resolve("blocked"));
        Files.writeString(badDestination.resolve("existing"), "keep");

        assertThrows(IOException.class, () -> repo.deleteBranch("feature", true, badDestination));

        assertEquals(List.of("main", "feature", "another"), repo.branches());
        assertEquals("feature", repo.branch());
        assertEquals("Keep on failure", repo.content("Main.java"));
        assertTrue(repo.canUndo());
        assertEquals(List.of(feature), repo.revision(1).children());
        assertEquals(List.of(feature, 1), repo.revisions().stream().map(revision -> revision.id).toList());
        assertEquals("feature", VersionRepository.load(workspace()).branch());
    }

    @Test void keepsSharedAncestryAndPrunesOnlyTheDeletedSideAcrossRestart() throws IOException {
        repo.editFile("Main.java", "main committed");
        int main = repo.commit("Main work");
        repo.createAndCheckoutBranch("feature");
        repo.createFile("Feature.java");
        repo.editFile("Feature.java", "feature committed");
        int shared = repo.commit("Shared ancestor");
        repo.createBranch("keep");
        int featureOnly = repo.commit("Feature-only snapshot");
        repo.checkout("keep");
        repo.createFile("Keep.java");
        int keep = repo.commit("Independent work");

        repo.deleteBranch("feature", false, workspace());

        for (VersionRepository repository : List.of(repo, VersionRepository.load(workspace()))) {
            assertEquals("main", repository.branch());
            assertEquals(main, repository.headId());
            assertEquals("main committed", repository.content("Main.java"));
            assertFalse(repository.files().containsKey("Feature.java"));
            assertFalse(repository.files().containsKey("Keep.java"));
            assertEquals(List.of(keep, shared, main, 1),
                    repository.revisions().stream().map(revision -> revision.id).toList());
            assertEquals(List.of(keep), repository.revision(shared).children());
            assertThrows(IllegalArgumentException.class, () -> repository.revision(featureOnly));
            repository.checkout("keep");
            assertEquals(keep, repository.headId());
            assertEquals("feature committed", repository.content("Feature.java"));
            assertTrue(repository.files().containsKey("Keep.java"));
        }
    }

    @Test void featureFilesAndEditsStayOutOfMainWhenSwitchingBranches() throws IOException {
        repo.editFile("Main.java", "main version");
        int main = repo.commit("Main files");
        repo.createAndCheckoutBranch("feature");
        assertEquals(main, repo.headId());
        repo.editFile("Main.java", "feature version");
        repo.createFile("OnlyOnFeature.java");
        int feature = repo.commit("Feature files");

        repo.checkout("main");
        assertEquals("main version", repo.content("Main.java"));
        assertFalse(repo.files().containsKey("OnlyOnFeature.java"));
        assertThrows(IllegalArgumentException.class, () -> repo.restoreVersion(feature));
        repo.createFile("OnlyOnMain.java");
        repo.commit("Main only");
        repo.save(workspace());

        VersionRepository saved = VersionRepository.load(workspace());
        saved.checkout("feature");
        assertEquals("feature version", saved.content("Main.java"));
        assertTrue(saved.files().containsKey("OnlyOnFeature.java"));
        assertFalse(saved.files().containsKey("OnlyOnMain.java"));
        saved.checkout("main");
        assertTrue(saved.files().containsKey("OnlyOnMain.java"));
        assertFalse(saved.files().containsKey("OnlyOnFeature.java"));
    }

}
