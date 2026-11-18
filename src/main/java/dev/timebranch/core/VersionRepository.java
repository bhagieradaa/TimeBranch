package dev.timebranch.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
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
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 1000;
    private static final int MAX_BRANCHES = 100;
    private static final int MAX_REVISIONS = 10000;

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
        private int size;
        private int bucket(int id) { return Math.floorMod(id, buckets.length); }
        void put(Revision r) {
            int i = bucket(r.id);
            for (Bucket p = buckets[i]; p != null; p = p.next) {
                if (p.key == r.id) { throw new IllegalArgumentException("Duplicate revision"); }
            }
            buckets[i] = new Bucket(r.id, r, buckets[i]);
            size++;
        }
        Revision get(int key) {
            for (Bucket p = buckets[bucket(key)]; p != null; p = p.next) {
                if (p.key == key) return p.value;
            }
            return null;
        }
        void remove(int key) {
            int i = bucket(key);
            Bucket previous = null;
            for (Bucket p = buckets[i]; p != null; p = p.next) {
                if (p.key == key) {
                    if (previous == null) buckets[i] = p.next;
                    else previous.next = p.next;
                    size--;
                    return;
                }
                previous = p;
            }
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
        Link find(int id) {
            for (Link link = last; link != null; link = link.previous) {
                if (link.value.id == id) return link;
            }
            throw new IllegalStateException("Version is missing from history.");
        }
        void remove(Link link) {
            if (link.previous == null) first = link.next;
            else link.previous.next = link.next;
            if (link.next == null) last = link.previous;
            else link.next.previous = link.previous;
            size--;
        }
        void reinsert(Link link) {
            if (link.previous == null) first = link;
            else link.previous.next = link;
            if (link.next == null) last = link;
            else link.next.previous = link;
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

    private final RevisionIndex index = new RevisionIndex();
    private final History history = new History();
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
        if (result == null) throw new IllegalArgumentException("The requested version no longer exists.");
        return result;
    }
    public boolean dirty() { return !revision(headId()).files.equals(files); }
    public static List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        return names.stream().filter(name -> !Objects.equals(before.get(name), after.get(name))).toList();
    }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    private void beforeEdit() { undo.push(files); redo.clear(); }
    public static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100
                || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
                || name.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(name))
            throw new IllegalArgumentException("Use a filename without directories (for example Main.java).");
    }
    public void createFile(String name) {
        createFile(name, "");
    }
    public void createFile(String name, String content) {
        validateName(name);
        if (files.containsKey(name)) throw new IllegalArgumentException("That file already exists.");
        requireFileCapacity();
        validateContent(content);
        beforeEdit();
        files.put(name, content);
    }
    private void requireFileCapacity() {
        if (files.size() >= MAX_FILES) throw new IllegalArgumentException("A workspace can contain up to 1,000 files.");
    }
    private static void validateContent(String content) {
        if (content == null || !StandardCharsets.UTF_8.newEncoder().canEncode(content))
            throw new IllegalArgumentException("File contents must be valid UTF-8 text.");
        if (content.length() > MAX_FILE_BYTES || content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
            throw new IllegalArgumentException("Files are limited to 1 MiB of UTF-8 text.");
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
        validateContent(content);
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
        if (history.size >= MAX_REVISIONS || nextId == Integer.MAX_VALUE)
            throw new IllegalArgumentException("The workspace has reached its version limit.");
        if (message.length() > 180) throw new IllegalArgumentException("Version message is too long.");
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(message))
            throw new IllegalArgumentException("The version message must be valid UTF-8 text.");
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
        if (!validBranchName(name))
            throw new IllegalArgumentException("Use 1–60 letters, numbers, periods, slashes, hyphens or underscores in the branch name.");
        if (heads.containsKey(name)) throw new IllegalArgumentException("Branch already exists.");
        if (heads.size() >= MAX_BRANCHES) throw new IllegalArgumentException("A workspace can contain up to 100 branches.");
        heads.put(name, headId());
    }
    private static boolean validBranchName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,59}")
                && !name.contains("..") && !name.endsWith("/");
    }
    public void createAndCheckoutBranch(String name) {
        createBranch(name);
        currentBranch = name;
        undo.clear();
        redo.clear();
    }

    public void checkout(String name) {
        if (currentBranch.equals(name)) return;
        LinkedHashMap<String, String> targetFiles = committedFiles(name);
        currentBranch = name;
        files = targetFiles;
        undo.clear();
        redo.clear();
    }

    public void checkout(String name, Path workspace) throws IOException {
        if (currentBranch.equals(name)) return;
        LinkedHashMap<String, String> targetFiles = committedFiles(name);
        String previousBranch = currentBranch;
        LinkedHashMap<String, String> previousFiles = files;
        currentBranch = name;
        files = targetFiles;
        try {
            save(workspace);
        } catch (IOException | RuntimeException failure) {
            currentBranch = previousBranch;
            files = previousFiles;
            throw failure;
        }
        undo.clear();
        redo.clear();
    }

    private LinkedHashMap<String, String> committedFiles(String name) {
        Integer id = heads.get(name);
        if (id == null) throw new IllegalArgumentException("Unknown branch.");
        return new LinkedHashMap<>(revision(id).files);
    }

    public void deleteBranch(String name, boolean discardWorkingChanges, Path workspace) throws IOException {
        if ("main".equals(name)) throw new IllegalArgumentException("The main branch cannot be deleted.");
        if (!heads.containsKey(name)) throw new IllegalArgumentException("Unknown branch.");
        if (dirty() && !discardWorkingChanges)
            throw new IllegalArgumentException("Confirm discarding uncommitted changes before returning to main.");

        Integer mainHead = heads.get("main");
        if (mainHead == null) throw new IllegalStateException("Cannot switch to the missing main branch.");
        LinkedHashMap<String, String> mainFiles = new LinkedHashMap<>(revision(mainHead).files);

        LinkedHashMap<String, Integer> previousHeads = new LinkedHashMap<>(heads);
        LinkedHashMap<String, String> previousFiles = files;
        String previousBranch = currentBranch;
        heads.remove(name);
        currentBranch = "main";
        files = mainFiles;
        try {
            pruneAndSave(workspace, true);
        } catch (IOException | RuntimeException failure) {
            heads.clear();
            heads.putAll(previousHeads);
            currentBranch = previousBranch;
            files = previousFiles;
            throw failure;
        }
        undo.clear();
        redo.clear();
    }

    /** Remove legacy orphan snapshots without changing branch heads or working files. */
    public int cleanupOrphanedVersions(Path workspace) throws IOException {
        return pruneAndSave(workspace, false);
    }

    private int pruneAndSave(Path workspace, boolean branchRemoved) throws IOException {
        Set<Integer> reachable = new HashSet<>();
        for (int head : heads.values()) addAncestors(head, reachable);

        List<RemovedRevision> removed = new ArrayList<>();
        // Descendants follow their parents in history, so unlink from newest to oldest.
        for (History.Link link = history.last; link != null; link = link.previous) {
            if (!reachable.contains(link.value.id)) removed.add(removeRevision(link));
        }
        try {
            if (!branchRemoved && !removed.isEmpty() && Files.isRegularFile(workspace)) {
                backupBeforeOrphanCleanup(workspace);
            }
            if (branchRemoved || !removed.isEmpty()) save(workspace);
        } catch (IOException | RuntimeException failure) {
            for (int i = removed.size() - 1; i >= 0; i--) restoreRemovedRevision(removed.get(i));
            throw failure;
        }
        return removed.size();
    }

    private static void backupBeforeOrphanCleanup(Path workspace) throws IOException {
        Path backup = Files.createTempFile(workspace.toAbsolutePath().getParent(), "workspace-before-orphan-cleanup-", ".tbr");
        try (InputStream input = Files.newInputStream(workspace); OutputStream output = Files.newOutputStream(backup)) {
            input.transferTo(output);
        } catch (IOException failure) {
            try { Files.deleteIfExists(backup); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private void addAncestors(int head, Set<Integer> seen) {
        for (int id = head; id > 0 && seen.add(id); id = revision(id).parentId) {
            // Each iteration follows the parent link toward the root.
        }
    }

    public boolean isInCurrentBranchHistory(int id) {
        for (int ancestor = headId(); ancestor > 0; ancestor = revision(ancestor).parentId) {
            if (ancestor == id) return true;
        }
        return false;
    }

    /** Restoring a version modifies the workspace, not immutable history. */
    public void restoreVersion(int id) {
        Revision selected = revision(id);
        if (!isInCurrentBranchHistory(id))
            throw new IllegalArgumentException("Choose a version from the current branch's history.");
        if (files.equals(selected.files)) return;
        beforeEdit();
        files = new LinkedHashMap<>(selected.files);
    }

    public void validateVersionDeletion(int id) {
        Revision selected = revision(id);
        if (id == 1 || selected.parentId < 1)
            throw new IllegalArgumentException("The initial version cannot be deleted.");
        if (!selected.childIds.isEmpty())
            throw new IllegalArgumentException("This version has child revisions.");
        if (id != headId())
            throw new IllegalArgumentException("Only the latest version on the current branch can be deleted.");
        for (var head : heads.entrySet()) {
            if (!head.getKey().equals(currentBranch) && head.getValue() == id)
                throw new IllegalArgumentException("This version is also the HEAD of " + head.getKey() + ".");
        }
    }

    /** Remove the current branch's leaf snapshot and persist the new HEAD together. */
    public void deleteVersion(int id, Path workspace) throws IOException {
        validateVersionDeletion(id);
        Revision selected = revision(id);
        RemovedRevision removed = removeRevision(history.find(id));
        heads.put(currentBranch, selected.parentId);
        try {
            save(workspace);
        } catch (IOException | RuntimeException failure) {
            heads.put(currentBranch, id);
            restoreRemovedRevision(removed);
            throw failure;
        }
    }

    private record RemovedRevision(History.Link entry, Revision parent, int childPosition) {}

    private RemovedRevision removeRevision(History.Link entry) {
        Revision selected = entry.value;
        Revision parent = revision(selected.parentId);
        int childPosition = parent.childIds.indexOf(selected.id);
        history.remove(entry);
        index.remove(selected.id);
        parent.childIds.remove(childPosition);
        return new RemovedRevision(entry, parent, childPosition);
    }

    private void restoreRemovedRevision(RemovedRevision removed) {
        Revision revision = removed.entry().value;
        removed.parent().childIds.add(removed.childPosition(), revision.id);
        index.put(revision);
        history.reinsert(removed.entry());
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
                requireFileCapacity();
                beforeEdit();
                files.put(name, r.files.get(name));
                return;
            }
            id = r.parentId;
        }
        throw new IllegalArgumentException("No saved copy of " + name + " found.");
    }

    public byte[] toBytes() throws IOException {
        verifyConsistency();
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
        if (out.size() > MAX_BYTES) throw new IOException("Workspace exceeds the 32 MiB storage limit.");
        return out.toByteArray();
    }

    private void verifyConsistency() throws IOException {
        if (!heads.containsKey("main") || !heads.containsKey(currentBranch) || heads.size() > MAX_BRANCHES)
            throw new IOException("The workspace has invalid branch references.");
        for (var head : heads.entrySet()) {
            if (!validBranchName(head.getKey()) || index.get(head.getValue()) == null)
                throw new IOException("The workspace has an invalid branch head.");
        }
        if (history.size < 1 || history.size > MAX_REVISIONS || history.size != index.size)
            throw new IOException("The version index and history disagree.");

        Map<Integer, List<Integer>> expectedChildren = new HashMap<>();
        History.Link previous = null;
        int lastId = 0;
        int count = 0;
        for (History.Link link = history.first; link != null; link = link.next) {
            Revision revision = link.value;
            if (++count > history.size || link.previous != previous || revision.id <= lastId
                    || revision.id >= nextId || index.get(revision.id) != revision
                    || (count == 1 && revision.id != 1)) {
                throw new IOException("The version history contains invalid entries.");
            }
            if (revision.id == 1) {
                if (revision.parentId != 0) throw new IOException("The initial version has an invalid parent.");
            } else {
                if (revision.parentId < 1 || revision.parentId >= revision.id || index.get(revision.parentId) == null)
                    throw new IOException("The version tree contains an invalid parent.");
                expectedChildren.computeIfAbsent(revision.parentId, id -> new ArrayList<>()).add(revision.id);
            }
            if (!validBranchName(revision.branch) || revision.message.isBlank() || revision.message.length() > 180)
                throw new IOException("A version has invalid metadata.");
            previous = link;
            lastId = revision.id;
        }
        if (count != history.size || previous != history.last)
            throw new IOException("The version history contains invalid links.");
        for (History.Link link = history.first; link != null; link = link.next) {
            if (!link.value.childIds.equals(expectedChildren.getOrDefault(link.value.id, List.of())))
                throw new IOException("The version tree contains inconsistent child links.");
        }
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
            int branchCount = checkedCount(data.readInt(), MAX_BRANCHES);
            for (int i = 0; i < branchCount; i++) {
                if (repo.heads.putIfAbsent(readText(data), data.readInt()) != null)
                    throw new IOException("The workspace contains a duplicate branch.");
            }
            repo.files = readFiles(data);
            int count = checkedCount(data.readInt(), MAX_REVISIONS);
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
            if (data.read() != -1) throw new IOException("Unexpected trailing backup data.");
            repo.verifyConsistency();
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
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IOException("The workspace contains invalid UTF-8 text.");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("A workspace text field exceeds 1 MiB.");
        if (out.size() > MAX_BYTES - Integer.BYTES - bytes.length)
            throw new IOException("Workspace exceeds the 32 MiB storage limit.");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readText(DataInputStream in) throws IOException {
        int length = checkedCount(in.readInt(), MAX_FILE_BYTES);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("The workspace contains invalid UTF-8 text.", e);
        }
    }
    private static void writeFiles(DataOutputStream out, Map<String,String> files) throws IOException {
        checkedCount(files.size(), MAX_FILES);
        out.writeInt(files.size());
        for (var f : files.entrySet()) {
            validateName(f.getKey());
            writeText(out, f.getKey()); writeText(out, f.getValue());
        }
    }
    private static LinkedHashMap<String,String> readFiles(DataInputStream in) throws IOException {
        int count = checkedCount(in.readInt(), MAX_FILES);
        LinkedHashMap<String,String> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = readText(in), content = readText(in);
            validateName(name);
            if (files.put(name, content) != null) throw new IOException("Duplicate file.");
        }
        return files;
    }
    public void save(Path path) throws IOException {
        path = path.toAbsolutePath();
        byte[] contents = toBytes();
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".timebranch-", ".tmp");
        try {
            Files.write(temp, contents);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temp); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }
    public static VersionRepository load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return fromBytes(input.readNBytes(MAX_BYTES + 1));
        } catch (NoSuchFileException missing) {
            if (Files.isSymbolicLink(path)) throw missing;
            return new VersionRepository();
        }
    }
}
