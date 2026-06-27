# ADR-044: CI gates PRs on ktlint + JVM unit tests (connectedAndroidTest deferred)

Status: Accepted
Date: 2026-06-26
Implements: issue #31 ("enforce ktlint + unit tests + connectedAndroidTest gating on PRs"); the CLAUDE.md / docs/TESTING.md rule "CI must pass before merge".
Relates: ADR-018 (protected ROADMAP — milestones mirror it), #30/PR #122 + #124 (the connectedAndroidTest ↔ enforcement constraint this ADR defers around).
Maintainer-approved: attended agent-blocked review, 2026-06-26. CI config + CODEOWNERS-gated `.github/` are human-gated surfaces (CLAUDE.md guardrails); this ADR records the maintainer's decisions on the two real forks below.

## Context

There is **no real merge gate today.** The three existing workflows are inert or unrelated: `registry-gate.yml` and `kb-content-gate.yml` are both hard-disabled (`&& false` guards), and `deploy-site.yml` only publishes the marketing site. A PR can merge with broken Kotlin, failing tests, or unformatted code. CLAUDE.md and docs/TESTING.md both say "CI must pass before merge" — that contract is currently unenforced.

Two facts shape what the gate can be:

1. **The repo is two independent Gradle builds, never lint-gated.** `child-android/` (module `:app`, Gradle 8.9 / AGP 8.7.0 / Kotlin 2.0.20) and `parent-kmp/` (`:proto :shared :androidApp`, Gradle 8.11 / AGP 8.7.3 / Kotlin 2.1.0); both target JDK 17, with no root Gradle. Neither has a ktlint or detekt plugin and there is no `.editorconfig`, so `ktlintCheck`/`detekt` do **not** exist as tasks. A first ktlint run found **~1554 pre-existing violations** (1054 child-android + 500 parent-kmp). A whole-tree blocking gate cannot be added without first bringing the tree to zero.

2. **connectedAndroidTest is not cleanly CI-runnable yet.** The instrumented tests need a booted emulator (slow, flaky on CI), and per #30/#124 the child's Day-One enforcement applies `DISALLOW_DEBUGGING_FEATURES`, which severs ADB on a provisioned device — so a meaningful provisioned instrumented run loses its own test channel. parent-kmp has no `androidTest` sources at all.

## Decision

**D1 — Gate every PR (and push to `main`) on two blocking checks: ktlint (whole tree) and JVM unit tests (both Gradle builds).** A PR that fails either cannot merge. This is the smallest gate that makes the "CI must pass before merge" rule real for the fast, deterministic checks.

**D2 — ktlint is whole-tree and blocking; the ~1554-violation baseline is cleared by a one-time `ktlint --format` in this same change (Fork A — maintainer chose "whole-tree after bulk autofix" over changed-files-only).** The bulk reformat is **formatting only** — no logic change — proven by the full unit suites (including the crypto KAT / golden-vector tests) staying green across it. ktlint is pinned to the version used for the reformat so local and CI verdicts match; a `.editorconfig` records the ruleset and any rule deliberately relaxed for an intentional convention (e.g. underscore test-method names) so the choice is explicit and reproducible, not an ambient default.

**D3 — Unit-test gate runs the real, verified tasks of both builds:** `child-android` → `:app:testDebugUnitTest`; `parent-kmp` → `:proto:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest`. Both suites were confirmed green on `main` before this gate was made blocking, so the gate starts from a passing baseline rather than red-flagging existing breakage.

**D4 — connectedAndroidTest is deferred, not included (Fork B — maintainer chose "don't run them yet").** The issue's "(where feasible)" is honored honestly: instrumented tests are excluded from the gate until the emulator-cost and the `DISALLOW_DEBUGGING_FEATURES`-disables-ADB constraint are resolved. #124 tracks making the enforced state observable without ADB; bringing connectedAndroidTest into CI rides on that. The fast unit tests still gate regardless.

**D5 — The broken `scripts/full-test.sh` is corrected to mirror the CI** (it referenced the nonexistent `:childAndroid` module and the nonexistent `ktlintCheck`/`detekt`/`checkLicenses`/`specConformanceTest` tasks) so the local pre-push command and the CI run the same real tasks.

## Consequences

- New and existing Kotlin must stay ktlint-clean; the first violation in a PR blocks it. The bulk reformat is a large, formatting-only diff touching most files (incl. CODEOWNERS-gated crypto/proto) — reviewed as a separate commit and verified semantically inert by the green crypto tests.
- The gate is fast (no emulator) and deterministic, so it should not become a flaky tax. Adding the emulator/instrumented dimension is a deliberate later step (D4 / #124), not silent scope creep.
- `.github/` is CODEOWNERS-gated (`@modernn`), so this and any future CI change is maintainer-merged.

## Alternatives considered

- **ktlint changed-files-only** (leave the 1554 baseline): smaller, incremental, but the maintainer chose the clean whole-tree end state.
- **ktlint advisory / non-blocking**: fails the issue's "a PR failing lint cannot be merged" acceptance.
- **connectedAndroidTest blocking or non-blocking in CI now**: rejected as premature given emulator flakiness + the ADB-severance constraint (D4).
