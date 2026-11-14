package dev.timebranch.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VersionRepositoryTest {
    @Test void initialVersionAndLookup() {
        VersionRepository r=new VersionRepository();
        assertEquals("main",r.branch());
        assertEquals(1,r.headId());
        assertEquals(1,r.revisions().size());
        assertTrue(r.revision(1).files.containsKey("Main.java"));
    }
    @Test void commitAndRecoverDeletedContent() {
        VersionRepository r=new VersionRepository();
        r.editFile("Main.java","new code");
        int v=r.commit("updated");
        r.deleteFile("Main.java");
        r.commit("removed file");
        assertTrue(r.recoverableFiles().contains("Main.java"));
        r.recoverFile("Main.java");
        assertEquals("new code",r.content("Main.java"));
        assertEquals("new code",r.revision(v).files.get("Main.java"));
    }
    @Test void branchingIsIndependent() {
        VersionRepository r=new VersionRepository();
        r.createBranch("feature/login");
        r.checkout("feature/login");
        r.editFile("Main.java","feature");
        r.commit("feature work");
        r.checkout("main");
        assertNotEquals("feature",r.content("Main.java"));
        r.checkout("feature/login");
        assertEquals("feature",r.content("Main.java"));
    }
    @Test void undoRedoAndRestore() {
        VersionRepository r=new VersionRepository();
        String old=r.content("Main.java");
        r.editFile("Main.java","changed");
        assertTrue(r.undo());
        assertEquals(old,r.content("Main.java"));
        assertTrue(r.redo());
        assertEquals("changed",r.content("Main.java"));
        r.restoreVersion(1);
        assertEquals(old,r.content("Main.java"));
    }
    @Test void serializationRoundTripIncludesBranches() throws Exception {
        VersionRepository r=new VersionRepository();
        r.createBranch("experiment");
        r.checkout("experiment");
        r.createFile("App.java");r.editFile("App.java","abc");
        r.commit("added app");
        VersionRepository loaded=VersionRepository.fromBytes(r.toBytes());
        assertEquals("experiment",loaded.branch());
        assertEquals("abc",loaded.content("App.java"));
        assertEquals(2,loaded.revisions().size());
        loaded.checkout("main");
        assertFalse(loaded.files().containsKey("App.java"));
    }
    @Test void invalidNamesAndMissingVersions() {
        VersionRepository r=new VersionRepository();
        assertThrows(IllegalArgumentException.class,()->r.createFile("../secret"));
        assertThrows(IllegalArgumentException.class,()->r.revision(999));
        assertThrows(IllegalArgumentException.class,()->r.createBranch("bad name"));
        assertThrows(IllegalArgumentException.class,()->r.createFile("Main.java"));
    }
    @Test void diffShowsAddedAndRemovedLines() {
        List<DiffUtil.Line> lines=DiffUtil.compare("a\nb","a\nc");
        assertTrue(lines.stream().anyMatch(s->s.kind()=='-' && s.text().equals("b")));
        assertTrue(lines.stream().anyMatch(s->s.kind()=='+' && s.text().equals("c")));
    }
}
