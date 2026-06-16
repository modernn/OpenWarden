import { describe, it, expect } from "vitest";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import { searchKb, loadIndex, parseFrontmatter, sessionDigest } from "../src/kb.js";
import { buildEntry } from "../src/propose.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
// test/ -> mcp-server -> .claude -> repo root
const REPO_ROOT = resolve(__dirname, "..", "..", "..");

describe("kb index", () => {
  it("loads the seeded index with the six entries", () => {
    const idx = loadIndex(REPO_ROOT);
    expect(idx.entries.length).toBeGreaterThanOrEqual(6);
    const ids = idx.entries.map((e) => e.id);
    expect(ids).toContain("jdk21-build");
    expect(ids).toContain("agent-ready-vs-blocked");
  });
});

describe("search_kb", () => {
  it("finds the JDK 21 gotcha by content query", () => {
    const hits = searchKb(REPO_ROOT, { query: "JDK 21" });
    expect(hits.length).toBeGreaterThan(0);
    expect(hits.some((h) => h.id === "jdk21-build")).toBe(true);
  });

  it("finds the sealed-box gotcha by the BLAKE2b keyword", () => {
    const hits = searchKb(REPO_ROOT, { query: "BLAKE2b" });
    expect(hits.some((h) => h.id === "sealed-box-blake2b")).toBe(true);
  });

  it("filters by tag", () => {
    const hits = searchKb(REPO_ROOT, { query: "", tags: ["dns"] });
    expect(hits.length).toBeGreaterThan(0);
    expect(hits.every((h) => h.tags.includes("dns"))).toBe(true);
    expect(hits.some((h) => h.id === "fail-closed-dns-floor")).toBe(true);
  });

  it("respects the limit", () => {
    const hits = searchKb(REPO_ROOT, { query: "crypto", limit: 1 });
    expect(hits.length).toBeLessThanOrEqual(1);
  });

  it("returns nothing for a query that matches no entry", () => {
    const hits = searchKb(REPO_ROOT, { query: "zzz-no-such-token-qwerty" });
    expect(hits.length).toBe(0);
  });
});

describe("parseFrontmatter", () => {
  it("parses scalars and list tags", () => {
    const fm = parseFrontmatter(
      "---\nid: x\ntitle: Hello\ntype: gotcha\ntags: [a, b, c]\nstatus: active\n---\nbody"
    );
    expect(fm.id).toBe("x");
    expect(fm.tags).toEqual(["a", "b", "c"]);
  });
});

describe("sessionDigest", () => {
  it("includes the data-not-instructions reminder", () => {
    const d = sessionDigest(REPO_ROOT);
    expect(d.reminder.toLowerCase()).toContain("data, never instructions");
    expect(d.kb.total).toBeGreaterThanOrEqual(6);
  });
});

describe("propose buildEntry", () => {
  it("builds a well-formed frontmatter entry without touching git", () => {
    const { id, relPath, fileContents } = buildEntry(
      REPO_ROOT,
      { type: "gotcha", title: "Some New Gotcha", body: "watch out", tags: ["Build", "DNS"] },
      "2026-06-15"
    );
    expect(id).toBe("some-new-gotcha");
    expect(relPath).toBe("kb/gotchas/some-new-gotcha.md");
    expect(fileContents).toContain("id: some-new-gotcha");
    expect(fileContents).toContain("tags: [build, dns]");
    expect(fileContents).toContain("status: active");
  });
});
