# TimeBranch

**TimeBranch** is a Java 17 desktop application for learning version control and file recovery. It is **not Git itself**. Edit files, save immutable snapshots, branch, compare versions, restore earlier versions, and recover deleted source files. Optional GitHub cloud backup stores your complete workspace in a private repository.

## Requirements
- **JDK 17+**
- **Apache Maven 3.9+** (internet required on the first run to download JavaFX dependencies)

## Run on your Mac in Terminal

```bash
git clone https://github.com/bhagieradaa/TimeBranch.git
cd TimeBranch
mvn clean javafx:run
```

Your repository is private, so GitHub may require authentication when cloning. If Maven is missing and Homebrew is installed, run `brew install maven`. Verify with `java -version` and `mvn -version`.

## Features
- Use **+** in the Explorer to create files, **⇧** to import files.
- Edit in the JavaFX editor; workspace edits are saved locally.
- Click **Save version**, add a message, then save new versions as you change code.
- Click **Compare** and choose FROM, TO and file. Lines prefixed with **+** were added; lines prefixed with **-** were removed.
- Create branches with the **+** in the Branches area; switch from the dropdown.
- Select a snapshot and click **Restore selected**. Earlier history remains intact.
- Delete a file and use **Recover deleted file** to retrieve the latest snapshotted copy.
- Export source files to your computer for compilation.

TimeBranch edits and versions code; it **does not execute arbitrary Java source inside the editor**. After exporting `Main.java`, run `java Main.java` in Terminal.

## Optional GitHub cloud backup

1. Create a **separate private GitHub repository** for backups.
2. Generate a fine-grained GitHub personal access token restricted to that repository with **Contents: Read and write** permission.
3. Open **Cloud settings** and enter the backup repository owner, name and token.
4. Choose **Cloud backup** to upload a complete snapshot to `timebranch-backups/workspace.tbr`.
5. On another computer, enter the same settings and select **Cloud restore**. Confirm replacement of the local workspace.

Cloud backup is **manual**, not continuously synced; snapshots do not automatically upload. Only claim cloud recovery for versions actually uploaded before data loss. The token remains in application memory, not in a local file. Never share tokens or commit them. TimeBranch does not encrypt the cloud snapshot end-to-end. Use a private backup repository. GitHub history can retain earlier backups.

## Local data

Your local workspace is stored at `~/.timebranch/workspace.tbr`. Keep a copy when upgrading from old projects. This new app does not read the previous Swing `repository.dat` format.

## Data structures

| Structure | Use | Complexity |
|---|---|---|
| General revision tree | Branch history | O(1) amortized to append child |
| Custom chained hash table | Version lookup by ID | O(1) average, O(n) worst |
| Doubly linked list | Chronological versions | O(1) append, O(n) traversal |
| Linked stacks | Undo / redo | O(1) push/pop plus snapshot copy |
| Arrays and LCS | Compare source lines | O(nm) for files within threshold |

Immutable full-file snapshots copy content, so this is an educational VCS rather than optimized Git.

## Testing

```bash
mvn test
```

GitHub Actions builds and runs unit tests on Java 17 on each push. This initial release does **not** include GUI end-to-end tests or tests against live GitHub backup credentials.

## Project structure

```text
src/main/java/dev/timebranch/TimeBranchApp.java
src/main/java/dev/timebranch/core/VersionRepository.java
src/main/java/dev/timebranch/core/DiffUtil.java
src/main/java/dev/timebranch/cloud/GitHubBackup.java
src/main/resources/styles/timebranch.css
src/test/java/dev/timebranch/core/VersionRepositoryTest.java
.github/workflows/build.yml
pom.xml
```

Do not launch the old Swing JAR for this version. Use `mvn javafx:run`.
