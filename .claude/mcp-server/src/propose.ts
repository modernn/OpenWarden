/**
 * propose_kb_update: PR-gated KB writes.
 *
 * This does NOT write to the live kb/ on the working branch. It creates a fresh
 * branch, writes the new entry + updates kb/index.json, commits (signed + DCO),
 * pushes, and opens a PR labeled `kb-update` via the contributor's own `gh`.
 * The maintainer (CODEOWNERS on /kb/**) reviews it like any other change.
 */
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { runGh } from "./gh.js";
import { loadIndex, type KbIndexEntry } from "./kb.js";

export interface ProposeInput {
  type: "decision" | "gotcha" | "design-memory";
  title: string;
  body: string;
  tags: string[];
  supersedes?: string;
}

function git(repoRoot: string, args: string[]) {
  return spawnSync("git", args, {
    encoding: "utf8",
    cwd: repoRoot,
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0", SSH_ASKPASS_REQUIRE: "never" },
  });
}

function slugify(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
}

function subdirFor(type: ProposeInput["type"]): string {
  if (type === "decision") return "decisions";
  if (type === "gotcha") return "gotchas";
  return "design-memory";
}

export interface ProposeResult {
  ok: boolean;
  message: string;
  branch?: string;
  prUrl?: string;
}

/**
 * Returns the would-be entry + index mutation without touching git. Exposed so
 * tests can validate the generated file shape deterministically.
 */
export function buildEntry(
  repoRoot: string,
  input: ProposeInput,
  today: string
): { id: string; relPath: string; fileContents: string; newIndexEntry: KbIndexEntry } {
  const id = slugify(input.title);
  const relPath = `kb/${subdirFor(input.type)}/${id}.md`;
  const tagsYaml = `[${input.tags.map((t) => slugify(t)).join(", ")}]`;
  const supersedesLine = input.supersedes ? `supersedes: ${slugify(input.supersedes)}\n` : "";
  const fileContents =
    `---\n` +
    `id: ${id}\n` +
    `title: ${input.title}\n` +
    `type: ${input.type}\n` +
    `tags: ${tagsYaml}\n` +
    `status: active\n` +
    supersedesLine +
    `created: ${today}\n` +
    `updated: ${today}\n` +
    `source_pr: null\n` +
    `---\n\n` +
    `${input.body.trim()}\n`;
  const newIndexEntry: KbIndexEntry = {
    id,
    path: relPath,
    title: input.title,
    type: input.type,
    tags: input.tags.map((t) => slugify(t)),
    status: "active",
  };
  return { id, relPath, fileContents, newIndexEntry };
}

export function proposeKbUpdate(repoRoot: string, input: ProposeInput): ProposeResult {
  if (!input.title?.trim() || !input.body?.trim()) {
    return { ok: false, message: "title and body are required" };
  }
  const today = new Date().toISOString().slice(0, 10);
  const { id, relPath, fileContents, newIndexEntry } = buildEntry(repoRoot, input, today);

  // Build the new index (mark superseded if requested).
  const idx = loadIndex(repoRoot);
  if (idx.entries.some((e) => e.id === id)) {
    return { ok: false, message: `an entry with id "${id}" already exists; pick a distinct title` };
  }
  if (input.supersedes) {
    const target = idx.entries.find((e) => e.id === slugify(input.supersedes!));
    if (target) target.status = "superseded";
  }
  idx.entries.push(newIndexEntry);
  idx.generated = today;

  // F1: do not disturb the contributor's working state. Require a clean tree, and
  // restore the original branch on the way out — never strand them on kb-update/*.
  const dirty = (git(repoRoot, ["status", "--porcelain"]).stdout || "").trim();
  if (dirty) {
    return {
      ok: false,
      message:
        "working tree has uncommitted changes; commit or stash them before proposing a KB " +
        "update (propose_kb_update must not disturb your in-progress work).",
    };
  }
  const original = (git(repoRoot, ["rev-parse", "--abbrev-ref", "HEAD"]).stdout || "").trim();

  const branch = `kb-update/${id}`;
  const co = git(repoRoot, ["checkout", "-b", branch]);
  if (co.status !== 0) {
    return { ok: false, message: `could not create branch ${branch}: ${co.stderr}` };
  }

  // Write entry file.
  const absPath = join(repoRoot, relPath);
  if (existsSync(absPath)) {
    return { ok: false, message: `file already exists: ${relPath}` };
  }
  mkdirSync(dirname(absPath), { recursive: true });
  writeFileSync(absPath, fileContents, "utf8");

  // Update index.
  writeFileSync(join(repoRoot, "kb", "index.json"), JSON.stringify(idx, null, 2) + "\n", "utf8");

  // Commit signed + DCO.
  git(repoRoot, ["add", relPath, "kb/index.json"]);
  const commit = git(repoRoot, [
    "commit",
    "-S",
    "-s",
    "-m",
    `docs(kb): propose ${input.type} "${input.title}"\n\nKB-update proposed via MCP propose_kb_update. PR-gated; not written to main.\n\nCo-Authored-By: Claude <noreply@anthropic.com>`,
  ]);
  if (commit.status !== 0) {
    return { ok: false, message: `commit failed (signing/DCO?): ${commit.stderr}`, branch };
  }

  // After a successful commit the tree is clean, so returning to the original branch
  // is safe. restore() guarantees we never leave the contributor on kb-update/*.
  const restore = () => {
    if (original && original !== branch) git(repoRoot, ["checkout", original]);
  };

  // Push + open PR via the contributor's own gh.
  const push = git(repoRoot, ["push", "-u", "origin", branch]);
  if (push.status !== 0) {
    restore();
    return {
      ok: false,
      message:
        `committed to ${branch} but push failed: ${push.stderr.trim()}. ` +
        `Push it manually and open a PR labeled kb-update (your branch was restored).`,
      branch,
    };
  }
  const pr = runGh(
    [
      "pr",
      "create",
      "--label",
      "kb-update",
      "--title",
      `kb: ${input.title}`,
      "--body",
      `Proposed ${input.type} KB entry via MCP \`propose_kb_update\`.\n\n` +
        `- entry: \`${relPath}\`\n- index updated\n\n` +
        `Reviewed under CODEOWNERS (/kb/** → @modernn) and scanned by kb-content-gate.`,
    ],
    repoRoot
  );
  restore();
  if (!pr.ok) {
    return { ok: false, message: `branch pushed but PR open failed: ${pr.stderr}`, branch };
  }
  return {
    ok: true,
    message: "opened kb-update PR (restored your original branch)",
    branch,
    prUrl: pr.stdout.trim(),
  };
}
