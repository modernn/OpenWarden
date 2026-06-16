# AI-Automated Development Pipeline for OpenWarden

> **Audience:** OpenWarden contributors using Claude Code (and Codex as a second opinion) to write, test, and iterate on the KMP child DPC + parent app.
>
> **Status:** Working reference. Codifies how the maintainers actually drive the spec → code → test → fix → commit loop with AI assistance, given the constraints in [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md), [`TESTING.md`](TESTING.md), [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md), and [`PROVISIONING_V2.md`](PROVISIONING_V2.md).
>
> **Non-goal:** This is not "vibe-coding a security app." OpenWarden's threat model assumes a motivated adversary; the AI loop exists to accelerate scaffolding, refactors, and test churn, not to bypass human review on crypto, provisioning, or DPC code.

---

## 1. Claude Code capabilities for this stack

Claude Code is the primary driver. Its native tools map cleanly onto a KMP repo:

- **File ops:** `Read`, `Write`, `Edit`, `Grep`, `Glob`. Use `Glob` over `find`, `Grep` over `rg` — they are permission-aware. `Edit` operates on exact strings; for renames across the repo, prefer `Edit` with `replace_all` per file.
- **Bash:** Long-running commands (`./gradlew test`, `emulator -avd ...`) run via `Bash` with `run_in_background: true`. The `Monitor` tool then streams stdout lines as notifications, so Claude does not poll.
- **Parallel tool calls:** Independent reads/searches go in one assistant message as multiple `<function_calls>` entries. Cuts wall time roughly N-fold for spec-gathering passes.
- **Plan mode:** Used before any non-trivial edit. Forces Claude to write a plan, get user approval, then execute. Mandatory for any change touching `:proto`, `crypto/`, or `provisioning/`.
- **Hooks:** `PreToolUse`, `PostToolUse`, `Stop`, `SessionStart`. Configured in `.claude/settings.json`. Used for ktlint-on-save, blocking secret-leak `Write` calls, and gating turn-end on green tests (see §6, §10).
- **Skills:** Project-scoped reusable commands in `.claude/skills/<name>/SKILL.md`. The OpenWarden skill set is enumerated in §15.
- **Subagents:** `Explore` (broad codebase mapping), `Plan` (deep specification reading), and project-local `cavecrew-investigator` / `cavecrew-builder` / `cavecrew-reviewer` (token-compressed delegations for context preservation across long sessions). Subagent output is reinjected to main context as a summary, so a 40k-token investigation costs roughly 15k against the main context window.
- **Memory:** `~/.claude/projects/<repo-hash>/memory/` persists facts across sessions. Repo-scoped invariants live in `CLAUDE.md` at the repo root.

For a KMP project these matter because the build is slow (`./gradlew :shared:allTests` is ~90 s cold) and the emulator is slow (~3 min cold boot). Both belong on background tasks the moment they exist; the alternative is Claude burning context waiting for stdout.

---

## 2. Codex CLI capabilities

OpenAI's Codex CLI complements Claude. Install:

```bash
npm install -g @openai/codex
codex --version
export OPENAI_API_KEY="$(security find-generic-password -s openai_api_key -w)"
```

The Codex plugin is already wired into Claude Code via the `codex:rescue` and `codex:setup` skills. `codex:rescue` spawns Codex as a subagent with a structured prompt; output is filtered back into Claude's context.

**When Codex beats Claude on OpenWarden work:**

- **Numerical / algebraic refactors.** Constant-time crypto rewrites, reordering field accesses, JCS canonicalization edge cases. Codex's stricter type-following catches off-by-one in canonical byte output where Claude tends to "look right" without checking.
- **Second opinion on a single failing test.** Hand it the failing test + the offending file and ask "what's wrong"; you get a fresh diagnostic that doesn't share Claude's wrong mental model from earlier in the session.
- **Adversarial review of a security-critical diff.** `impeccable-codex-debate` runs both models in N rounds and emits a ranked findings report; see §18.

**When Claude wins:**

- **Long-context, multi-file work.** The 1M-token Opus context can hold all four spec docs + the `:shared` module + the relevant test corpus at once. Codex truncates earlier.
- **Spec-driven implementation.** Claude follows `PROTOCOL.md` and `PROVISIONING_V2.md` literally; Codex tends to invent shortcuts unless given strict negative constraints.
- **Tool orchestration.** Claude Code is a richer harness — parallel tools, hooks, skills, background tasks — and that's where the dev-loop velocity actually comes from.

Rule of thumb: Claude drives, Codex critiques.

---

## 3. Dual-LLM dev loop

```
+----------------+      +-------------------+      +----------------+
|  Claude Code   | ---> |  Tests run via    | ---> |  Green → PR    |
|  (primary)     |      |  full-test.sh     |      |  Red → iterate |
+----------------+      +-------------------+      +----------------+
        |                                                 |
        | stuck after 3 iterations                        |
        v                                                 v
+----------------+      contention on             +----------------+
| codex:rescue   |  <-- design / security  -----> | impeccable-    |
| (second opin.) |                                | codex-debate   |
+----------------+                                +----------------+
```

The loop:

1. Claude reads the relevant spec section.
2. Claude writes failing tests first (test-vector-driven where crypto is involved; see §11).
3. Claude implements until tests are green.
4. If three consecutive iterations fail to converge, Claude invokes `codex:rescue` with the failing test output + the offending file. Codex returns a diff suggestion; Claude evaluates and either applies or discards.
5. For high-stakes design choices (new protocol field, new recovery-flow surface, anything in `crypto/`), Claude invokes `impeccable-codex-debate` instead — runs both models adversarially for N rounds and produces a ranked findings report (§18).

The bounded-autonomy budget (§21) prevents infinite ping-pong between models.

---

## 4. OpenWarden-specific automation

Project commands live in `.claude/skills/`. The starter set:

- `/test-openwarden-unit` — `./gradlew :shared:allTests :proto:allTests --console=plain`. Fast feedback (≤30 s cold, ≤5 s warm). Default after every code change.
- `/test-openwarden-e2e-emulator` — Boots an AVD, runs `provisioning/provision.sh`, asserts `/health` is green, runs the smoke bundle from `TESTING.md` §6. Long-running (~8 min); always invoked with `run_in_background: true`.
- `/provision-openwarden-emulator` — Just the boot + provision steps, no smoke test. Used to leave a provisioned AVD running for interactive iteration.
- `/codex-second-opinion` — Wraps `codex:rescue` with a OpenWarden-flavored system prompt that references the protocol and spec docs.
- `/verify-openwarden-spec-compliance` — Spec-conformance pass: reads the listed spec section, compares against the current implementation, reports drift. Useful before opening a PR.

`.claude/settings.json` defines the allow-list of Bash commands Claude can run without prompting per call (`./gradlew`, `adb`, `emulator`, `git status`, `git diff`, `gh pr view`, etc.). Everything else still prompts. Write/Edit on `crypto/`, `proto/`, and `provisioning/` paths trigger an explicit confirmation hook.

---

## 5. Self-testing E2E

The `reactivecircus/android-emulator-runner` GitHub Action pattern is mirrored locally so Claude can run the same flow CI runs. One entry point:

```bash
./scripts/full-test.sh           # unit + integration + emulator E2E
./scripts/full-test.sh --fast    # unit only
./scripts/full-test.sh --e2e     # emulator only, skip unit
```

The script:

1. `./gradlew :shared:allTests :proto:allTests` (≤30 s).
2. Boots the `openwarden_pixel7` AVD headless with `-no-window -no-audio -no-boot-anim -no-snapshot -feature DeviceAdmin -gpu swiftshader_indirect`. Waits for `adb shell getprop sys.boot_completed = 1`.
3. Runs `provisioning/provision.sh` against the booted emulator.
4. Polls `content://com.openwarden.child.debug/health` until every field is green or the timeout fires (`PROVISIONING_V2.md` §3 layer 3).
5. Runs the JVM-hosted mock parent: pushes a signed test bundle over LAN, asserts `dumpsys package` shows the expected suspend state.
6. Tears down the AVD unconditionally in a `trap`, even on failure.
7. Exits 0 on success; exits 40-series on wrong-state, 50-series on device fault, 60-series on policy verify fail (matching `PROVISIONING_V2.md` §2).

Claude invokes `./scripts/full-test.sh --e2e` in the background and uses `Monitor` to stream lines. When the exit code arrives, Claude reads the structured tail and either iterates or PRs.

---

## 6. Hooks

`.claude/settings.json` wires:

- **`PreToolUse` on `Write`/`Edit`:** Reject writes to `*.pem`, `*.p12`, `.env*`, or any path containing `secret`/`mnemonic`/`seed`. Belt-and-suspenders against an LLM stuffing a private key into source.
- **`PreToolUse` on `Bash`:** Reject `rm -rf` outside `build/` and `node_modules/`. Reject `git push --force` to `main`. Reject `--no-verify` on commits.
- **`PostToolUse` on `Edit`/`Write` for `*.kt`:** Run `ktlint --format` on the changed file. Fail-fast on detekt errors (warnings are advisory).
- **`Stop` hook:** Before allowing the turn to end, run `./gradlew :shared:test --quiet`. If red, inject a system reminder back into Claude's context: "tests failed — fix before stopping." Combined with the `/verify` skill, this enforces the "you must have actually tested it" discipline (§10).
- **`SessionStart`:** Echo the repo's `CLAUDE.md` rules (no secrets, PR-gated, crypto-needs-human-review) into the system prompt.

Pre-commit (Husky-equivalent via `pre-commit` framework or a shelled `.git/hooks/pre-commit`) runs ktlint + detekt + `:shared:test`. Pre-push runs the emulator E2E with a 15-minute timeout on `release/*` branches only.

---

## 7. Tooling matrix

| Tool | Role | License | Cost |
|---|---|---|---|
| Claude Code | Primary agent | Anthropic proprietary | per-token API |
| Codex CLI | Second opinion | OpenAI proprietary | per-token API |
| Kotlin / KMP | Language | Apache 2.0 | free |
| Gradle + AGP | Build | Apache 2.0 | free |
| JUnit 5 + Kotest | Unit tests | EPL-2.0 / Apache 2.0 | free |
| MockK | Mocking | Apache 2.0 | free |
| Paparazzi | Compose snapshots | Apache 2.0 | free |
| XCTest + swift-snapshot-testing | iOS tests | Apple / MIT | free |
| SKIE | Kotlin → Swift bridge | Apache 2.0 | free |
| ionspin/kotlin-multiplatform-libsodium | Crypto | ISC | free |
| Jazzer | JVM fuzzing | Apache 2.0 | free |
| reactivecircus/android-emulator-runner | Emulator in CI | Apache 2.0 | free |
| ktlint + detekt | Lint | MIT / Apache 2.0 | free |
| SwiftLint | iOS lint | MIT | free |
| CycloneDX Gradle plugin | SBOM | Apache 2.0 | free |
| licensee | License audit | Apache 2.0 | free |
| Gitleaks | Secret scan | MIT | free |
| diffoscope | Reproducibility | GPL-3.0 (host-only) | free |
| GitHub Actions | CI | proprietary | free tier |
| gh CLI | CI orchestration | MIT | free |

The hard rule from `PARENT_KMP_STRUCTURE.md`: no GPL/AGPL in shipped artifacts. diffoscope runs host-side only; never linked into the build.

---

## 8. Multi-agent orchestration

Claude Code supports concurrent work within a single turn:

- **`TaskCreate` / TODO list.** Tracks the current spec → impl → test plan. Claude marks items in-progress as it executes; the list persists across turns until cleared.
- **Background tasks.** `./gradlew assembleDebug`, emulator boot, and `:e2eTest` all run with `run_in_background: true`. The agent does other useful work (writing the next test, updating docs) while waiting.
- **Single-message multi-tool.** Independent reads (four spec docs, `git status`, `git log`) issue in parallel from one assistant turn.
- **Subagents.** `Plan` for spec digestion; `Explore` for "where does this concept live in the repo"; `cavecrew-investigator` for caveman-compressed deep dives that don't pollute main context.
- **Memory.** Long-lived facts ("ionspin libsodium init must run before any crypto call" / "policy_seq must monotonically increase") live in `CLAUDE.md` so they reload every session.

The point of orchestration is to keep main context lean. A 200k-token investigation that produces a 5k-token summary preserves room for the actual code.

---

## 9. Reproducibility

`PARENT_KMP_STRUCTURE.md` and `TESTING.md` together require reproducible builds. The AI loop has to honor this or it will introduce nondeterminism that breaks the F-Droid pipeline.

- **`gradle/libs.versions.toml`** pins every Kotlin/AGP/KMP/Compose/SKIE/ktor/libsodium version. Claude must not bump a version without an ADR.
- **`.tool-versions`** (asdf) pins Java, Node, Python (for `provision.sh` wrapper), and the Android command-line tools. Mirrored by `.github/workflows/*.yml` runner steps.
- **AVD image hash.** `system-images;android-35;google_apis;x86_64` is pinned by its sdkmanager package hash. CI fails on hash mismatch.
- **GitHub Actions versions.** All actions referenced by full commit SHA, not floating tags. Dependabot proposes bumps; humans review.
- **LLM model versions.** Documented in `.claude/settings.json` (Claude model ID) and `.codex/config.toml` (Codex model ID). Pinned so a model update doesn't silently change behavior of an automated commit.

Reproducibility breaks are diagnosed with `diffoscope` against the byte-for-byte AAR/APK output of a clean build vs. a cached build. Claude is allowed to run diffoscope but not to "fix" reproducibility by adding `org.gradle.parallel=true` or any other shortcut.

---

## 10. The "remember to test" enforcement

LLMs lie about having tested. Two layers prevent this:

- **`Stop` hook.** Before the turn ends, the harness runs `./gradlew :shared:test --quiet` and, if red, injects a system reminder telling Claude tests failed and to either fix or explicitly hand off. Claude cannot silently claim done with red tests.
- **`/verify` skill.** Project-local skill that forces Claude to (a) re-run the relevant test target, (b) capture the JSON output, (c) cite the passing tests by name in its summary. If `/verify` is not invoked before a "done" claim, the reviewer rejects the PR.

The hook + skill combination is the operational equivalent of the `TESTING.md` §17 pre-release checklist — applied at every turn, not only at release.

---

## 11. Test-vector-driven crypto development

`PROTOCOL.md` §9 mandates `docs/test-vectors/`. Any change to `crypto/`, `:proto`, or canonicalization must:

1. Start by reading the existing test vectors and the failing-vector assertions.
2. Generate or extend vectors **before** writing implementation. Vectors come from a fixed BIP39 mnemonic (`parent-keys.json`), giving deterministic Ed25519 / X25519 keys.
3. Run `./gradlew :shared:cryptoVectorsTest`. Compare output byte-for-byte to the corpus.
4. On mismatch, Claude runs `./scripts/bisect-vectors.sh <vector-id>` — emits the offset of the first divergent byte and the input prefix. Claude reads only that slice, not the whole vector.

This pattern is the only one that prevents subtle JCS canonicalization regressions from passing functional tests but breaking cross-platform parity (the Kotlin → Swift assertion in `TESTING.md` §5).

---

## 12. CI integration

GitHub Actions plus the `gh` CLI lets Claude participate in CI without leaving the terminal.

- **Read test JSON.** `gh run view <id> --json jobs,conclusion` returns structured output Claude can parse.
- **Diagnose red.** Claude pulls the failing step's log via `gh run view --log-failed`, locates the assertion that failed, opens the relevant source file, and proposes a fix.
- **Iterate.** Push to a topic branch, wait for `gh run watch`, repeat. The whole inner loop happens without a human reviewing intermediate state.
- **PR gate.** When CI is green and `/verify` has been invoked, Claude opens a PR with `gh pr create`. Auto-merge is allowed only on non-`crypto/`, non-`provisioning/`, non-`:proto` paths (§14).

Claude never force-pushes to `main`. Claude never disables hooks. Both are enforced by the `PreToolUse` Bash hook (§6).

---

## 13. Practical OpenWarden setup

The monorepo is a single composite Gradle build:

- `parent-kmp/` is the root (`PARENT_KMP_STRUCTURE.md` §1).
- `child-android/` is `includeBuild`-composed so a change to `:proto` rebuilds both sides in one shot.
- `./gradlew test` — JVM unit tier.
- `./gradlew connectedDebugAndroidTest` — instrumented tests on the booted AVD.
- `./gradlew :e2eTest` — custom task aggregating the provisioning script + smoke bundle.
- `./scripts/full-test.sh` — the top-level entry point Claude actually invokes (§5).

The composite-build choice means a single `git status` covers everything Claude is editing, and a single test target rebuilds everything that the diff touches. This is load-bearing for the AI loop: split repos would force Claude to track cross-repo coherence manually.

---

## 14. Risk mitigation

Security-product realities:

- **No Claude push to `main`.** All Claude commits land on topic branches; merges go through `gh pr create` and human approval. Enforced by branch protection.
- **Crypto / provisioning / proto = human review required.** CODEOWNERS routes any change under `crypto/`, `provisioning/`, `:proto`, or `docs/test-vectors/` to a human reviewer regardless of CI status. The PR cannot auto-merge.
- **Signed commits.** Every commit (including AI-authored ones) is GPG-signed by the operator. The `Co-Authored-By: Claude` trailer is informational, not authorial — the human takes responsibility.
- **Containerized tests where possible.** Unit tests run in a `ubuntu-latest`-equivalent container locally (Devcontainer or `act`). Emulator tests need KVM and run on the bare host.
- **No secrets in the repo.** Gitleaks pre-commit + the `Write` hook (§6) prevent accidental exfiltration.
- **Cost cap.** Per-day API spend cap enforced via the Anthropic and OpenAI consoles. A runaway loop dies at the cap, not at the bank.

---

## 15. Concrete skills (`.claude/skills/`)

Each skill is a `SKILL.md` with frontmatter (trigger description, allowed tools) and a body explaining the procedure.

### `/test-openwarden-unit`

- **Triggers:** "run unit tests", "test the shared module", `/test-openwarden-unit`, "did my change break anything".
- **Invokes:** `./gradlew :shared:allTests :proto:allTests --console=plain`.
- **Output:** PASS/FAIL plus the names of any failing tests and the first 20 lines of each stack trace.

### `/test-openwarden-e2e-emulator`

- **Triggers:** "run the E2E", "verify provisioning end-to-end", `/test-openwarden-e2e-emulator`.
- **Invokes:** `./scripts/full-test.sh --e2e` with `run_in_background: true`.
- **Output:** Streams via `Monitor`. Final summary cites the `/health` snapshot from `PROVISIONING_V2.md` §9.

### `/provision-openwarden-emulator`

- **Triggers:** "boot a provisioned emulator", `/provision-openwarden-emulator`.
- **Invokes:** `./scripts/provision-emulator.sh` (boot + provision, no teardown).
- **Output:** Emulator serial + `/health` snapshot. Caller is responsible for `adb emu kill` when done.

### `/codex-second-opinion`

- **Triggers:** "ask Codex", "second opinion", "I'm stuck on this test", `/codex-second-opinion`.
- **Invokes:** `codex:rescue` skill with a OpenWarden-flavored prompt that includes the failing test + the relevant spec section.
- **Output:** Codex's proposed diff and rationale; Claude applies or discards explicitly.

### `/verify-openwarden-spec-compliance`

- **Triggers:** "check spec compliance", "does this match PROTOCOL.md", `/verify-openwarden-spec-compliance <spec-section>`.
- **Invokes:** Reads the spec section, walks the implementation, reports drift in a structured list.
- **Output:** Markdown list of `[ok|drift|missing]` per spec clause.

---

## 16. The provisioning + E2E pipeline

This is the most AI-amenable part of OpenWarden because the spec is already a state machine.

- **Spec:** `PROVISIONING_V2.md` §1 states S0 → S10 with explicit advance and rollback commands.
- **Test:** For each transition, an emulator-driven test asserts the post-condition (e.g., S5 → S6 asserts `dumpsys device_policy` shows the 17 expected `DISALLOW_*` restrictions; layer-3 of §3 asserts `/health` matches the expected fingerprint).
- **Implementation:** `provisioning/provision.sh` emits structured logs (one JSON object per state transition, including timestamps and exit code per §2).
- **Loop:** Claude calls `/test-openwarden-e2e-emulator`, reads the JSON tail, identifies the failing transition, opens the implicated source file (DPC receiver, content provider, or the script), proposes a fix, re-runs.

Because the spec is structured and the test output is structured, Claude rarely gets lost. The failure modes that actually trip the loop are not "Claude doesn't know what to do" but "the emulator AVD hash drifted and the test now fails for environmental reasons" — caught by the reproducibility pins in §9.

---

## 17. Codex CLI integration patterns

Two patterns are supported:

- **`codex:rescue` skill.** Claude invokes the skill with structured arguments; the skill spawns Codex with a system prompt aware of OpenWarden's invariants. Output is a diff suggestion. Used for stuck-on-one-test situations.
- **Direct shell.** `./scripts/codex-second-opinion.sh "<question>" --files <a,b,c>` shells Codex directly with the named files. Used when Claude is unsure which subagent path to take.

API key lives in the OS keychain (`security find-generic-password -s openai_api_key -w` on macOS, `secret-tool` on Linux, Credential Manager on Windows). Never in `.env`, never in the repo.

Codex output is treated as *advisory*. Claude evaluates the diff, runs the affected tests, and either applies the change or notes "Codex suggested X; rejected because Y" in the turn's reasoning trail.

---

## 18. `impeccable` + `impeccable-codex-debate`

- **`impeccable`** — Single-model UI/UX craft critique. Used on Compose screens and SwiftUI views before they ship. Catches text-only-on-kid-screen, hardcoded color, asymmetric-friction violations from `DESIGN_PARADIGMS.md` §1.
- **`impeccable-codex-debate`** — Two-model adversarial review. Runs Claude (frontend craft / impeccable lens) vs. Codex (second-opinion implementor) over a target artifact for N rounds (`--quick` 2, `--standard` 4, `--deep` 6). Emits a markdown report with consolidated findings, contested points, and a ranked fix list.

When to use the debate skill on OpenWarden:

- New protocol field (forces a real argument about wire compatibility, replay safety, canonical ordering).
- New recovery flow surface (the brick-risk profile from `PROVISIONING_V2.md` §6 is exactly the kind of trade-off two models will fight over).
- New crypto path or key-derivation tweak.
- High-stakes UX (the BIP39 recovery-phrase entry screen, the "ask dad" surface — both have outsized consequence).

For low-stakes changes (a new String resource, a snapshot baseline regen), debate is overkill; plain `/review` suffices.

---

## 19. Memory + skills system

Three persistent layers, in order of scope:

- **`~/.claude/projects/<repo-hash>/memory/`** — Per-user, per-repo facts. Survives across sessions; updated by the `claude-md-management:revise-claude-md` skill when the operator says "remember that…".
- **`.claude/skills/`** — Repo-scoped commands (the five in §15). Committed to the repo so every contributor's Claude Code session gets the same toolbox.
- **`CLAUDE.md`** — Repo-scoped rules every session loads. Currently encodes: no secrets in commits, all PRs gated, crypto/provisioning/proto edits require human review, ktlint runs on save, libsodium init order, `policy_seq` invariant, `PROTOCOL.md` is the contract.

The `claude-md-management:claude-md-improver` skill periodically audits `CLAUDE.md` against the templates and proposes targeted updates. Run it monthly.

---

## 20. Recommended workflow

The canonical spec → tests → impl → commit cycle:

1. **Read spec.** Claude opens `PROTOCOL.md` (or whichever doc) and the relevant existing implementation. Plan mode mandatory if touching `crypto/`, `:proto`, or `provisioning/`.
2. **Generate tests first.** New crypto: extend `docs/test-vectors/` with the expected output. New policy logic: write Kotest property tests. New UI: write the Paparazzi snapshot expectation.
3. **Confirm red.** Run `/test-openwarden-unit`. The new tests must fail with the exact assertion you expect — not a compile error, not a wiring error. This is the proof the test is actually testing the thing.
4. **Implement.** Smallest possible diff to turn red into green. No drive-by refactors.
5. **Confirm green.** Re-run `/test-openwarden-unit`. If touching provisioning or the DPC, also run `/test-openwarden-e2e-emulator`.
6. **`/verify`.** Forces the cite-passing-tests-by-name discipline.
7. **PR.** `gh pr create` with a body referencing the spec section satisfied.
8. **Review.** Auto-merge for non-critical paths once CI is green. Human review mandatory for crypto / provisioning / `:proto`.

---

## 21. Bounded autonomy

LLM loops are unbounded by default. OpenWarden caps them:

- **Max 3 consecutive failed iterations** on a single test before Claude must invoke `/codex-second-opinion` or hand off to a human. Tracked in the TODO list.
- **Infinite-loop detection.** If the same file is edited 5 times in a session with the same test still failing, Claude stops and asks for human direction.
- **Time budgets.** A "fix one test" task gets 30 min wall clock. A "implement state S6" task gets 2 hr. Tracked against the session start timestamp.
- **Cost tracking.** Per-task budget in USD, defaults: trivial 0.50, feature 5, debate 10. Enforced by the operator, not the model — but logged per session.

These caps are advisory in `CLAUDE.md` and enforced in the operator's head. The point is to surface "this is not converging" earlier than the API bill does.

---

## 22. Recommended starter setup for OpenWarden

The minimum viable AI dev pipeline:

```
parent-kmp/
├── .claude/
│   ├── settings.json          # allowed Bash, hooks (§6), model pins
│   └── skills/
│       ├── test-openwarden-unit/SKILL.md
│       ├── test-openwarden-e2e-emulator/SKILL.md
│       ├── provision-openwarden-emulator/SKILL.md
│       ├── codex-second-opinion/SKILL.md
│       └── verify-openwarden-spec-compliance/SKILL.md
├── scripts/
│   ├── full-test.sh           # §5 entry point
│   ├── provision-emulator.sh  # AVD boot + provision
│   ├── codex-second-opinion.sh
│   └── bisect-vectors.sh      # §11 byte-offset diff
├── CLAUDE.md                  # repo-scoped rules
└── ... (the rest from PARENT_KMP_STRUCTURE.md)
```

`CLAUDE.md` at minimum encodes:

- "Never commit secrets; the `Write` hook will block but verify with `git diff --staged` regardless."
- "Always run `/test-openwarden-unit` after editing `.kt`. Always run `/test-openwarden-e2e-emulator` after editing `provisioning/` or the DPC."
- "Crypto, `:proto`, and provisioning changes require human review."
- "`PROTOCOL.md` and `PROVISIONING_V2.md` are the contracts. Drift = bug."
- "ionspin libsodium must be initialized before any crypto call; tests rely on `@BeforeAll` doing so."
- "`policy_seq` is monotonically increasing per `PROTOCOL.md` §2.1; never decrement, never re-use."

---

## 23. References

- Claude Code documentation: <https://docs.claude.com/en/docs/claude-code> and <https://claude.ai/code>.
- OpenAI Codex CLI: <https://github.com/openai/codex>.
- Anthropic Agent SDK: <https://docs.claude.com/en/docs/agent-sdk>.
- `reactivecircus/android-emulator-runner`: <https://github.com/ReactiveCircus/android-emulator-runner>.
- `skill-creator` skill (built into Claude Code): create and audit project skills.
- `impeccable` and `impeccable-codex-debate` skills: shipped with the operator's Claude Code install.
- OpenWarden specs: [`PROTOCOL.md`](PROTOCOL.md), [`CRYPTO.md`](CRYPTO.md), [`PROVISIONING_V2.md`](PROVISIONING_V2.md), [`TESTING.md`](TESTING.md), [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md), [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md), [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md).
