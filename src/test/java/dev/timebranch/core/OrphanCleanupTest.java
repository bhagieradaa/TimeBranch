package dev.timebranch.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrphanCleanupTest {
    @TempDir Path directory;

    @Test void cleansLegacyOrphanChainWithoutChangingCurrentBranchOrFiles() throws IOException {
        Path workspace = legacyWorkspace();
        VersionRepository repo = VersionRepository.load(workspace);
        assertEquals(List.of(7, 6, 5, 4, 3, 2, 1), ids(repo));
        byte[] original = Files.readAllBytes(workspace);

        assertEquals(2, repo.cleanupOrphanedVersions(workspace));

        VersionRepository restarted = VersionRepository.load(workspace);
        for (VersionRepository state : List.of(repo, restarted)) {
            assertEquals(List.of(7, 6, 4, 2, 1), ids(state));
            assertEquals("keep", state.branch());
            assertEquals(4, state.headId());
            assertEquals(Map.of("Draft.java", "Uncommitted content"), state.files());
            assertEquals(List.of(4, 7), state.revision(2).children());
            assertThrows(IllegalArgumentException.class, () -> state.revision(3));
            assertThrows(IllegalArgumentException.class, () -> state.restoreVersion(5));
        }
        assertEquals(0, restarted.cleanupOrphanedVersions(workspace));
        try (var entries = Files.list(directory)) {
            List<Path> backups = entries.filter(path -> path.getFileName().toString()
                    .startsWith("workspace-before-orphan-cleanup-")).toList();
            assertEquals(1, backups.size());
            assertArrayEquals(original, Files.readAllBytes(backups.get(0)));
        }
        assertEquals(8, restarted.commit("Another snapshot"));
    }

    @Test void deletingBranchPrunesItsUniqueCommitsAndOlderUnrelatedOrphans() throws IOException {
        Path workspace = legacyWorkspace();
        VersionRepository repo = VersionRepository.load(workspace);
        assertThrows(IllegalArgumentException.class, () -> repo.deleteBranch("drop", false, workspace));

        repo.deleteBranch("drop", true, workspace);

        VersionRepository restarted = VersionRepository.load(workspace);
        assertEquals(List.of("main", "keep"), restarted.branches());
        assertEquals(List.of(6, 4, 2, 1), ids(restarted));
        assertEquals("main", restarted.branch());
        assertEquals(6, restarted.headId());
        assertEquals(Map.of("Main.java", "main snapshot"), restarted.files());
        assertEquals(List.of(4), restarted.revision(2).children());
        assertEquals(List.of(2, 6), restarted.revision(1).children());
        assertEquals(0, restarted.cleanupOrphanedVersions(workspace));
    }

    @Test void failedCleanupRestoresHistoryAndLeavesSavedBytesIntact() throws IOException {
        Path workspace = legacyWorkspace();
        VersionRepository repo = VersionRepository.load(workspace);
        byte[] original = Files.readAllBytes(workspace);
        Path blocked = Files.createDirectory(directory.resolve("blocked"));
        Files.writeString(blocked.resolve("existing"), "keep");

        assertThrows(IOException.class, () -> repo.cleanupOrphanedVersions(blocked));

        assertEquals(List.of(7, 6, 5, 4, 3, 2, 1), ids(repo));
        assertEquals(List.of(3, 4, 7), repo.revision(2).children());
        assertEquals(List.of(5), repo.revision(3).children());
        assertArrayEquals(original, Files.readAllBytes(workspace));
    }

    private static List<Integer> ids(VersionRepository repo) {
        return repo.revisions().stream().map(revision -> revision.id).toList();
    }

    private Path legacyWorkspace() throws IOException {
        Path workspace = directory.resolve("workspace.tbr");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(workspace))) {
            out.writeInt(0x54425232);
            out.writeInt(1);
            out.writeInt(8);
            writeText(out, "keep");
            out.writeInt(3);
            writeText(out, "main");
            out.writeInt(6);
            writeText(out, "keep");
            out.writeInt(4);
            writeText(out, "drop");
            out.writeInt(7);
            writeFile(out, "Draft.java", "Uncommitted content");
            out.writeInt(7);
            writeRevision(out, 1, 0, "main");
            writeRevision(out, 2, 1, "main");
            writeRevision(out, 3, 2, "feature/new-idea");
            writeRevision(out, 4, 2, "keep");
            writeRevision(out, 5, 3, "feature/new-idea");
            writeRevision(out, 6, 1, "main");
            writeRevision(out, 7, 2, "drop");
        }
        return workspace;
    }

    private static void writeRevision(DataOutputStream out, int id, int parent, String branch) throws IOException {
        out.writeInt(id);
        out.writeInt(parent);
        writeText(out, branch);
        writeText(out, "Snapshot");
        out.writeLong(0);
        writeFile(out, "Main.java", branch + " snapshot");
    }

    private static void writeFile(DataOutputStream out, String name, String content) throws IOException {
        out.writeInt(1);
        writeText(out, name);
        writeText(out, content);
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
