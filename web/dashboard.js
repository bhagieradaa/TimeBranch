"use strict";

const header = "src/components/Header.tsx";
const toolbar = "src/components/Toolbar.tsx";
const styles = "src/styles/dashboard.css";
const feature = "feature/brutalist-ui";
const initialFiles = {
  [header]: [
    'import { BranchSelector } from "./BranchSelector";',
    "",
    "export function Header({ branchName }) {",
    "  return (",
    '    <header className="header">',
    '      <div className="brand">',
    "        <h1>TimeBranch</h1>",
    '        <span className="sub">VCS Dashboard</span>',
    "      </div>",
    '      <div className="actions">',
    "        <BranchSelector value={branchName} />",
    "      </div>",
    "    </header>",
    "  );",
    "}",
  ].join("\n"),
  "src/components/BranchSelector.tsx": "export function BranchSelector({ value }) {\n  return <strong>{value}</strong>;\n}",
  "src/components/DiffViewer.tsx": "export function DiffViewer({ lines }) {\n  return <pre>{lines.join(String.fromCharCode(10))}</pre>;\n}",
  "src/components/Sidebar.tsx": "export const Sidebar = ({ children }) => <aside>{children}</aside>;",
  [toolbar]: 'export const Toolbar = () => <button>Save version</button>;',
  [styles]: ".header { padding: 16px; }",
  "tests/Header.test.tsx": 'test("renders branch name", () => expect("main").toBeTruthy());',
  "docs/workflow.md": "# Workflow\nChoose a branch, review changes, then commit.",
  "README.md": "# TimeBranch\nA local VCS workspace.",
  "package.json": '{ "name": "timebranch-web", "private": true }',
  ".gitignore": "node_modules/\ndist/",
};

function revision(id, message, date, files, author = "alex.dev") {
  return { id, message, date, files, author };
}

const first = revision("c1b0e7d", "chore: initial dashboard scaffolding", "2025-01-26T11:34:00Z",
  { "README.md": initialFiles["README.md"], "package.json": initialFiles["package.json"], ".gitignore": initialFiles[".gitignore"] });
const second = revision("4f2d9ac", "feat(vcs): add action bar and status summary", "2025-01-27T18:09:00Z", initialFiles);
const third = revision("8c9e1b4", "refactor(diff): simplify line rendering", "2025-01-28T13:51:00Z", {
  ...second.files, "src/components/DiffViewer.tsx": "export const DiffViewer = ({ children }) => <pre>{children}</pre>;",
});
const fourth = revision("a7f3d2e", "feat(ui): add branch context to header", "2025-01-28T14:27:00Z", {
  ...third.files, [header]: third.files[header].replace("VCS Dashboard</span>", "{branchName}</span>"),
});
const seed = {
  branch: feature,
  branches: {
    main: { history: [second, first], files: second.files },
    [feature]: {
      history: [fourth, third, second, first],
      files: {
        ...fourth.files,
        [header]: fourth.files[header].replace(
          '      <div className="brand">\n        <h1>TimeBranch</h1>\n        <span className="sub">{branchName}</span>\n      </div>',
          [
            "      <h1>TIMEBRANCH</h1>",
            '      <div className="meta">',
            '        <span className="branch-label">BRANCH</span>',
            '        <span className="branch">{branchName}</span>',
            "      </div>",
          ].join("\n")
        ),
        [toolbar]: 'export const Toolbar = () => (\n  <div className="actions">\n    <button>Commit</button>\n    <button>Push</button>\n    <button>Revert</button>\n  </div>\n);',
        [styles]: ".header {\n  padding: 16px;\n  border-bottom: 2px solid #111;\n}",
      },
    },
  },
};

const workspaceKey = "timebranch-dashboard-v1";
function readLocal(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}
function writeLocal(key, value) {
  try { localStorage.setItem(key, value); } catch { /* Session state remains available. */ }
}

let workspace;
try { workspace = JSON.parse(readLocal(workspaceKey)) || structuredClone(seed); }
catch { workspace = structuredClone(seed); }

const get = (id) => document.getElementById(id);
const branchSelect = get("branch");
const tree = get("tree");
const filter = get("filter");
const dialog = get("dialog");
const message = get("message");
const openFolders = new Set(["src", "src/components", "tests"]);
let selectedFile = header;
let selectedRevision = null;
let pendingAction;

function element(tag, value, className) {
  const node = document.createElement(tag);
  if (value !== undefined) node.textContent = value;
  if (className) node.className = className;
  return node;
}

function changedPaths(before, after) {
  return [...new Set([...Object.keys(before), ...Object.keys(after)])]
    .filter((path) => before[path] !== after[path]);
}

function diffLines(before, after) {
  const oldLines = before?.split("\n") ?? [];
  const newLines = after?.split("\n") ?? [];
  let start = 0;
  let oldEnd = oldLines.length;
  let newEnd = newLines.length;
  while (start < oldEnd && start < newEnd && oldLines[start] === newLines[start]) start++;
  while (oldEnd > start && newEnd > start && oldLines[oldEnd - 1] === newLines[newEnd - 1]) {
    oldEnd--;
    newEnd--;
  }
  return [
    ...oldLines.slice(0, start).map((line) => [" ", line]),
    ...oldLines.slice(start, oldEnd).map((line) => ["-", line]),
    ...newLines.slice(start, newEnd).map((line) => ["+", line]),
    ...oldLines.slice(oldEnd).map((line) => [" ", line]),
  ];
}

function counts(lines) {
  return lines.reduce(([added, removed], [kind]) => [
    added + Number(kind === "+"), removed + Number(kind === "-"),
  ], [0, 0]);
}

function renderTree(paths) {
  const folders = new Map([["", tree]]);
  tree.replaceChildren();
  paths.sort((left, right) => {
    const leftParts = left.split("/");
    const rightParts = right.split("/");
    const shared = leftParts.findIndex((part, index) => part !== rightParts[index]);
    if (shared < 0) return leftParts.length - rightParts.length;
    const leftFolder = shared < leftParts.length - 1;
    const rightFolder = shared < rightParts.length - 1;
    return Number(rightFolder) - Number(leftFolder) || leftParts[shared].localeCompare(rightParts[shared]);
  });
  for (const path of paths) {
    const parts = path.split("/");
    const name = parts.pop();
    let parent = "";
    for (const part of parts) {
      const directory = parent ? `${parent}/${part}` : part;
      if (!folders.has(directory)) {
        const disclosure = element("details");
        const content = element("div");
        disclosure.open = openFolders.has(directory);
        disclosure.addEventListener("toggle", () => {
          if (disclosure.open) openFolders.add(directory);
          else openFolders.delete(directory);
        });
        disclosure.append(element("summary", `${part}/`), content);
        folders.get(parent).append(disclosure);
        folders.set(directory, content);
      }
      parent = directory;
    }

    const button = element("button", undefined, "tree-file");
    button.type = "button";
    button.title = path;
    button.dataset.path = path;
    button.setAttribute("aria-current", String(path === selectedFile));
    const icon = element("span", "▤");
    icon.setAttribute("aria-hidden", "true");
    button.append(icon, document.createTextNode(name));
    button.onclick = () => {
      selectedFile = path;
      render();
      [...tree.querySelectorAll("button")].find((item) => item.dataset.path === path)?.focus();
    };
    folders.get(parent).append(button);
  }
}

function renderDiff(before, after) {
  const lines = diffLines(before, after);
  const [added, removed] = counts(lines);
  const stats = get("file-stats");
  stats.replaceChildren();
  if (added || removed || before !== after) {
    const kind = before === undefined ? "ADDED" : after === undefined ? "REMOVED" : "MODIFIED";
    stats.append(element("span", kind, kind === "ADDED" ? "plus" : "stamp"),
      element("span", `+${added}`, "plus"), element("span", `−${removed}`, "minus"));
  }

  const table = get("diff");
  table.replaceChildren();
  const hunk = table.insertRow();
  hunk.className = "hunk";
  const heading = hunk.insertCell();
  heading.colSpan = 4;
  heading.textContent = before === after ? (before === undefined ? "File unavailable" : "No changes to this file")
    : `@@ -${before === undefined ? 0 : 1},${before?.split("\n").length ?? 0} +${after === undefined ? 0 : 1},${after?.split("\n").length ?? 0} @@`;
  let oldNumber = 0;
  let newNumber = 0;
  for (const [kind, line] of lines) {
    const row = table.insertRow();
    row.className = kind === "+" ? "added" : kind === "-" ? "removed" : "";
    row.append(element("td", kind === "+" ? "" : ++oldNumber, "line-no"),
      element("td", kind === "-" ? "" : ++newNumber, "line-no"),
      element("td", kind, "sign"), element("td", line));
  }
}

function renderHistory(history) {
  const historyBody = get("history");
  historyBody.replaceChildren();
  for (const [index, item] of history.entries()) {
    const previous = history[index + 1]?.files ?? {};
    if (filter.value === "file" && previous[selectedFile] === item.files[selectedFile]) continue;

    const row = historyBody.insertRow();
    row.classList.toggle("selected", item.id === selectedRevision);
    const button = element("button", item.id);
    button.type = "button";
    button.setAttribute("aria-label", `Show commit ${item.id}: ${item.message}`);
    button.setAttribute("aria-pressed", String(item.id === selectedRevision));
    button.onclick = () => {
      selectedRevision = selectedRevision === item.id ? null : item.id;
      render();
      [...historyBody.querySelectorAll("button")].find((entry) => entry.textContent === item.id)?.focus();
    };
    row.insertCell().append(button);
    row.append(element("td", item.message), element("td", item.author),
      element("td", item.date.slice(0, 16).replace("T", " ")));
  }
  if (!historyBody.rows.length) {
    const cell = historyBody.insertRow().insertCell();
    cell.colSpan = 4;
    cell.textContent = "No commits match this file.";
  }
}

function render() {
  const branch = workspace.branches[workspace.branch];
  const head = branch.history[0];
  const changes = changedPaths(head.files, branch.files);
  const [added, removed] = counts(changes.flatMap((path) => diffLines(head.files[path], branch.files[path])));
  get("active-branch").textContent = workspace.branch;
  get("head").textContent = head.id;
  get("status").textContent = changes.length ? "■ WORKING TREE DIRTY" : "□ WORKING TREE CLEAN";
  get("changed").textContent = changes.length;
  get("totals").textContent = `+ ${added} additions\n− ${removed} deletions\n± ${changes.length} files changed`;
  get("history-branch").textContent = `( ${workspace.branch} )`;
  get("commit").disabled = !changes.length;
  get("revert").disabled = !changes.length;

  const index = branch.history.findIndex((item) => item.id === selectedRevision);
  const commit = branch.history[index];
  const before = commit ? (branch.history[index + 1]?.files ?? {}) : head.files;
  const after = commit ? commit.files : branch.files;
  const paths = [...new Set([...Object.keys(before), ...Object.keys(after)])];
  if (!paths.includes(selectedFile)) selectedFile = changedPaths(before, after)[0] ?? paths[0];
  renderTree(paths);
  get("meta").textContent = commit
    ? `COMMIT  ${commit.id}  |  AUTHOR  ${commit.author}  |  DATE  ${commit.date}`
    : `WORKING TREE  |  BASE  ${head.id}`;
  get("path").textContent = selectedFile ?? "No files";
  renderDiff(before[selectedFile], after[selectedFile]);
  renderHistory(branch.history);
}

function save() {
  writeLocal(workspaceKey, JSON.stringify(workspace));
  render();
}

function notify(text) {
  get("notice").textContent = text;
}

function confirmAction(title, description, requireMessage, action) {
  pendingAction = action;
  get("dialog-title").textContent = title;
  get("dialog-description").textContent = description;
  get("message-label").hidden = !requireMessage;
  message.required = requireMessage;
  message.setCustomValidity("");
  message.value = "";
  get("confirm").textContent = title;
  get("confirm").classList.toggle("danger", !requireMessage);
  dialog.returnValue = "";
  dialog.showModal();
  (requireMessage ? message : dialog.querySelector('[value="cancel"]')).focus();
}

message.oninput = () => message.setCustomValidity("");
dialog.querySelector("form").onsubmit = (event) => {
  if (event.submitter?.value === "cancel" || !message.required || message.value.trim()) return;
  event.preventDefault();
  message.setCustomValidity("Enter a commit message.");
  message.reportValidity();
};
dialog.onclose = () => {
  if (dialog.returnValue === "confirm") pendingAction(message.value.trim());
  pendingAction = undefined;
};

get("commit").onclick = () => confirmAction("Commit", `Save working tree changes to ${workspace.branch}.`, true, (text) => {
  const branch = workspace.branches[workspace.branch];
  const random = crypto.getRandomValues(new Uint32Array(1))[0];
  const id = random.toString(16).padStart(8, "0").slice(0, 7);
  branch.history.unshift(revision(id, text, new Date().toISOString(), { ...branch.files }, "local.dev"));
  selectedRevision = id;
  save();
  notify(`Committed ${id} to ${workspace.branch}.`);
});

get("revert").onclick = () => confirmAction("Revert", `Discard all uncommitted changes on ${workspace.branch}?`, false, () => {
  const branch = workspace.branches[workspace.branch];
  branch.files = { ...branch.history[0].files };
  selectedRevision = null;
  save();
  notify(`Restored the working tree to ${branch.history[0].id}.`);
});

get("push").onclick = () => {
  const { history } = workspace.branches[workspace.branch];
  const blob = new Blob([JSON.stringify({ branch: workspace.branch, history }, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = element("a");
  link.href = url;
  link.download = `timebranch-${workspace.branch.replaceAll("/", "-")}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  notify(`Downloaded committed snapshots for ${workspace.branch}.`);
};

for (const name of Object.keys(workspace.branches)) branchSelect.add(new Option(name, name));
branchSelect.value = workspace.branch;
branchSelect.onchange = () => {
  workspace.branch = branchSelect.value;
  selectedRevision = null;
  save();
  notify(`Switched to ${workspace.branch}.`);
};
filter.onchange = () => renderHistory(workspace.branches[workspace.branch].history);

const theme = get("theme");
const systemTheme = matchMedia("(prefers-color-scheme: dark)");
function applyTheme(dark) {
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  theme.textContent = dark ? "Light mode" : "Dark mode";
  theme.setAttribute("aria-pressed", String(dark));
}
applyTheme((readLocal("timebranch-theme") ?? (systemTheme.matches ? "dark" : "light")) === "dark");
systemTheme.addEventListener("change", (event) => {
  if (!readLocal("timebranch-theme")) applyTheme(event.matches);
});
theme.onclick = () => {
  const dark = document.documentElement.dataset.theme !== "dark";
  applyTheme(dark);
  writeLocal("timebranch-theme", dark ? "dark" : "light");
};

render();
