import { describe, it, expect } from "vitest";
import { validateInput, buildEntry, type ProposeInput } from "../src/propose.js";

const base: ProposeInput = {
  type: "gotcha",
  title: "Use JDK 21 for the build",
  body: "Default java is 17.",
  tags: ["build"],
};

describe("propose input validation", () => {
  it("accepts a well-formed input", () => {
    expect(validateInput(base)).toBeNull();
  });
  it("rejects empty title/body", () => {
    expect(validateInput({ ...base, title: "   " })).toMatch(/title/);
    expect(validateInput({ ...base, body: "" })).toMatch(/body/);
  });
  it("rejects multi-line titles (frontmatter safety)", () => {
    expect(validateInput({ ...base, title: "line one\nline two" })).toMatch(/single line/);
  });
  it("requires at least one non-empty tag", () => {
    expect(validateInput({ ...base, tags: [] })).toMatch(/tag/);
    expect(validateInput({ ...base, tags: ["", "  "] })).toMatch(/tag/);
  });
  it("rejects a title with no slug-safe characters", () => {
    expect(validateInput({ ...base, title: "!!!" })).toBeTruthy();
  });
});

describe("buildEntry", () => {
  it("JSON-quotes the YAML title and slugs the path", () => {
    const e = buildEntry({ ...base, title: 'Title: with "quotes"' }, "2026-06-16");
    expect(e.relPath).toBe("kb/gotchas/title-with-quotes.md");
    expect(e.fileContents).toContain('title: "Title: with \\"quotes\\""');
    expect(e.fileContents).toContain("created: 2026-06-16");
  });
  it("slugs tags into a quoted YAML array", () => {
    const e = buildEntry({ ...base, tags: ["Build", "JDK 21"] }, "2026-06-16");
    expect(e.fileContents).toContain('tags: ["build", "jdk-21"]');
    expect(e.newIndexEntry.tags).toEqual(["build", "jdk-21"]);
  });
});
