package dev.timebranch.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryConsistencyTest {
    @TempDir Path directory;

    @Test void rejectsBadBranchesAndHistoryReferences() throws IOException {
        assertThrows(IOException.class, () -> VersionRepository.fromBytes(workspace("other", 2, 1, 0, 2, 1)));
        assertThrows(IOException.class, () -> VersionRepository.fromBytes(workspace("main", 99, 1, 0, 2, 1)));
        assertThrows(IOException.class, () -> VersionRepository.fromBytes(workspace("main", 2, 1, 0, 2, 2)));
        assertThrows(IOException.class, () -> VersionRepository.fromBytes(workspace("main", 2, 1, 2, 2, 1)));
        assertThrows(IOException.class, () -> VersionRepository.fromBytes(workspace("main", 2, 1, 0, 2, 0)));
        assertThrows(IOException.class, () -> VersionRepository.fromBytes(workspace("main", 2, 2, 1, 1, 0)));
    }

    @Test void validWorkspaceKeepsGapsAndReadsTheExistingFormat() throws IOException {
        VersionRepository repo = VersionRepository.fromBytes(workspace("main", 4, 1, 0, 4, 1));
        assertEquals(4, repo.headId());
        assertEquals(1, repo.revision(4).parentId);
        assertEquals(Map.of(), repo.files());
        assertEquals(7, repo.commit("Next version"));
    }

    @Test void invalidWorkspaceRemainsIntactAndMissingWorkspaceStartsFresh() throws IOException {
        Path file = directory.resolve("workspace.tbr");
        assertEquals(1, VersionRepository.load(file).headId());
        byte[] broken = {1, 2, 3, 4};
        Files.write(file, broken);
        assertThrows(IOException.class, () -> VersionRepository.load(file));
        assertArrayEquals(broken, Files.readAllBytes(file));
    }

    private static byte[] workspace(String branch, int head, int first, int firstParent, int last, int lastParent)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(0x54425232);
            data.writeInt(1);
            data.writeInt(7);
            text(data, branch);
            data.writeInt(1);
            text(data, branch);
            data.writeInt(head);
            data.writeInt(0);
            data.writeInt(2);
            revision(data, first, firstParent, branch);
            revision(data, last, lastParent, branch);
        }
        return bytes.toByteArray();
    }

    private static void revision(DataOutputStream data, int id, int parent, String branch) throws IOException {
        data.writeInt(id);
        data.writeInt(parent);
        text(data, branch);
        text(data, "Snapshot");
        data.writeLong(0);
        data.writeInt(0);
    }

    private static void text(DataOutputStream data, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }
}
