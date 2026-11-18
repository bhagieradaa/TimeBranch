package dev.timebranch.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Educational version-control engine. Each revision is an immutable snapshot
 * in a general tree; an explicit hash table indexes revision IDs, a doubly
 * linked list keeps chronology, and linked stacks implement workspace undo/redo.
 */
public final class VersionRepository {
    private static final int MAGIC = 0x54425232; // TBR2
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    public static final class Revision {
        public final int id;
        public final int parentId;
        public final String branch;
        public final String message;
        public final long createdAt;
        public final Map<String, String> files;
        private final List<Integer> childIds = new ArrayList<>();

        Revision(int id, int parentId, String branch, String message, long at, Map<String, String> files) {
            this.id = id;
            this.parentId = parentId;
            this.branch = branch;
            this.message = message;
            this.createdAt = at;
            this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
        public List<Integer> children() { return Collections.unmodifiableList(childIds); }
        public String time() { return Instant.ofEpochMilli(createdAt).toString(); }
    }

    // Custom chained hash table: O(1) average revision lookup.
    private static final class RevisionIndex {
        private static final class Bucket {
            int key;
            Revision value;
            Bucket next;
            Bucket(int key, Revision value, Bucket next) {
                this.key = key; this.value = value; this.next = next;
            }
        }
        private final Bucket[] buckets = new Bucket[4093];
        private int bucket(int id) { return Math.floorMod(id, buckets.length); }
        void put(Revision r) {
            int i = bucket(r.id);
            for (Bucket p = buckets[i]; p != null; p = p.next) {
                if (p.key == r.id) { throw new IllegalArgumentException("Duplicate revision"); }
            }
            buckets[i] = new Bucket(r.id, r, buckets[i]);
        }
        Revision get(int key) {
            for (Bucket p = buckets[bucket(key)]; p != null; p = p.next) {
                if (p.key == key) return p.value;
            }
            return null;
        }
    }

    // Custom doubly linked list: O(1) append and reverse chronological traversal.
    private static final class History {
        private static final class Link {
            Revision value; Link previous, next;
            Link(Revision value) { this.value = value; }
        }
        Link first, last;
        int size;
        void append(Revision revision) {
            Link link = new Link(revision);
            if (last == null) first = link;
            else { last.next = link; link.previous = last; }
            last = link;
            size++;
        }
        List<Revision> newestFirst() {
            List<Revision> result = new ArrayList<>(size);
            for (Link p = last; p != null; p = p.previous) result.add(p.value);
            return result;
        }
        List<Revision> oldestFirst() {
            List<Revision> result = new ArrayList<>(size);
            for (Link p = first; p != null; p = p.next) result.add(p.value);
            return result;
        }
    }

    // Custom linked stack for reversing workspace changes.
    private static final class EditStack {
        private static final class Link {
            Map<String,String> state; Link next;
            Link(Map<String,String> state, Link next) { this.state = state; this.next = next; }
        }
        Link top;
        int size;
        void push(Map<String,String> files) {
            top = new Link(new LinkedHashMap<>(files), top);
            if (++size > 100) {
                Link p = top;
                for (int i = 1; i < 100; i++) p = p.next;
                p.next = null;
                size = 100;
            }
        }
        Map<String,String> pop() {
            if (top == null) return null;
            Map<String,String> state = top.state;
            top = top.next; size--;
            return state;
        }
        void clear() { top = null; size = 0; }
        boolean isEmpty() { return top == null; }
    }

    private RevisionIndex index = new RevisionIndex();
    private History history = new History();
    private final LinkedHashMap<String,Integer> heads = new LinkedHashMap<>();
    private LinkedHashMap<String,String> files = new LinkedHashMap<>();
    private String currentBranch = "main";
    private int nextId = 1;
    private final EditStack undo = new EditStack();
    private final EditStack redo = new EditStack();

    public VersionRepository() {
        files.put("Main.java", "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, TimeBranch!\");\n    }\n}\n");
        commit("Initial snapshot");
    }
    private VersionRepository(boolean blank) { }

    public Map<String,String> files() { return Collections.unmodifiableMap(files); }
    public String content(String name) { return files.getOrDefault(name, ""); }
    public String branch() { return currentBranch; }
    public int headId() { return heads.get(currentBranch); }
    public List<String> branches() { return new ArrayList<>(heads.keySet()); }
    public List<Revision> revisions() { return history.newestFirst(); }
    public Revision revision(int id) {
        Revision result = index.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown version V" + id);
        return result;
    }
    public boolean dirty() { return !revision(headId()).files.equals(files); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    private void beforeEdit() { undo.push(files); redo.clear(); }
    public static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100
                || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
                || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use a filename without directories (for example Main.java).");
    }
    public void createFile(String name) {
        validateName(name);
        if (files.containsKey(name)) throw new IllegalArgumentException("That file already exists.");
        beforeEdit();
        files.put(name, "");
    }
    public void renameFile(String oldName, String newName) {
        validateName(newName);
        if (!files.containsKey(oldName)) throw new IllegalArgumentException("File not found.");
        if (!oldName.equals(newName) && files.containsKey(newName))
            throw new IllegalArgumentException("The destination filename already exists.");
        if (oldName.equals(newName)) return;
        beforeEdit();
        LinkedHashMap<String,String> changed = new LinkedHashMap<>();
        for (var entry : files.entrySet())
            changed.put(entry.getKey().equals(oldName) ? newName : entry.getKey(), entry.getValue());
        files = changed;
    }
    public void editFile(String name, String content) {
        if (!files.containsKey(name)) throw new IllegalArgumentException("File not found.");
        if (content == null) content = "";
        if (Objects.equals(files.get(name), content)) return;
        beforeEdit();
        files.put(name, content);
    }
    public void deleteFile(String name) {
        if (!files.containsKey(name)) throw new IllegalArgumentException("File not found.");
        beforeEdit();
        files.remove(name);
    }
    public boolean undo() {
        Map<String,String> previous = undo.pop();
        if (previous == null) return false;
        redo.push(files);
        files = new LinkedHashMap<>(previous);
        return true;
    }
    public boolean redo() {
        Map<String,String> next = redo.pop();
        if (next == null) return false;
        undo.push(files);
        files = new LinkedHashMap<>(next);
        return true;
    }

    public int commit(String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Enter a version message.");
        if (message.length() > 180) throw new IllegalArgumentException("Version message is too long.");
        int parent = heads.getOrDefault(currentBranch, 0);
        Revision r = new Revision(nextId++, parent, currentBranch, message.trim(),
                System.currentTimeMillis(), files);
        index.put(r);
        history.append(r);
        if (parent != 0) revision(parent).childIds.add(r.id);
        heads.put(currentBranch, r.id);
        undo.clear();
        redo.clear();
        return r.id;
    }
    public void createBranch(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,59}")
                || name.contains("..") || name.endsWith("/"))
            throw new IllegalArgumentException("Branch name must use letters, numbers, /, - or _.");
        if (heads.containsKey(name)) throw new IllegalArgumentException("Branch already exists.");
        heads.put(name, headId());
    }
    public void createAndCheckoutBranch(String name) {
        createBranch(name);
        currentBranch = name;
    }

    public void checkout(String name) {
        Integer id = heads.get(name);
        if (id == null) throw new IllegalArgumentException("Unknown branch.");
        currentBranch = name;
        files = new LinkedHashMap<>(revision(id).files);
        undo.clear();
        redo.clear();
    }
    /** Restoring a version modifies the workspace, not immutable history. */
    public void restoreVersion(int id) {
        Revision selected = revision(id);
        beforeEdit();
        files = new LinkedHashMap<>(selected.files);
    }

    /** Walk the ancestor tree: deleted files remain recoverable after later saves. */
    public List<String> recoverableFiles() {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        int id = headId();
        while (id > 0) {
            Revision r = revision(id);
            for (String name : r.files.keySet())
                if (!files.containsKey(name)) missing.add(name);
            id = r.parentId;
        }
        return new ArrayList<>(missing);
    }
    public void recoverFile(String name) {
        validateName(name);
        if (files.containsKey(name)) throw new IllegalArgumentException("File already exists.");
        int id = headId();
        while (id > 0) {
            Revision r = revision(id);
            if (r.files.containsKey(name)) {
                beforeEdit();
                files.put(name, r.files.get(name));
                return;
            }
            id = r.parentId;
        }
        throw new IllegalArgumentException("No saved copy of " + name + " found.");
    }

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(MAGIC);
            data.writeInt(FORMAT);
            data.writeInt(nextId);
            writeText(data, currentBranch);
            data.writeInt(heads.size());
            for (var entry : heads.entrySet()) {
                writeText(data, entry.getKey()); data.writeInt(entry.getValue());
            }
            writeFiles(data, files);
            List<Revision> revisions = history.oldestFirst();
            data.writeInt(revisions.size());
            for (Revision r : revisions) {
                data.writeInt(r.id); data.writeInt(r.parentId);
                writeText(data, r.branch); writeText(data, r.message);
                data.writeLong(r.createdAt);
                writeFiles(data, r.files);
            }
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IOException("Repository exceeds 32 MB limit.");
        return bytes;
    }
    public static VersionRepository fromBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES || bytes.length < 16)
            throw new IOException("Invalid backup size.");
        VersionRepository repo = new VersionRepository(true);
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (data.readInt() != MAGIC || data.readInt() != FORMAT)
                throw new IOException("Not a TimeBranch v1 backup.");
            repo.nextId = data.readInt();
            repo.currentBranch = readText(data);
            int branchCount = checkedCount(data.readInt(), 100);
            for (int i = 0; i < branchCount; i++)
                repo.heads.put(readText(data), data.readInt());
            repo.files = readFiles(data);
            int count = checkedCount(data.readInt(), 10000);
            for (int i = 0; i < count; i++) {
                int id = data.readInt(), parent = data.readInt();
                Revision r = new Revision(id, parent, readText(data), readText(data),
                        data.readLong(), readFiles(data));
                if (id <= 0 || id >= repo.nextId || repo.index.get(id) != null)
                    throw new IOException("Invalid revision index.");
                repo.index.put(r);
                repo.history.append(r);
            }
            for (Revision r : repo.history.oldestFirst()) {
                if (r.parentId > 0) {
                    Revision p = repo.index.get(r.parentId);
                    if (p == null) throw new IOException("Missing parent revision.");
                    p.childIds.add(r.id);
                }
            }
            if (repo.heads.isEmpty() || !repo.heads.containsKey(repo.currentBranch))
                throw new IOException("No valid current branch.");
            for (int id : repo.heads.values())
                if (repo.index.get(id) == null) throw new IOException("Missing branch head.");
            if (data.read() != -1) throw new IOException("Unexpected trailing backup data.");
            return repo;
        } catch (RuntimeException e) {
            throw new IOException("Corrupt TimeBranch backup.", e);
        }
    }
    private static int checkedCount(int number, int maximum) throws IOException {
        if (number < 0 || number > maximum) throw new IOException("Invalid record count.");
        return number;
    }
    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) throw new IOException("Text field too large.");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readText(DataInputStream in) throws IOException {
        int length = checkedCount(in.readInt(), 1024 * 1024);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void writeFiles(DataOutputStream out, Map<String,String> files) throws IOException {
        out.writeInt(files.size());
        for (var f : files.entrySet()) { writeText(out, f.getKey()); writeText(out, f.getValue()); }
    }
    private static LinkedHashMap<String,String> readFiles(DataInputStream in) throws IOException {
        int count = checkedCount(in.readInt(), 1000);
        LinkedHashMap<String,String> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = readText(in), content = readText(in);
            validateName(name);
            if (files.put(name, content) != null) throw new IOException("Duplicate file.");
        }
        return files;
    }
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(temp, toBytes());
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public static VersionRepository load(Path path) throws IOException {
        return Files.exists(path) ? fromBytes(Files.readAllBytes(path)) : new VersionRepository();
    }
}
