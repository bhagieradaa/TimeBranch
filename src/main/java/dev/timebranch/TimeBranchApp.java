package dev.timebranch;

import dev.timebranch.cloud.GitHubBackup;
import dev.timebranch.core.DiffUtil;
import dev.timebranch.core.VersionRepository;
import dev.timebranch.core.VersionRepository.Revision;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
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
    private ListView<String> fileList;
    private ListView<Revision> historyList;
    private ComboBox<String> branches;
    private TextArea editor;
    private Label title, status, statusDot, branchLabel, changesLabel, fileCounter, historyCounter;
    private String selectedFile;
    private boolean updating;
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(750));
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private final GitHubBackup cloud = new GitHubBackup();
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
        root.setTop(buildHeader());
        root.setCenter(buildWorkspace());
        root.setBottom(buildStatus());
        Scene scene = new Scene(root, 1470, 920);
        var resource = getClass().getResource("/styles/timebranch.css");
        if (resource != null) scene.getStylesheets().add(resource.toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(1040);
        stage.setMinHeight(690);
        saveDelay.setOnFinished(event -> persist());
        refreshAll();
        stage.setOnCloseRequest(event -> { saveDelay.stop(); persist(); });
        stage.show();
        Platform.runLater(editor::requestFocus);
    }

    private Node buildHeader() {
        Label emblem = label("◈", "brand-icon");
        Label brand = label("timebranch", "brand");
        Label subtitle = label("WORKSPACE   /   LOCAL REPOSITORY", "eyebrow");
        VBox identity = new VBox(3, brand, subtitle);
        HBox brandGroup = new HBox(13, emblem, identity);
        brandGroup.setAlignment(Pos.CENTER_LEFT);
        Label offline = label("●  LOCAL FIRST", "local-pill");
        Button backup = button("☁  Cloud backup", "secondary-button", this::uploadCloud);
        Button restore = button("↧  Cloud restore", "ghost-button", this::downloadCloud);
        Button settings = button("⚙  Cloud settings", "ghost-button", this::cloudSettings);
        HBox left = new HBox(32, brandGroup, separator(), label("A better way to recover code", "header-caption"));
        left.setAlignment(Pos.CENTER_LEFT);
        HBox right = new HBox(11, offline, backup, restore, settings);
        right.setAlignment(Pos.CENTER_RIGHT);
        Region grow = new Region(); HBox.setHgrow(grow,Priority.ALWAYS);
        HBox bar = new HBox(12, left, grow, right);
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }
    private Node buildWorkspace() {
        HBox workspace = new HBox(0);
        workspace.getStyleClass().add("workspace");
        VBox left = buildExplorer();
        VBox center = buildEditor();
        VBox right = buildHistory();
        left.setPrefWidth(275); left.setMinWidth(220); left.setMaxWidth(340);
        center.setMinWidth(380);
        HBox.setHgrow(center,Priority.ALWAYS);
        right.setPrefWidth(335);right.setMinWidth(275);right.setMaxWidth(375);
        workspace.getChildren().addAll(left, center, right);
        return workspace;
    }
    private VBox buildExplorer() {
        Label top = label("EXPLORER", "eyebrow");
        Button add = smallButton("+",this::newFile);
        Button importFile = smallButton("⇧",this::importFile);
        HBox head = spaced(top,new HBox(8,add,importFile));
        head.getStyleClass().add("section-header");
        fileCounter = label("YOUR FILES", "eyebrow");
        fileList = new ListView<>();
        fileList.getStyleClass().add("file-list");
        fileList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(String item,boolean empty) {
                super.updateItem(item,empty);
                setText(empty || item == null ? null : iconFor(item) + "  " + item);
                getStyleClass().remove("file-cell");
                if (!empty) getStyleClass().add("file-cell");
            }
        });
        fileList.getSelectionModel().selectedItemProperty().addListener((o,oldName,newName) -> openFile(newName));
        VBox.setVgrow(fileList,Priority.ALWAYS);
        Button rename = button("Rename", "compact-button",this::renameFile);
        Button remove = button("Delete", "compact-button danger-button",this::deleteFile);
        HBox fileActions = new HBox(8,rename,remove);
        Button export = button("↗  Export selected file", "wide-ghost",this::exportFile);
        Label branchTitle = label("BRANCHES","eyebrow");
        Button newBranch = smallButton("+",this::createBranch);
        HBox branchHead = spaced(branchTitle,newBranch);
        branches = new ComboBox<>();
        branches.setMaxWidth(Double.MAX_VALUE);
        branches.valueProperty().addListener((o,a,b) -> {
            if (!updating && b != null && !b.equals(repo.branch())) switchBranch(b);
        });
        Label branchInfo = label("Keep experiments separate from main.","muted-small");
        VBox branchArea = new VBox(12,branchHead,branches,branchInfo);
        branchArea.getStyleClass().add("sidebar-section");
        VBox bottom = new VBox(8,
            label("RECOVERY PROTECTION","eyebrow"),
            label("↺  Snapshots are stored locally","muted-small"),
            label("☁  Cloud backups are optional","muted-small"));
        bottom.getStyleClass().add("sidebar-bottom");
        VBox left = new VBox(12,head,fileCounter,fileList,fileActions,export,new Separator(),branchArea,bottom);
        left.getStyleClass().add("explorer-panel");
        return left;
    }
    private VBox buildEditor() {
        title = label("Main.java", "editor-filename");
        branchLabel = label("main", "branch-chip");
        changesLabel = label("No unsaved edits","editor-context");
        HBox caption = new HBox(11,branchLabel,changesLabel);
        caption.setAlignment(Pos.CENTER_LEFT);
        VBox fileIdentity = new VBox(6,title,caption);
        Button undo = button("↶  Undo","outline-button",() -> {if (repo.undo()) refreshAll();});
        Button redo = button("↷  Redo","outline-button",() -> {if (repo.redo()) refreshAll();});
        Button compare = button("⇄  Compare","outline-button",this::compareVersions);
        Button save = button("+  Save version","primary-button",this::saveVersion);
        HBox actions = new HBox(8,undo,redo,compare,save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox top = spaced(fileIdentity,actions);
        top.getStyleClass().add("editor-header");
        Label codeLabel = label("CODE EDITOR","eyebrow");
        Label hint = label("⌘Z to undo  ·  ⌘S to save a version","muted-small");
        HBox captionBar = spaced(codeLabel,hint);captionBar.getStyleClass().add("code-caption");
        editor = new TextArea();
        editor.setWrapText(false);
        editor.setPromptText("Create or select a file to start editing...");
        editor.getStyleClass().add("code-editor");
        editor.textProperty().addListener((o,previous,current) -> {
            if (updating || selectedFile == null || !repo.files().containsKey(selectedFile)) return;
            try {
                repo.editFile(selectedFile,current);
                updateMeta();
                saveDelay.playFromStart();
            } catch (Exception e) { error(e); }
        });
        VBox.setVgrow(editor,Priority.ALWAYS);
        Label footerTip = label("TIP  •  Save a version before experimenting. Restore any earlier snapshot instantly.","hint-text");
        VBox footer = new VBox(footerTip);
        footer.getStyleClass().add("editor-footer");
        VBox center = new VBox(top,captionBar,editor,footer);
        center.getStyleClass().add("editor-panel");
        return center;
    }
    private VBox buildHistory() {
        Label head = label("VERSION HISTORY","eyebrow");
        historyCounter = label("Saved snapshots","muted-small");
        VBox heading = new VBox(7,head,historyCounter);
        Button compare = smallButton("⇄",this::compareVersions);
        HBox top = spaced(heading,compare);
        top.getStyleClass().add("history-header");
        historyList = new ListView<>();
        historyList.getStyleClass().add("history-list");
        historyList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Revision r,boolean empty) {
                super.updateItem(r,empty);
                if (empty || r == null) {
                    setText(null);setGraphic(null);return;
                }
                Label name = label("V" + r.id + "   " + r.message,"revision-name");
                name.setWrapText(true);
                Label date = label(formatter.format(Instant.ofEpochMilli(r.createdAt)) + "  ·  " + r.branch,"revision-date");
                Label id = label(r.id == repo.headId() ? "● HEAD":"◦ SNAPSHOT",
                        r.id == repo.headId() ? "head-pill":"revision-tag");
                VBox detail = new VBox(8,id,name,date);
                detail.getStyleClass().add("revision-card");
                setText(null);setGraphic(detail);
            }
        });
        historyList.setOnMouseClicked(e -> { if(e.getClickCount()==2) restoreSelected(); });
        VBox.setVgrow(historyList,Priority.ALWAYS);
        Button restore = button("↺  Restore selected","wide-button",this::restoreSelected);
        Button recover = button("♻  Recover deleted file","wide-ghost",this::recoverDeleted);
        Button compareSnapshots = button("⇄  Compare snapshots","wide-ghost",this::compareVersions);
        VBox recovery = new VBox(10,
            label("RECOVERY CENTER","eyebrow"),
            label("A deleted file isn't gone if it was saved in a snapshot.","muted-small"),
            recover,compareSnapshots);
        recovery.getStyleClass().add("recovery-card");
        VBox right = new VBox(top,historyList,restore,recovery);
        right.getStyleClass().add("history-panel");
        return right;
    }
    private Node buildStatus() {
        statusDot = label("●","status-online");
        status = label("Ready","status-text");
        fileCounter = label("0 files","status-text");
        Label mode = label("JAVA 17  •  LOCAL FIRST","status-text");
        HBox left = new HBox(9,statusDot,status);left.setAlignment(Pos.CENTER_LEFT);
        HBox right = new HBox(16,fileCounter,mode);right.setAlignment(Pos.CENTER_RIGHT);
        HBox footer = spaced(left,right);footer.getStyleClass().add("statusbar");
        return footer;
    }

    private void refreshAll() {
        updating = true;
        try {
            String wanted = selectedFile != null && repo.files().containsKey(selectedFile)
                    ? selectedFile : repo.files().keySet().stream().findFirst().orElse(null);
            fileList.setItems(FXCollections.observableArrayList(repo.files().keySet()));
            fileList.getSelectionModel().select(wanted);
            selectedFile = wanted;
            editor.setDisable(wanted == null);
            editor.setText(wanted == null ? "" : repo.content(wanted));
            branches.setItems(FXCollections.observableArrayList(repo.branches()));
            branches.setValue(repo.branch());
            historyList.setItems(FXCollections.observableArrayList(repo.revisions()));
            historyList.getSelectionModel().selectFirst();
            updateMeta();
        } finally { updating = false; }
    }
    private void updateMeta() {
        title.setText(selectedFile == null ? "No file selected" : selectedFile);
        branchLabel.setText("⑂  " + repo.branch());
        changesLabel.setText(repo.dirty() ? "●  Changes not versioned" : "✓  Latest snapshot saved");
        fileCounter.setText(repo.files().size() + " files");
        historyCounter.setText(repo.revisions().size() + " total snapshots · V" + repo.headId() + " latest");
    }
    private void openFile(String name) {
        if (updating) return;
        selectedFile = name;
        updating = true;
        try { editor.setDisable(name == null); editor.setText(name == null ? "" : repo.content(name)); }
        finally { updating = false; }
        updateMeta();
    }
    private void persist() {
        try { repo.save(STATE); setStatus("Saved to " + STATE.getFileName()); }
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
            repo.createFile(name);
            repo.editFile(name, Files.readString(file.toPath(), StandardCharsets.UTF_8));
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
                    repo.createBranch(name);
                    repo.checkout(name);
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
        Revision r = historyList.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select a snapshot first.");return; }
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
        TextArea diffArea = new TextArea();
        diffArea.setEditable(false);diffArea.setWrapText(false);
        diffArea.getStyleClass().add("diff-area");
        diffArea.setPrefSize(860,510);
        Runnable update = () -> {
            Revision a = from.getValue(),b = to.getValue();String file=files.getValue();
            if(a==null || b==null || file==null) return;
            StringBuilder output = new StringBuilder();
            output.append("--- V").append(a.id).append("/").append(file).append("\n");
            output.append("+++ V").append(b.id).append("/").append(file).append("\n\n");
            for (DiffUtil.Line line:DiffUtil.compare(a.files.getOrDefault(file,""),b.files.getOrDefault(file,"")))
                output.append(line.kind()).append(' ').append(line.text()).append('\n');
            diffArea.setText(output.toString());
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
        byte[] snapshot;
        try { persist(); snapshot=repo.toBytes(); }
        catch(Exception e){error(e);return;}
        setStatus("Uploading backup…");
        CompletableFuture.runAsync(() -> {
            try {
                cloud.upload(cloudOwner,cloudRepo,cloudToken,snapshot);
                Platform.runLater(() -> info("Cloud backup succeeded",
                    "A copy of your snapshot is now stored in " + cloudOwner + "/" + cloudRepo
                    + " at " + GitHubBackup.backupPath()));
                Platform.runLater(() -> setStatus("✓ GitHub cloud backup uploaded"));
            } catch(Exception e) {Platform.runLater(() -> error(e));}
        });
    }
    private void downloadCloud() {
        if(!cloudReady())return;
        if(!confirm("Replace current workspace from cloud?",
                "This operation replaces the local workspace and history. Export your current files or upload a backup first."))return;
        setStatus("Downloading backup…");
        CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes=cloud.download(cloudOwner,cloudRepo,cloudToken);
                VersionRepository recovered=VersionRepository.fromBytes(bytes);
                Platform.runLater(() -> execute(() -> {
                    repo=recovered;selectedFile=null;refreshAll();setStatus("✓ Cloud workspace restored");
                }));
            } catch(Exception e) {Platform.runLater(() -> error(e));}
        });
    }

    @FunctionalInterface private interface Task {void run() throws Exception;}
    private void execute(Task action) {
        saveDelay.stop();
        try { action.run(); persist(); }
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
        var css=getClass().getResource("/styles/timebranch.css");
        if(css!=null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("timebranch-dialog");
    }
    private static String iconFor(String file) {
        if(file.endsWith(".java"))return "☕";
        if(file.endsWith(".md"))return "▤";
        if(file.endsWith(".json"))return "{ }";
        return "◇";
    }
    private Label label(String text,String css) {Label l=new Label(text);l.getStyleClass().add(css);return l;}
    private Button button(String text,String css,Runnable onClick) {
        Button b=new Button(text);for(String style:css.split(" "))b.getStyleClass().add(style);
        b.setOnAction(e->onClick.run());return b;
    }
    private Button smallButton(String text,Runnable action) {return button(text,"icon-button",action);}
    private Node separator() {Separator sep=new Separator(Orientation.VERTICAL);return sep;}
    private HBox spaced(Node left,Node right) {
        Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);
        HBox h=new HBox(10,left,gap,right);h.setAlignment(Pos.CENTER_LEFT);return h;
    }
}
