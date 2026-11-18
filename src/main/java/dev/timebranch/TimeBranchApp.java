package dev.timebranch;

import dev.timebranch.cloud.GitHubBackup;
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
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class TimeBranchApp extends Application {
    private static final Path HOME = Path.of(System.getProperty("user.home"), ".timebranch");
    private static final Path STATE = HOME.resolve("workspace.tbr");
    private VersionRepository repo;
    private Stage stage;
    private BorderPane root;
    private TreeView<String> fileTree;
    private final Set<String> collapsedFolders = new HashSet<>();
    private ListView<DiffRow> diffList;
    private TabPane views;
    private Tab diffTab;
    private Label diffContext;
    private boolean darkMode;
    private record DiffRow(String oldNumber, String newNumber, DiffUtil.Line line) {}
    private ListView<Revision> historyList;
    private ComboBox<String> branches;
    private TextArea editor;
    private Label title, status, branchLabel, changesLabel, fileCounter, historyCounter;
    private String selectedFile;
    private boolean updating;
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(750));
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private final GitHubBackup cloud = new GitHubBackup();
    private record CloudTarget(String owner, String repository, String token) {}
    private String cloudOwner, cloudRepo, cloudToken;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        try {
            repo = VersionRepository.load(STATE);
        } catch (IOException e) {
            repo = new VersionRepository();
            showWarning("Saved workspace could not be opened",
                    "A fresh workspace was created. The unreadable backup was not deleted.\n" + e.getMessage());
        }
        stage.setTitle("TimeBranch — Code, commit, recover");
        root = new BorderPane();
        root.getStyleClass().add("app");
        darkMode = java.util.prefs.Preferences.userNodeForPackage(TimeBranchApp.class).getBoolean("darkMode", false);
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
        saveDelay.setOnFinished(event -> persist());
        refreshAll();
        stage.setOnCloseRequest(event -> { saveDelay.stop(); persist(); });
        stage.show();
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
            java.util.prefs.Preferences.userNodeForPackage(TimeBranchApp.class).putBoolean("darkMode", darkMode);
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
                setText(getTreeItem().isLeaf() ? "▤  " + name : name + "/");
            }
        });
        fileTree.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item == null || item.isLeaf()) openFile(item == null ? null : item.getValue());
        });
        VBox.setVgrow(fileTree, Priority.ALWAYS);

        Button rename = button("Rename", "compact-button", this::renameFile);
        Button remove = button("Delete", "danger-button", this::deleteFile);
        VBox tools = new VBox(8, new HBox(8, rename, remove),
                button("Export selected file", "wide-button", this::exportFile),
                button("New branch", "wide-button", this::createBranch),
                button("Recover deleted file", "wide-button", this::recoverDeleted),
                button("Cloud settings", "wide-button", this::cloudSettings),
                button("Cloud restore", "wide-button", this::downloadCloud));
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
        Button push = button("PUSH", "action-button", this::uploadCloud);
        push.setTooltip(new Tooltip("Back up the workspace to GitHub"));
        Button revert = button("REVERT", "action-button danger-button", this::restoreSelected);
        for (Button action : List.of(commit, push, revert)) {
            action.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(action, Priority.ALWAYS);
        }
        HBox actions = new HBox(14, commit, push, revert);
        actions.getStyleClass().add("action-bar");

        title = label("No file selected", "editor-filename");
        title.setMinWidth(0);
        editor = new TextArea();
        editor.setWrapText(false);
        editor.getStyleClass().add("code-editor");
        editor.textProperty().addListener((o, previous, current) -> {
            if (updating || selectedFile == null || !repo.files().containsKey(selectedFile)) return;
            try {
                repo.editFile(selectedFile, current);
                updateMeta();
                saveDelay.playFromStart();
            } catch (Exception e) { error(e); }
        });

        diffContext = label("", "diff-context");
        Tooltip contextTooltip = new Tooltip();
        contextTooltip.textProperty().bind(diffContext.textProperty());
        diffContext.setTooltip(contextTooltip);
        diffList = newDiffList();
        VBox diff = new VBox(diffContext, diffList);
        VBox.setVgrow(diffList, Priority.ALWAYS);
        diffTab = new Tab("DIFF", diff);
        views = new TabPane(new Tab("EDIT", editor), diffTab);
        views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        views.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshDiff());
        VBox.setVgrow(views, Priority.ALWAYS);

        Button undo = button("Undo", "compact-button", () -> { if (repo.undo()) refreshAll(); });
        Button redo = button("Redo", "compact-button", () -> { if (repo.redo()) refreshAll(); });
        Button compare = button("Compare versions", "compact-button", this::compareVersions);
        HBox fileBar = spaced(title, new HBox(8, undo, redo, compare));
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

    private VBox buildHistory() {
        historyCounter = label("", "mono");
        Button working = button("Working tree", "compact-button", () -> {
            historyList.getSelectionModel().clearSelection();
            views.getSelectionModel().select(diffTab);
        });
        HBox heading = spaced(new HBox(12, label("COMMIT HISTORY", "eyebrow"), historyCounter), working);
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
                Label id = label("V" + r.id, "revision-id");
                id.setMinWidth(68);
                Label description = label(r.message, "revision-message");
                description.setMinWidth(0);
                description.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(description, Priority.ALWAYS);
                String date = formatter.format(Instant.ofEpochMilli(r.createdAt));
                HBox row = new HBox(12, id, description, label(r.branch + "  ·  " + date, "mono"));
                row.setAlignment(Pos.CENTER_LEFT);
                row.prefWidthProperty().bind(widthProperty().subtract(24));
                setGraphic(row);
            }
        });
        historyList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!updating) {
                if (b != null) views.getSelectionModel().select(diffTab);
                refreshDiff();
            }
        });
        VBox history = new VBox(heading, historyList);
        history.getStyleClass().add("history-panel");
        return history;
    }

    private Node buildStatus() {
        status = label("Ready", "mono");
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
                code.setText(item.line().kind() + "  " + item.line().text());
                if (item.line().kind() == '+') getStyleClass().add("added");
                if (item.line().kind() == '-') getStyleClass().add("removed");
                setGraphic(row);
            }
        });
        return list;
    }

    private void showDiff(ListView<DiffRow> list, String before, String after) {
        List<DiffUtil.Line> lines;
        if (before != null && before.equals(after)) {
            lines = before.lines().map(line -> new DiffUtil.Line(' ', line)).toList();
        } else if (before == null || after == null) {
            String existing = before == null ? after : before;
            char kind = before == null ? '+' : '-';
            lines = existing == null ? List.of() : existing.lines().map(line -> new DiffUtil.Line(kind, line)).toList();
        } else {
            lines = DiffUtil.compare(before, after);
        }
        List<DiffRow> rows = new ArrayList<>();
        int oldNumber = 0;
        int newNumber = 0;
        for (DiffUtil.Line line : lines) {
            String left = line.kind() == '+' ? "" : Integer.toString(++oldNumber);
            String right = line.kind() == '-' ? "" : Integer.toString(++newNumber);
            rows.add(new DiffRow(left, right, line));
        }
        list.getItems().setAll(rows);
    }

    private void refreshDiff() {
        if (historyList == null || views.getSelectionModel().getSelectedItem() != diffTab) return;
        Revision selected = historyList.getSelectionModel().getSelectedItem();
        Revision base = selected == null ? repo.revision(repo.headId())
                : selected.parentId > 0 ? repo.revision(selected.parentId) : null;
        String before = base == null ? null : base.files.get(selectedFile);
        String after = (selected == null ? repo.files() : selected.files).get(selectedFile);
        String context = selected == null ? "WORKING TREE  /  BASE V" + repo.headId()
                : "COMMIT V" + selected.id + "  /  " + selected.message;
        diffContext.setText(context + (Objects.equals(before, after) ? "  /  NO CHANGES" : ""));
        showDiff(diffList, before, after);
    }

    private void populateTree(String wanted) {
        TreeItem<String> rootItem = new TreeItem<>("workspace");
        rootItem.setExpanded(true);
        Map<String, TreeItem<String>> folders = new HashMap<>();
        folders.put("", rootItem);
        TreeItem<String> selected = null;
        for (String path : new TreeSet<>(repo.files().keySet())) {
            TreeItem<String> parent = rootItem;
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                String folderPath = path.substring(0, slash);
                TreeItem<String> folder = folders.get(folderPath);
                if (folder == null) {
                    folder = new TreeItem<>(folderPath);
                    folder.setExpanded(!collapsedFolders.contains(folderPath)
                            || (wanted != null && wanted.startsWith(folderPath + "/")));
                    folder.expandedProperty().addListener((o, a, expanded) -> {
                        if (expanded) collapsedFolders.remove(folderPath);
                        else collapsedFolders.add(folderPath);
                    });
                    parent.getChildren().add(folder);
                    folders.put(folderPath, folder);
                }
                parent = folder;
            }
            TreeItem<String> item = new TreeItem<>(path);
            parent.getChildren().add(item);
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
            editor.setText(wanted == null ? "" : repo.content(wanted));
            branches.setItems(FXCollections.observableArrayList(repo.branches()));
            branches.setValue(repo.branch());
            historyList.setItems(FXCollections.observableArrayList(repo.revisions()));
            historyList.getSelectionModel().clearSelection();
            updateMeta();
            refreshDiff();
        } finally { updating = false; }
    }
    private void updateMeta() {
        title.setText(selectedFile == null ? "No file selected" : selectedFile);
        branchLabel.setText(repo.branch() + "  →  V" + repo.headId());
        Map<String, String> base = repo.revision(repo.headId()).files;
        Map<String, String> files = repo.files();
        Set<String> paths = new HashSet<>(base.keySet());
        paths.addAll(files.keySet());
        long changed = paths.stream().filter(path -> !Objects.equals(base.get(path), files.get(path))).count();
        changesLabel.setText(changed == 0 ? "□ CLEAN" : "■ DIRTY  /  " + changed + " CHANGED");
        fileCounter.setText(repo.files().size() + " files");
        historyCounter.setText(repo.revisions().size() + " snapshots");
    }
    private void openFile(String name) {
        if (updating) return;
        selectedFile = name;
        updating = true;
        try { editor.setDisable(name == null); editor.setText(name == null ? "" : repo.content(name)); }
        finally { updating = false; }
        updateMeta();
        refreshDiff();
    }
    private void persist() {
        try { repo.save(STATE); }
        catch (Exception e) { setStatus("Local save failed"); error(e); }
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
        return dialog.showAndWait().map(String::trim).filter(s -> !s.isBlank());
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
                "This removes the file from your workspace. Saved snapshots still contain earlier versions.")) return;
        execute(() -> { repo.deleteFile(name);selectedFile=null;refreshAll(); });
    }
    private void importFile() {
        FileChooser chooser = new FileChooser();chooser.setTitle("Import text or source file");
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        execute(() -> {
            if (Files.size(file.toPath()) > 1024 * 1024) throw new IOException("Files are limited to 1 MB.");
            String name = file.getName();
            if (repo.files().containsKey(name))
                throw new IllegalArgumentException("File already exists. Rename it before importing.");
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            repo.createFile(name);
            repo.editFile(name, content);
            selectedFile=name; refreshAll();
        });
    }
    private void exportFile() {
        if (selectedFile == null) {setStatus("Select a file first.");return;}
        FileChooser chooser = new FileChooser();chooser.setTitle("Export code to disk");chooser.setInitialFileName(selectedFile);
        File output = chooser.showSaveDialog(stage);
        if (output == null) return;
        execute(() -> {
            Files.writeString(output.toPath(),repo.content(selectedFile),StandardCharsets.UTF_8);
            setStatus("Exported " + output.getName());
        });
    }
    private void saveVersion() {
        prompt("Describe this version","Updated " + (selectedFile==null ? "workspace":selectedFile))
                .ifPresent(message -> execute(() -> {
                    int id = repo.commit(message);
                    refreshAll();setStatus("Saved version V" + id);
                }));
    }
    private void createBranch() {
        prompt("Create a branch from V" + repo.headId(),"feature/new-idea")
                .ifPresent(name -> execute(() -> {
                    repo.createAndCheckoutBranch(name);
                    refreshAll();setStatus("Created branch " + name);
                }));
    }
    private void switchBranch(String name) {
        if (repo.dirty() && !confirm("Discard unversioned edits?",
                "Switching branches replaces the working files with that branch's last snapshot. Save a version first if you need these edits."))
        { refreshAll(); return; }
        execute(() -> {repo.checkout(name);selectedFile=null;refreshAll();setStatus("On branch " + name);});
    }
    private void restoreSelected() {
        Revision selected = historyList.getSelectionModel().getSelectedItem();
        Revision r = selected == null ? repo.revision(repo.headId()) : selected;
        if (!confirm("Restore version V" + r.id + "?",
                "Replace your working files with this snapshot. The timeline remains unchanged. Save a new version afterward to preserve the recovery.")) return;
        execute(() -> {repo.restoreVersion(r.id);selectedFile=null;refreshAll();setStatus("Restored V"+r.id+" into workspace");});
    }
    private void recoverDeleted() {
        List<String> deleted=repo.recoverableFiles();
        if (deleted.isEmpty()) {
            info("No deleted files to recover","Files must have been saved in at least one version before deletion.");
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(deleted.get(0),deleted);
        dialog.setTitle("Recovery center");dialog.setHeaderText("Choose a file to recover");
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
        files.getItems().addAll(repo.files().keySet());
        for (Revision r:revisions) for (String file:r.files.keySet())
            if (!files.getItems().contains(file)) files.getItems().add(file);
        from.setCellFactory(l -> revisionCell());
        to.setCellFactory(l -> revisionCell());
        from.setButtonCell(revisionCell());to.setButtonCell(revisionCell());
        from.getSelectionModel().select(revisions.size() > 1 ? 1 : 0);
        to.getSelectionModel().selectFirst();
        files.getSelectionModel().select(selectedFile == null ? files.getItems().stream().findFirst().orElse(null) : selectedFile);
        HBox selectors = new HBox(12,
                new VBox(6,label("FROM","eyebrow"),from),
                new VBox(6,label("TO","eyebrow"),to),
                new VBox(6,label("FILE","eyebrow"),files));
        ListView<DiffRow> diffArea = newDiffList();
        diffArea.setPrefSize(860, 510);
        Runnable update = () -> {
            Revision a = from.getValue(), b = to.getValue();
            String file = files.getValue();
            if (a != null && b != null && file != null) showDiff(diffArea, a.files.get(file), b.files.get(file));
        };
        from.valueProperty().addListener((o,a,b)->update.run());
        to.valueProperty().addListener((o,a,b)->update.run());
        files.valueProperty().addListener((o,a,b)->update.run());
        update.run();
        VBox layout = new VBox(15,selectors,diffArea);
        layout.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(layout);
        dialog.showAndWait();
    }
    private ListCell<Revision> revisionCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Revision item,boolean empty) {
                super.updateItem(item,empty);
                setText(empty || item==null ? null : "V" + item.id + "  " + item.message);
            }
        };
    }
    private void cloudSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();dialog.setTitle("Cloud backup settings");
        styleDialog(dialog);
        TextField owner = new TextField(cloudOwner==null?"":cloudOwner);
        TextField repository = new TextField(cloudRepo==null?"":cloudRepo);
        PasswordField token = new PasswordField();
        if(cloudToken!=null)token.setText(cloudToken);
        owner.setPromptText("GitHub username");repository.setPromptText("Repository name");
        token.setPromptText("Fine-grained GitHub token");
        Label notice = label("Use a PRIVATE repository with Contents: Read and write.\nThe token stays in memory and is never saved to disk.","muted-small");
        VBox form = new VBox(10,label("OWNER","eyebrow"),owner,
                label("REPOSITORY","eyebrow"),repository,label("ACCESS TOKEN","eyebrow"),token,notice);
        form.setPadding(new Insets(16));form.setPrefWidth(430);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK) {
            cloudOwner=owner.getText().trim();cloudRepo=repository.getText().trim();
            cloudToken=token.getText().trim();
            setStatus("Cloud account configured (not yet backed up)");
        }
    }
    private boolean cloudReady() {
        if (cloudOwner==null || cloudRepo==null || cloudToken==null ||
                cloudOwner.isBlank() || cloudRepo.isBlank() || cloudToken.isBlank()) {
            cloudSettings();
        }
        return cloudOwner!=null && cloudRepo!=null && cloudToken!=null &&
                !cloudOwner.isBlank() && !cloudRepo.isBlank() && !cloudToken.isBlank();
    }
    private void uploadCloud() {
        if(!cloudReady())return;
        CloudTarget target = new CloudTarget(cloudOwner, cloudRepo, cloudToken);
        byte[] snapshot;
        try { persist(); snapshot=repo.toBytes(); }
        catch(Exception e){error(e);return;}
        setStatus("Uploading backup…");
        CompletableFuture.runAsync(() -> {
            try {
                cloud.upload(target.owner(), target.repository(), target.token(), snapshot);
                Platform.runLater(() -> info("Cloud backup succeeded",
                    "A copy of your snapshot is now stored in " + target.owner() + "/" + target.repository()
                    + " at " + GitHubBackup.backupPath()));
                Platform.runLater(() -> setStatus("✓ GitHub cloud backup uploaded"));
            } catch(Exception e) {Platform.runLater(() -> error(e));}
        });
    }
    private void downloadCloud() {
        if(!cloudReady())return;
        if(!confirm("Replace current workspace from cloud?",
                "This operation replaces the local workspace and history. Export your current files or upload a backup first."))return;
        CloudTarget target = new CloudTarget(cloudOwner, cloudRepo, cloudToken);
        setStatus("Downloading backup…");
        CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes=cloud.download(target.owner(), target.repository(), target.token());
                VersionRepository recovered=VersionRepository.fromBytes(bytes);
                Platform.runLater(() -> execute(() -> {
                    repo=recovered;selectedFile=null;refreshAll();setStatus("✓ Cloud workspace restored");
                }));
            } catch(Exception e) {Platform.runLater(() -> error(e));}
        });
    }

    @FunctionalInterface private interface Task {void run() throws Exception;}
    private void execute(Task action) {
        try { action.run(); saveDelay.stop(); persist(); }
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
