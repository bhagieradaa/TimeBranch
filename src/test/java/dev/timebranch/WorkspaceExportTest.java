package dev.timebranch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceExportTest {
    @TempDir Path destination;

    @Test void exportsAllFilesWithOriginalNamesAndContents() throws IOException {
        Map<String, String> files = Map.of("Main.java", "class Main {}\r\n",
                "notes résumé.txt", "café\n", ".gitignore", "");
        Path project = TimeBranchApp.exportWorkspace(destination, files);

        assertEquals(destination.resolve("TimeBranchProject"), project);
        try (var entries = Files.list(project)) {
            assertEquals(files.keySet(), entries.map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet()));
        }
        for (var file : files.entrySet()) {
            assertEquals(file.getValue(), Files.readString(project.resolve(file.getKey())));
        }
    }

    @Test void leavesAnExistingProjectFolderAndItsFilesAlone() throws IOException {
        Path project = Files.createDirectory(destination.resolve("TimeBranchProject"));
        Path existing = Files.writeString(project.resolve("Main.java"), "keep this");
        assertThrows(IOException.class, () ->
                TimeBranchApp.exportWorkspace(destination, Map.of("Main.java", "replacement")));
        assertEquals("keep this", Files.readString(existing));
    }

    @Test void leavesAnExistingFileAtTheProjectPathAlone() throws IOException {
        Path existing = Files.writeString(destination.resolve("TimeBranchProject"), "keep this");
        assertThrows(IOException.class, () -> TimeBranchApp.exportWorkspace(destination, Map.of()));
        assertEquals("keep this", Files.readString(existing));
    }

    @Test void removesThePartialExportWhenWritingFails() throws IOException {
        Path unrelated = Files.writeString(destination.resolve("unrelated.txt"), "keep this");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("first.txt", "valid content");
        files.put("invalid.txt", String.valueOf((char) 0xd800));

        assertThrows(IOException.class, () -> TimeBranchApp.exportWorkspace(destination, files));
        try (var entries = Files.list(destination)) {
            assertEquals(Set.of(unrelated), entries.collect(Collectors.toSet()));
        }
    }
}
