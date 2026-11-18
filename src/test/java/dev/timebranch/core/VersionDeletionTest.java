package dev.timebranch.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VersionDeletionTest {
    @TempDir Path temporaryDirectory;
    private final VersionRepository repo = new VersionRepository();

    private Path workspace() {
        return temporaryDirectory.resolve("workspace.tbr");
    }

    private List<Integer> history(VersionRepository repository) {
        return repository.revisions().stream().map(revision -> revision.id).toList();
    }

    @Test void deletesLeafAndPreservesWorkingFilesAcrossRestart() throws IOException {
        repo.editFile("Main.java", "first edit");
        int parent = repo.commit("Parent");
        repo.editFile("Main.java", "second edit");
        repo.createFile("OnlyInDeletedVersion.txt");
        int leaf = repo.commit("Leaf");
        repo.deleteFile("OnlyInDeletedVersion.txt");
        repo.editFile("Main.java", "uncommitted edit");
        repo.save(workspace());

        repo.deleteVersion(leaf, workspace());

        VersionRepository reloaded = VersionRepository.load(workspace());
        for (VersionRepository repository : List.of(repo, reloaded)) {
            assertEquals(parent, repository.headId());
            assertEquals(List.of(parent, 1), history(repository));
            assertTrue(repository.revision(parent).children().isEmpty());
            assertEquals("uncommitted edit", repository.content("Main.java"));
            assertTrue(repository.dirty());
            assertThrows(IllegalArgumentException.class, () -> repository.revision(leaf));
            assertThrows(IllegalArgumentException.class, () -> repository.restoreVersion(leaf));
            assertFalse(repository.recoverableFiles().contains("OnlyInDeletedVersion.txt"));
        }
        assertTrue(repo.canUndo());
        assertEquals(leaf + 1, reloaded.commit("Next commit"));
    }

    @Test void rejectsInitialVersionAndACommitWithChildren() {
        assertThrows(IllegalArgumentException.class, () -> repo.deleteVersion(1, workspace()));
        int parent = repo.commit("Parent");
        int child = repo.commit("Child");

        assertThrows(IllegalArgumentException.class, () -> repo.deleteVersion(parent, workspace()));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteVersion(999, workspace()));
        assertEquals(child, repo.headId());
        assertEquals(List.of(child), repo.revision(parent).children());
        assertEquals(List.of(child, parent, 1), history(repo));
        assertFalse(Files.exists(workspace()));
    }

    @Test void rejectsAHeadThatAnotherBranchShares() {
        int shared = repo.commit("Shared");
        repo.createBranch("feature");

        assertThrows(IllegalArgumentException.class, () -> repo.deleteVersion(shared, workspace()));
        assertEquals(shared, repo.headId());
        repo.checkout("feature");
        assertEquals(shared, repo.headId());
        assertThrows(IllegalArgumentException.class, () -> repo.deleteVersion(shared, workspace()));
    }

    @Test void rejectsAnotherBranchsLeafAndKeepsItWhenDeletingCurrentHead() throws IOException {
        repo.createBranch("feature");
        int main = repo.commit("Main leaf");
        repo.checkout("feature");
        int feature = repo.commit("Feature leaf");

        assertThrows(IllegalArgumentException.class, () -> repo.deleteVersion(main, workspace()));
        repo.checkout("main");
        repo.deleteVersion(main, workspace());

        VersionRepository reloaded = VersionRepository.load(workspace());
        assertEquals(1, reloaded.headId());
        assertEquals(List.of(feature), reloaded.revision(1).children());
        assertEquals(List.of(feature, 1), history(reloaded));
        reloaded.checkout("feature");
        assertEquals(feature, reloaded.headId());
    }

    @Test void rollsBackAllHistoryChangesIfSavingFails() throws IOException {
        repo.createBranch("feature");
        int main = repo.commit("Main leaf");
        repo.checkout("feature");
        int feature = repo.commit("Feature leaf");
        repo.checkout("main");
        repo.save(workspace());
        Path badDestination = Files.createDirectory(temporaryDirectory.resolve("blocked"));
        Files.writeString(badDestination.resolve("existing"), "keep");

        assertThrows(IOException.class, () -> repo.deleteVersion(main, badDestination));

        assertEquals(main, repo.headId());
        assertEquals(main, repo.revision(main).id);
        assertEquals(List.of(main, feature), repo.revision(1).children());
        assertEquals(List.of(feature, main, 1), history(repo));
        assertEquals(history(repo), history(VersionRepository.load(workspace())));
    }
}
