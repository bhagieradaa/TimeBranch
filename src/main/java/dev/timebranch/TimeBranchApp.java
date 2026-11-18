package dev.timebranch;

import dev.timebranch.core.DiffUtil;
import dev.timebranch.core.VersionRepository;
import dev.timebranch.core.VersionRepository.Revision;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.prefs.Preferences;

public final class TimeBranchApp extends Application {
    private static final Path HOME = Path.of(System.getProperty("user.home"), ".timebranch");
    private static final Path STATE = HOME.resolve("workspace.tbr");
    private VersionRepository repo;
    private FileChannel workspaceChannel;
    private FileLock workspaceLock;
    private Stage stage;
    private BorderPane root;
    private TreeView<String> fileTree;
    private ListView<DiffRow> diffList;
    private ComboBox<String> diffFiles;
    private boolean updatingDiffFiles;
    private TabPane views;
    private Tab diffTab;
    private Label diffContext;
    private boolean darkMode;
    private Preferences themePreferences;
    private record DiffRow(String oldNumber, String newNumber, DiffUtil.Line line) {}
    private ListView<Revision> historyList;
    private final Map<Integer, Integer> versionNumbers = new HashMap<>();
    private ComboBox<String> branches;
    private TextArea editor;
    private Label title, status, branchLabel, changesLabel, fileCounter, historyCounter;
    private String selectedFile;
    private boolean updating;
    private boolean workspaceUnsaved;
    private Button renameButton, deleteFileButton, exportFileButton, undoButton, redoButton;
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(750));
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        try {
            Files.createDirectories(HOME);
            workspaceChannel = FileChannel.open(HOME.resolve("workspace.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            workspaceLock = workspaceChannel.tryLock();
            if (workspaceLock == null) throw new IOException("Another TimeBranch window is using this workspace.");
            repo = VersionRepository.load(STATE);
        } catch (IOException | OverlappingFileLockException e) {
            releaseWorkspace();
            showWarning("Saved workspace could not be opened",
                    "TimeBranch will close to protect your workspace.\n" + STATE + "\n"
                            + (e instanceof OverlappingFileLockException
                            ? "Another TimeBranch window is using this workspace." : e.getMessage()));
            Platform.exit();
            return;
        }
        int orphanedVersionsRemoved = 0;
        IOException cleanupFailure = null;
        try {
            orphanedVersionsRemoved = repo.cleanupOrphanedVersions(STATE);
        } catch (IOException e) {
            cleanupFailure = e;
        }
        stage.setTitle("TimeBranch — Code, commit, recover");
        root = new BorderPane();
        root.getStyleClass().add("app");
        try {
            themePreferences = Preferences.userNodeForPackage(TimeBranchApp.class);
            darkMode = themePreferences.getBoolean("darkMode", false);
        } catch (SecurityException e) {
            themePreferences = null;
        }
        if (darkMode) root.getStyleClass().add("dark");
        root.setTop(buildHeader());
        root.setCenter(buildWorkspace());
        root.setBottom(buildStatus());
        Scene scene = new Scene(root, 1470, 920);
        var resource = getClass().getResource("/styles/dashboard.css");
        if (resource != null) scene.getStylesheets().add(resource.toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(1040);
        stage.setMinHeight(690);
        saveDelay.setOnFinished(event -> {
            if (persist(false)) setStatus("Workspace saved");
        });
        refreshAll();
        stage.setOnCloseRequest(event -> {
            saveDelay.stop();
            if (!persist(true)) event.consume();
        });
        stage.show();
        if (cleanupFailure != null) {
            setStatus("Orphaned versions could not be saved");
            showWarning("Could not clean up orphaned versions", cleanupFailure.getMessage());
        } else if (orphanedVersionsRemoved > 0) {
            setStatus("Removed " + orphanedVersionsRemoved + " orphaned "
                    + (orphanedVersionsRemoved == 1 ? "version" : "versions"));
        }
    }

    @Override public void stop() {
        saveDelay.stop();
        if (!persist(false)) System.err.println("TimeBranch could not save the workspace on exit.");
        releaseWorkspace();
    }

    private void releaseWorkspace() {
        if (workspaceChannel == null) return;
        try {
            workspaceChannel.close();
        } catch (IOException e) {
            System.err.println("Could not close the TimeBranch workspace lock: " + e.getMessage());
        }
    }

    private Node buildHeader() {
        Label brand = label("TIMEBRANCH", "brand");
        branches = new ComboBox<>();
        branches.getStyleClass().add("branch-selector");
        branches.setAccessibleText("Current branch");
        branches.setPrefWidth(240);
        branches.valueProperty().addListener((o, a, b) -> {
            if (!updating && b != null && !b.equals(repo.branch())) switchBranch(b);
        });
        Button theme = new Button(darkMode ? "LIGHT MODE" : "DARK MODE");
        theme.setOnAction(event -> {
            darkMode = !darkMode;
            if (darkMode) root.getStyleClass().add("dark");
            else root.getStyleClass().remove("dark");
            theme.setText(darkMode ? "LIGHT MODE" : "DARK MODE");
            if (themePreferences != null) {
                try {
                    themePreferences.putBoolean("darkMode", darkMode);
                } catch (SecurityException e) {
                    setStatus("Theme changed for this session");
                }
            }
        });
        HBox bar = spaced(brand, new HBox(12, branches, theme));
        bar.getStyleClass().add("topbar");
        return bar;
    }

    private Node buildWorkspace() {
        VBox explorer = buildExplorer();
        explorer.setPrefWidth(235);
        explorer.setMinWidth(200);
        VBox editorPanel = buildEditor();
        VBox.setVgrow(editorPanel, Priority.ALWAYS);
        VBox main = new VBox(editorPanel, buildHistory());
        HBox.setHgrow(main, Priority.ALWAYS);
        return new HBox(explorer, main);
    }

    private VBox buildExplorer() {
        Button add = smallButton("+", this::newFile);
        add.setAccessibleText("New file");
        Button importButton = smallButton("⇧", this::importFile);
        importButton.setAccessibleText("Import file");
        HBox head = spaced(label("REPOSITORY", "eyebrow"), new HBox(8, add, importButton));
        head.getStyleClass().add("section-header");
        fileTree = new TreeView<>();
        fileTree.getStyleClass().add("file-tree");
        fileTree.setCellFactory(tree -> new TreeCell<>() {
            @Override protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null);
                    return;
                }
                String name = path.substring(path.lastIndexOf('/') + 1);
                setText(getTreeItem() == fileTree.getRoot() ? name + "/" : "▤  " + name);
            }
        });
        fileTree.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            openFile(item == null || item == fileTree.getRoot() ? null : item.getValue());
        });
        VBox.setVgrow(fileTree, Priority.ALWAYS);

        renameButton = button("Rename", "compact-button", this::renameFile);
        deleteFileButton = button("Delete", "danger-button", this::deleteFile);
        exportFileButton = button("Export selected file", "wide-button", this::exportFile);
        VBox tools = new VBox(8, new HBox(8, renameButton, deleteFileButton),
                exportFileButton,
                button("Export All Files", "wide-button", this::exportAllFiles),
                button("New branch", "wide-button", this::createBranch),
                button("Delete Branch", "wide-button danger-button", this::deleteBranch),
                button("Recycle Bin", "wide-button", this::recoverDeleted));
        tools.getStyleClass().add("sidebar-tools");
        VBox explorer = new VBox(head, fileTree, tools);
        explorer.getStyleClass().add("explorer-panel");
        return explorer;
    }

    private VBox buildEditor() {
        branchLabel = label("main", "branch-chip");
        changesLabel = label("", "mono");
        HBox summary = new HBox(
                summaryCell("BRANCH / HEAD", branchLabel),
                summaryCell("WORKING TREE", changesLabel));
        summary.getStyleClass().add("summary");

        Button commit = button("COMMIT", "action-button", this::saveVersion);
        Button revert = button("REVERT", "action-button danger-button", this::restoreSelected);
        for (Button action : List.of(commit, revert)) {
            action.setPrefWidth(180);
            action.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(action, Priority.ALWAYS);
        }
        HBox actions = new HBox(14, commit, revert);
        actions.getStyleClass().add("action-bar");

        title = label("No file selected", "editor-filename");
        title.setMinWidth(0);
        editor = new TextArea();
        editor.setWrapText(false);
        editor.getStyleClass().add("code-editor");
        editor.setTextFormatter(new TextFormatter<>(change -> {
            if (updating || !change.isContentChange()) return change;
            if (selectedFile == null) return null;
            try {
                String inserted = EditorText.display(change.getText());
                int oldEnd = change.getRangeStart() + change.getText().length();
                int newEnd = change.getRangeStart() + inserted.length();
                if (oldEnd != newEnd) {
                    int caret = change.getCaretPosition();
                    int anchor = change.getAnchor();
                    change.setText(inserted);
                    change.selectRange(anchor >= oldEnd ? anchor + newEnd - oldEnd : Math.min(anchor, newEnd),
                            caret >= oldEnd ? caret + newEnd - oldEnd : Math.min(caret, newEnd));
                } else {
                    change.setText(inserted);
                }
                String source = repo.content(selectedFile);
                String edited = EditorText.applyEdit(source, change.getRangeStart(), change.getRangeEnd(), inserted);
                if (source.equals(edited)) return change;
                repo.editFile(selectedFile, edited);
                updateMeta();
                workspaceUnsaved = true;
                setStatus("Saving workspace…");
                saveDelay.playFromStart();
                return change;
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> error(e));
                return null;
            }
        }));

        diffContext = label("", "diff-context");
        diffContext.setMinWidth(0);
        Tooltip contextTooltip = new Tooltip();
        contextTooltip.textProperty().bind(diffContext.textProperty());
        diffContext.setTooltip(contextTooltip);
        diffFiles = new ComboBox<>();
        diffFiles.setPromptText("No files to compare");
        diffFiles.setAccessibleText("Diff file");
        diffFiles.setPrefWidth(235);
        diffFiles.valueProperty().addListener((o, a, b) -> {
            if (!updatingDiffFiles) refreshDiff(false);
        });
        HBox diffHeader = spaced(diffContext, diffFiles);
        diffHeader.getStyleClass().add("diff-header");
        diffList = newDiffList();
        VBox diff = new VBox(diffHeader, diffList);
        VBox.setVgrow(diffList, Priority.ALWAYS);
        diffTab = new Tab("DIFF", diff);
        views = new TabPane(new Tab("EDIT", editor), diffTab);
        views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        views.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshDiff(true));
        VBox.setVgrow(views, Priority.ALWAYS);

        undoButton = button("Undo", "compact-button", () -> undoWorkspace(false));
        redoButton = button("Redo", "compact-button", () -> undoWorkspace(true));
        configureEditorActions();
        Button compare = button("Compare versions", "compact-button", this::compareVersions);
        HBox fileBar = spaced(title, new HBox(8, undoButton, redoButton, compare));
        fileBar.getStyleClass().add("file-bar");
        return new VBox(summary, actions, fileBar, views);
    }

    private VBox summaryCell(String caption, Label value) {
        VBox cell = new VBox(4, label(caption, "eyebrow"), value);
        cell.getStyleClass().add("summary-cell");
        if (value == branchLabel) cell.getStyleClass().add("branch-summary");
        cell.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cell, Priority.ALWAYS);
        return cell;
    }

    private void configureEditorActions() {
        KeyCombination undo = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
        KeyCombination redo = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCombination alternateRedo = new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN);
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (undo.match(event) || redo.match(event) || alternateRedo.match(event)) {
                undoWorkspace(!undo.match(event));
                event.consume();
            }
        });
        MenuItem undoItem = new MenuItem("Undo");
        undoItem.disableProperty().bind(undoButton.disableProperty());
        undoItem.setOnAction(event -> undoWorkspace(false));
        MenuItem redoItem = new MenuItem("Redo");
        redoItem.disableProperty().bind(redoButton.disableProperty());
        redoItem.setOnAction(event -> undoWorkspace(true));
        MenuItem cut = new MenuItem("Cut");
        cut.disableProperty().bind(editor.selectedTextProperty().isEmpty());
        cut.setOnAction(event -> editor.cut());
        MenuItem copy = new MenuItem("Copy");
        copy.disableProperty().bind(editor.selectedTextProperty().isEmpty());
        copy.setOnAction(event -> editor.copy());
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(event -> editor.paste());
        MenuItem selectAll = new MenuItem("Select All");
        selectAll.setOnAction(event -> editor.selectAll());
        editor.setContextMenu(new ContextMenu(undoItem, redoItem, new SeparatorMenuItem(), cut, copy, paste, selectAll));
    }

    private void undoWorkspace(boolean redo) {
        if (!(redo ? repo.redo() : repo.undo())) return;
        execute(() -> {
            refreshAll();
            setStatus(redo ? "Workspace change redone" : "Workspace change undone");
        });
    }

    private VBox buildHistory() {
        historyCounter = label("", "mono");
        Button working = button("Working tree", "compact-button", () -> {
            historyList.getSelectionModel().clearSelection();
            views.getSelectionModel().select(diffTab);
        });
        Button deleteVersion = button("Delete Version", "danger-button", this::deleteSelectedVersion);
        deleteVersion.setDisable(true);
        HBox heading = spaced(new HBox(12, label("COMMIT HISTORY", "eyebrow"), historyCounter),
                new HBox(8, working, deleteVersion));
        heading.getStyleClass().add("history-header");
        historyList = new ListView<>();
        historyList.setPrefHeight(148);
        historyList.getStyleClass().add("history-list");
        historyList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Revision r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setGraphic(null);
                    return;
                }
                Label id = label(versionLabel(r.id), "revision-id");
                id.setMinWidth(Region.USE_PREF_SIZE);
                Label description = label(r.message, "revision-message");
                description.setMinWidth(0);
                description.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(description, Priority.ALWAYS);
                String date = formatter.format(Instant.ofEpochMilli(r.createdAt));
                Label origin = label(r.branch + "  ·  " + date, "mono");
                origin.setTooltip(new Tooltip("Committed on " + r.branch));
                HBox row = new HBox(12, id, description, origin);
                row.setAlignment(Pos.CENTER_LEFT);
                row.prefWidthProperty().bind(widthProperty().subtract(24));
                setGraphic(row);
            }
        });
        historyList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            deleteVersion.setDisable(b == null);
            if (!updating) {
                if (b != null) views.getSelectionModel().select(diffTab);
                refreshDiff(true);
            }
        });
        VBox history = new VBox(heading, historyList);
        history.getStyleClass().add("history-panel");
        return history;
    }

    private Node buildStatus() {
        status = label("Ready", "mono");
        status.setMinWidth(0);
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(status.textProperty());
        status.setTooltip(statusTooltip);
        fileCounter = label("0 files", "mono");
        HBox footer = spaced(status, fileCounter);
        footer.getStyleClass().add("statusbar");
        return footer;
    }

    private ListView<DiffRow> newDiffList() {
        ListView<DiffRow> list = new ListView<>();
        list.getStyleClass().add("diff-list");
        list.setFixedCellSize(24);
        list.setCellFactory(view -> new ListCell<>() {
            private final Label oldNumber = label("", "line-number");
            private final Label newNumber = label("", "new-number");
            private final Label code = label("", "diff-code");
            private final HBox row = new HBox(oldNumber, newNumber, code);

            @Override protected void updateItem(DiffRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("added", "removed");
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                oldNumber.setText(item.oldNumber());
                newNumber.setText(item.newNumber());
                DiffUtil.Line line = item.line();
                String ending = line.kind() == ' ' ? "" : switch (line.ending()) {
                    case "\n" -> "";
                    case "\r\n" -> "  [CRLF]";
                    case "\r" -> "  [CR]";
                    case "" -> "  [no newline]";
                    default -> "  [Unicode line break]";
                };
                code.setText(line.kind() + "  " + line.text() + ending);
                if (item.line().kind() == '+') getStyleClass().add("added");
                if (item.line().kind() == '-') getStyleClass().add("removed");
                setGraphic(row);
            }
        });
        return list;
    }

    private void showDiff(ListView<DiffRow> list, String before, String after) {
        List<DiffRow> rows = new ArrayList<>();
        int oldNumber = 0;
        int newNumber = 0;
        for (DiffUtil.Line line : DiffUtil.compare(before, after)) {
            String left = line.kind() == '+' ? "" : Integer.toString(++oldNumber);
            String right = line.kind() == '-' ? "" : Integer.toString(++newNumber);
            rows.add(new DiffRow(left, right, line));
        }
        list.getItems().setAll(rows);
    }

    private void refreshDiff(boolean selectRelevantFile) {
        if (historyList == null || views.getSelectionModel().getSelectedItem() != diffTab) return;
        Revision selected = historyList.getSelectionModel().getSelectedItem();
        Revision base = selected == null ? repo.revision(repo.headId())
                : selected.parentId > 0 ? repo.revision(selected.parentId) : null;
        Map<String, String> before = base == null ? Map.of() : base.files;
        Map<String, String> after = selected == null ? repo.files() : selected.files;
        if (selectRelevantFile) {
            updatingDiffFiles = true;
            try {
                selectDiffFile(diffFiles, before, after, selectedFile);
            } finally {
                updatingDiffFiles = false;
            }
        }
        String path = diffFiles.getValue();
        String oldText = path == null ? null : before.get(path);
        String newText = path == null ? null : after.get(path);
        String context = selected == null ? "WORKING TREE  /  BASE " + versionLabel(repo.headId())
                : versionLabel(selected.id) + "  /  " + selected.message;
        diffContext.setText(context + "  /  " + diffStatus(path, oldText, newText));
        showDiff(diffList, oldText, newText);
    }

    private static void selectDiffFile(ComboBox<String> control, Map<String, String> before,
                                       Map<String, String> after, String preferred) {
        List<String> changed = VersionRepository.changedFiles(before, after);
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        control.getItems().setAll(names);
        String target = preferred != null && changed.contains(preferred) ? preferred
                : !changed.isEmpty() ? changed.get(0)
                : preferred != null && names.contains(preferred) ? preferred
                : names.isEmpty() ? null : names.iterator().next();
        control.setValue(target);
        control.setDisable(names.isEmpty());
    }

    private static String diffStatus(String path, String before, String after) {
        if (path == null) return "NO FILES TO COMPARE";
        if (Objects.equals(before, after)) return "NO CHANGES";
        if (before == null) return "ADDED";
        if (after == null) return "DELETED";
        return "MODIFIED";
    }

    private void populateTree(String wanted) {
        TreeItem<String> rootItem = new TreeItem<>("workspace");
        rootItem.setExpanded(wanted != null || fileTree.getRoot() == null || fileTree.getRoot().isExpanded());
        TreeItem<String> selected = null;
        for (String path : new TreeSet<>(repo.files().keySet())) {
            TreeItem<String> item = new TreeItem<>(path);
            rootItem.getChildren().add(item);
            if (path.equals(wanted)) selected = item;
        }
        fileTree.setRoot(rootItem);
        if (selected != null) fileTree.getSelectionModel().select(selected);
    }

    private void refreshAll() {
        updating = true;
        try {
            String wanted = selectedFile != null && repo.files().containsKey(selectedFile)
                    ? selectedFile : repo.files().keySet().stream().findFirst().orElse(null);
            populateTree(wanted);
            selectedFile = wanted;
            editor.setDisable(wanted == null);
            editor.setText(wanted == null ? "" : EditorText.display(repo.content(wanted)));
            branches.setItems(FXCollections.observableArrayList(repo.branches()));
            branches.setValue(repo.branch());
            List<Revision> revisions = repo.revisions();
            versionNumbers.clear();
            for (int i = 0; i < revisions.size(); i++) {
                versionNumbers.put(revisions.get(i).id, revisions.size() - i);
            }
            historyList.setItems(FXCollections.observableArrayList(revisions));
            historyList.getSelectionModel().clearSelection();
            historyList.refresh();
            updateMeta();
            refreshDiff(true);
        } finally { updating = false; }
    }
    private String versionLabel(int id) {
        Integer number = versionNumbers.get(id);
        return number == null ? "Unknown version" : "Version " + number;
    }

    private void updateMeta() {
        title.setText(selectedFile == null ? "No file selected" : selectedFile);
        branchLabel.setText(repo.branch() + "  →  " + versionLabel(repo.headId()));
        Map<String, String> base = repo.revision(repo.headId()).files;
        Map<String, String> files = repo.files();
        int changed = VersionRepository.changedFiles(base, files).size();
        changesLabel.setText(changed == 0 ? "□ CLEAN" : "■ DIRTY  /  " + changed + " CHANGED");
        fileCounter.setText(repo.files().size() + " files");
        historyCounter.setText(versionNumbers.size() + " snapshots");
        renameButton.setDisable(selectedFile == null);
        deleteFileButton.setDisable(selectedFile == null);
        exportFileButton.setDisable(selectedFile == null);
        undoButton.setDisable(!repo.canUndo());
        redoButton.setDisable(!repo.canRedo());
    }
    private void openFile(String name) {
        if (updating) return;
        selectedFile = name;
        updating = true;
        try { editor.setDisable(name == null); editor.setText(name == null ? "" : EditorText.display(repo.content(name))); }
        finally { updating = false; }
        updateMeta();
        refreshDiff(true);
        if (name != null && diffFiles.getItems().contains(name)) diffFiles.setValue(name);
    }
    private boolean persist(boolean showError) {
        if (!workspaceUnsaved) return true;
        try {
            repo.save(STATE);
            workspaceUnsaved = false;
            return true;
        } catch (IOException | RuntimeException e) {
            String message = "Workspace not saved: " + e.getMessage();
            if (showError) showWarning("Could not save the workspace", message + "\nYour changes are still open in TimeBranch.");
            setStatus(message);
            return false;
        }
    }
    private void setStatus(String msg) { if(status != null) status.setText(msg); }
    private boolean confirm(String heading,String explanation) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,explanation,ButtonType.YES,ButtonType.CANCEL);
        styleDialog(alert);alert.setTitle("TimeBranch");alert.setHeaderText(heading);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
    }
    private Optional<String> prompt(String heading,String example) {
        TextInputDialog dialog = new TextInputDialog(example);
        dialog.setTitle("TimeBranch");dialog.setHeaderText(heading);dialog.setContentText("");
        styleDialog(dialog);
        return dialog.showAndWait().filter(s -> !s.isBlank());
    }
    private void newFile() {
        prompt("Name your new file","NewFile.java").ifPresent(value -> execute(() -> {
            repo.createFile(value);selectedFile=value;refreshAll();
        }));
    }
    private void renameFile() {
        if (selectedFile == null) return;
        String old = selectedFile;
        prompt("Rename " + old,old).ifPresent(value -> execute(() -> {
            repo.renameFile(old,value);selectedFile=value;refreshAll();
        }));
    }
    private void deleteFile() {
        if (selectedFile == null) return;
        String name = selectedFile;
        if (!confirm("Delete " + name + "?",
                "This removes the file from your workspace. Recycle Bin can recover a copy only if this file "
                        + "was committed on the current branch or in its shared history.")) return;
        execute(() -> { repo.deleteFile(name);selectedFile=null;refreshAll(); });
    }
    private void importFile() {
        FileChooser chooser = new FileChooser();chooser.setTitle("Import text or source file");
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        execute(() -> {
            selectedFile = importSource(repo, file.toPath());
            refreshAll();
            setStatus("Imported " + selectedFile);
        });
    }

    static String importSource(VersionRepository repository, Path source) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source)) {
            bytes = input.readNBytes(VersionRepository.MAX_FILE_BYTES + 1);
        }
        if (bytes.length > VersionRepository.MAX_FILE_BYTES) throw new IOException("Files are limited to 1 MiB.");
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Choose a text file encoded in UTF-8.", e);
        }
        String name = source.getFileName().toString();
        repository.createFile(name, content);
        return name;
    }

    private void exportFile() {
        if (selectedFile == null) {setStatus("Select a file first.");return;}
        FileChooser chooser = new FileChooser();chooser.setTitle("Export code to disk");chooser.setInitialFileName(selectedFile);
        File output = chooser.showSaveDialog(stage);
        if (output == null) return;
        try {
            exportSource(output.toPath(), STATE, repo.content(selectedFile));
            setStatus("Exported " + output.getName());
        } catch (IOException e) {
            error(e);
        }
    }

    static void exportSource(Path destination, Path workspace, String content) throws IOException {
        Path target = destination.toAbsolutePath();
        Path state = workspace.toAbsolutePath();
        if (target.normalize().equals(state.normalize()) || isWorkspaceAlias(target, state))
            throw new IOException("Choose an export location outside the saved workspace file.");
        Path temporary = Files.createTempFile(target.getParent(), ".timebranch-export-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static boolean isWorkspaceAlias(Path target, Path workspace) throws IOException {
        try {
            if (Files.isSameFile(target, workspace)) return true;
        } catch (NoSuchFileException e) {
            // The workspace or export target might not have been created yet.
        }
        return target.getFileName().equals(workspace.getFileName())
                && Files.isDirectory(workspace.getParent())
                && Files.isSameFile(target.getParent(), workspace.getParent());
    }
    private void exportAllFiles() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a destination for TimeBranchProject");
        File destination = chooser.showDialog(stage);
        if (destination == null) return;
        try {
            Path exported = exportWorkspace(destination.toPath(), repo.files());
            setStatus("Exported " + repo.files().size() + " files to " + exported);
        } catch (IOException e) {
            error(e);
        }
    }

    static Path exportWorkspace(Path destination, Map<String, String> files) throws IOException {
        Path project = destination.resolve("TimeBranchProject");
        try {
            Files.createDirectory(project);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("TimeBranchProject already exists. Choose a different destination.", e);
        }

        List<Path> created = new ArrayList<>();
        try {
            for (var file : files.entrySet()) {
                Path output;
                try {
                    output = project.resolve(file.getKey()).normalize();
                } catch (InvalidPathException e) {
                    throw new IOException("Cannot export filename: " + file.getKey(), e);
                }
                if (!project.normalize().equals(output.getParent())) {
                    throw new IOException("Cannot export filename: " + file.getKey());
                }
                try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    created.add(output);
                    writer.write(file.getValue());
                }
            }
        } catch (IOException failure) {
            created.add(project);
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return project;
    }

    private void saveVersion() {
        prompt("Describe this version","Updated " + (selectedFile==null ? "workspace":selectedFile))
                .ifPresent(message -> execute(() -> {
                    int id = repo.commit(message.trim());
                    refreshAll();setStatus("Saved " + versionLabel(id));
                }));
    }
    private void createBranch() {
        prompt("Create a branch from " + versionLabel(repo.headId()),"feature/new-idea").map(String::trim).ifPresent(name -> {
            if (repo.dirty() && !confirm("Carry your uncommitted changes to " + name + "?",
                    "The new branch starts at the current commit. Your edits will move to its working tree. "
                            + "Returning to " + repo.branch() + " will show that branch’s committed files.")) return;
            execute(() -> {
                    repo.createAndCheckoutBranch(name);
                    refreshAll();setStatus("Created branch " + name);
            });
        });
    }
    private void deleteBranch() {
        List<String> deletable = repo.branches().stream().filter(name -> !name.equals("main")).toList();
        if (deletable.isEmpty()) {
            info("No branches to delete", "The main branch is protected.");
            return;
        }
        String initial = deletable.contains(repo.branch()) ? repo.branch() : deletable.get(0);
        ChoiceDialog<String> dialog = new ChoiceDialog<>(initial, deletable);
        dialog.setTitle("Delete Branch");
        dialog.setHeaderText("Choose a branch to delete");
        dialog.setContentText("Branch");
        styleDialog(dialog);
        dialog.showAndWait().ifPresent(name -> {
            boolean dirty = repo.dirty();
            String explanation = "This deletes the branch and removes versions that no remaining branch references. "
                    + "Shared commits are kept. "
                    + "TimeBranch will restore the last committed files on main.";
            if (dirty) explanation += " Your uncommitted changes on " + repo.branch() + " will be discarded.";
            if (!confirm("Delete branch " + name + "?", explanation)) return;
            try {
                repo.deleteBranch(name, dirty, STATE);
                saveDelay.stop();
                workspaceUnsaved = false;
                selectedFile = null;
                refreshAll();
                setStatus("Deleted branch " + name + " · Restored main");
            } catch (IOException | RuntimeException e) {
                error(e);
            }
        });
    }

    private void switchBranch(String name) {
        if (repo.dirty() && !confirm("Discard unversioned edits?",
                "Switching branches replaces the working files with that branch's last snapshot. Save a version first if you need these edits.")) {
            resetBranchSelector();
            return;
        }
        try {
            repo.checkout(name, STATE);
            saveDelay.stop();
            workspaceUnsaved = false;
            selectedFile = null;
            refreshAll();
            setStatus("On branch " + name);
        } catch (IOException | RuntimeException e) {
            resetBranchSelector();
            error(e);
        }
    }

    private void resetBranchSelector() {
        updating = true;
        try { branches.setValue(repo.branch()); }
        finally { updating = false; }
    }
    private void restoreSelected() {
        Revision selected = historyList.getSelectionModel().getSelectedItem();
        Revision r = selected == null ? repo.revision(repo.headId()) : selected;
        if (!repo.isInCurrentBranchHistory(r.id)) {
            info("Cannot restore " + versionLabel(r.id), "Choose a version from the current branch's history.");
            return;
        }
        if (!confirm("Restore " + versionLabel(r.id) + "?",
                "Replace your working files with this snapshot. The timeline remains unchanged. Save a new version afterward to preserve the recovery.")) return;
        execute(() -> {repo.restoreVersion(r.id);selectedFile=null;refreshAll();setStatus("Restored " + versionLabel(r.id) + " into workspace");});
    }
    private void deleteSelectedVersion() {
        Revision selected = historyList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            repo.validateVersionDeletion(selected.id);
        } catch (IllegalArgumentException e) {
            info("Cannot delete " + versionLabel(selected.id), e.getMessage());
            return;
        }
        if (!confirm("Permanently delete " + versionLabel(selected.id) + "?",
                "This snapshot will be removed from history and can no longer be restored. "
                        + "The current branch will move back to " + versionLabel(selected.parentId)
                        + ". Your working files will stay as they are and may show as uncommitted changes.")) return;
        try {
            repo.deleteVersion(selected.id, STATE);
            saveDelay.stop();
            workspaceUnsaved = false;
            refreshAll();
            setStatus("Version deleted · HEAD is now " + versionLabel(repo.headId()));
        } catch (IOException | RuntimeException e) {
            error(e);
        }
    }

    private void recoverDeleted() {
        List<String> deleted=repo.recoverableFiles();
        if (deleted.isEmpty()) {
            info("No deleted files to recover", "Recycle Bin looks for missing files in the current branch’s committed history.");
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(deleted.get(0),deleted);
        dialog.setTitle("Recycle Bin");dialog.setHeaderText("Choose a file to recover");
        dialog.setContentText("Deleted file:");styleDialog(dialog);
        dialog.showAndWait().ifPresent(file -> execute(() -> {
            repo.recoverFile(file);selectedFile=file;refreshAll();setStatus("Recovered " + file);
        }));
    }
    private void compareVersions() {
        List<Revision> revisions = repo.revisions();
        if (revisions.isEmpty()) return;
        Dialog<Void> dialog = new Dialog<>();dialog.setTitle("Compare versions");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        styleDialog(dialog);
        ComboBox<Revision> from = new ComboBox<>(FXCollections.observableArrayList(revisions));
        ComboBox<Revision> to = new ComboBox<>(FXCollections.observableArrayList(revisions));
        ComboBox<String> files = new ComboBox<>();
        from.setPrefWidth(270);
        to.setPrefWidth(270);
        files.setPrefWidth(235);
        files.setPromptText("No files to compare");
        files.setAccessibleText("Compare file");
        from.setCellFactory(l -> revisionCell());
        to.setCellFactory(l -> revisionCell());
        from.setButtonCell(revisionCell());to.setButtonCell(revisionCell());
        Revision selected = historyList.getSelectionModel().getSelectedItem();
        Revision target = selected == null ? repo.revision(repo.headId()) : selected;
        from.setValue(target.parentId == 0 ? target : repo.revision(target.parentId));
        to.setValue(target);
        HBox selectors = new HBox(12,
                new VBox(6,label("FROM","eyebrow"),from),
                new VBox(6,label("TO","eyebrow"),to),
                new VBox(6,label("FILE","eyebrow"),files));
        ListView<DiffRow> diffArea = newDiffList();
        diffArea.setPrefSize(860, 510);
        Label summary = label("", "diff-context");
        Runnable update = () -> {
            String path = files.getValue();
            Revision a = from.getValue(), b = to.getValue();
            String oldText = path == null || a == null ? null : a.files.get(path);
            String newText = path == null || b == null ? null : b.files.get(path);
            summary.setText(diffStatus(path, oldText, newText));
            showDiff(diffArea, oldText, newText);
        };
        Runnable updateFiles = () -> {
            Revision a = from.getValue(), b = to.getValue();
            String preferred = files.getValue() == null ? selectedFile : files.getValue();
            selectDiffFile(files, a == null ? Map.of() : a.files, b == null ? Map.of() : b.files, preferred);
            update.run();
        };
        from.valueProperty().addListener((o,a,b)->updateFiles.run());
        to.valueProperty().addListener((o,a,b)->updateFiles.run());
        files.valueProperty().addListener((o,a,b)->update.run());
        updateFiles.run();
        VBox layout = new VBox(12, selectors, summary, diffArea);
        layout.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(layout);
        dialog.showAndWait();
    }
    private ListCell<Revision> revisionCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Revision item,boolean empty) {
                super.updateItem(item,empty);
                setText(empty || item==null ? null : versionLabel(item.id) + "  " + item.message);
            }
        };
    }
    @FunctionalInterface private interface Task {void run() throws Exception;}
    private void execute(Task action) {
        try {
            action.run();
            saveDelay.stop();
            workspaceUnsaved = true;
            persist(true);
        }
        catch(Exception e) {error(e);}
    }
    private void error(Exception e) {
        setStatus("Action failed");
        Alert alert=new Alert(Alert.AlertType.ERROR,e.getMessage()==null?e.toString():e.getMessage(),ButtonType.OK);
        alert.setTitle("TimeBranch");alert.setHeaderText("Could not complete action");styleDialog(alert);alert.showAndWait();
    }
    private void info(String header,String description) {
        Alert alert=new Alert(Alert.AlertType.INFORMATION,description,ButtonType.OK);
        alert.setTitle("TimeBranch");alert.setHeaderText(header);styleDialog(alert);alert.showAndWait();
    }
    private void showWarning(String header,String description) {
        Alert alert=new Alert(Alert.AlertType.WARNING,description,ButtonType.OK);
        alert.setTitle("TimeBranch");alert.setHeaderText(header);alert.showAndWait();
    }
    private void styleDialog(Dialog<?> dialog) {
        if (stage.isShowing()) {
            dialog.initOwner(stage);
            dialog.initModality(Modality.WINDOW_MODAL);
        }
        var css=getClass().getResource("/styles/dashboard.css");
        if(css!=null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("timebranch-dialog");
        if (darkMode) dialog.getDialogPane().getStyleClass().add("dark");
    }
    private Label label(String text,String css) {Label l=new Label(text);l.getStyleClass().add(css);return l;}
    private Button button(String text,String css,Runnable onClick) {
        Button b=new Button(text);for(String style:css.split(" "))b.getStyleClass().add(style);
        b.setOnAction(e->onClick.run());return b;
    }
    private Button smallButton(String text,Runnable action) {return button(text,"icon-button",action);}
    private HBox spaced(Node left,Node right) {
        Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);
        HBox h=new HBox(10,left,gap,right);h.setAlignment(Pos.CENTER_LEFT);return h;
    }
}
