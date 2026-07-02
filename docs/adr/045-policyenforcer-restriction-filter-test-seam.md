# ADR-045: PolicyEnforcer `restrictionFilter` test seam — keep `DISALLOW_DEBUGGING_FEATURES` always-on in release, omit it only in instrumented tests

Status: Accepted
Date: 2026-06-29
Relates: **ADR-020** (fail-closed Day-One restrictions — the baseline this seam configures; D1 "there is no relaxed variant in v1" stays true for *release*), **ADR-022** (deny-by-default profile-escape block, also in the required set), the existing `PolicyEnforcer` seam architecture (`isRestrictionSet` / `installedApps` / `isLaunchBlocked` / `alwaysExempt` / `lock`); issue #131; the DISALLOW_DEBUGGING_FEATURES research (`docs/research/09-disallow-debugging-and-cross-oem-provisioning.md`); `docs/E2E_EXIT_CRITERIA.md` (the adb-dark testing constraint); `scripts/e2e-exit-criteria.sh`; `child-android/app/src/androidTest/.../ExitCriteriaE2ETest.kt`.

## Context

`PolicyEnforcer.requiredRestrictions` (the ADR-020 Day-One baseline) includes `UserManager.DISALLOW_DEBUGGING_FEATURES` (`PolicyEnforcer.kt`). When the watchdog applies the baseline on a provisioned Device-Owner child, that restriction **disables adb the instant it enforces**. The consequence (documented at `ExitCriteriaE2ETest.kt` and `e2e-exit-criteria.sh`): an adb-driven instrumented test can run **only** in the provisioned-but-not-yet-enforcing window, so it can never observe the *enforced* baseline (exit criterion 2). The repo works around this by force-stopping the watchdog before the test and checking criterion 2 out-of-band — correct, but criterion 2 is not automatable.

The 2026-06-29 research (issue #131 context) confirmed two things that bound the design space:

1. **`DISALLOW_DEBUGGING_FEATURES` is NOT redundant and MUST stay always-on in release.** Per the AOSP javadoc, `DISALLOW_APPS_CONTROL` explicitly does *not* cover adb (`pm clear` / `force-stop` still work via adb), `DISALLOW_USB_FILE_TRANSFER` is MTP-only (not `adb pull`), and `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` gates the UI consent (not `adb install`). It is the only restriction in the set that closes the adb console — the staging ground for the crit-rated K1 rollback attack. Dropping or relaxing it in release reopens that attack for a non-root adversary explicitly in scope.

2. **A `BuildConfig.DEBUG` runtime gate is forbidden.** Gating the restriction on a build-config boolean manufactures a fail-OPEN variant: a misconfigured release flag or a leaked debug APK would produce a provisioned-looking child with adb fully open. That directly violates the non-negotiable "every error path defaults to *more* restriction, never less" and ADR-020 D1 ("there is no relaxed variant in v1").

So the testing pain must be solved **without** a runtime relaxation of the release restriction set.

## Options

- **A. Injectable `restrictionFilter` seam (chosen).** Add a constructor seam `restrictionFilter: (String) -> Boolean = { true }` to `PolicyEnforcer`, applied to `requiredRestrictions`. Production constructs `PolicyEnforcer(context)` → the default identity filter → the **complete** baseline, byte-for-byte unchanged. Instrumented tests construct `PolicyEnforcer(context, restrictionFilter = { it != UserManager.DISALLOW_DEBUGGING_FEATURES })` so they can run `applyDayOneRestrictions()` on a real provisioned device, verify the rest of the baseline by readback, and keep adb alive. This follows the established seam pattern (production default + test injection, like `lock`) — **but, unlike `lock`, `restrictionFilter` is the highest-blast-radius seam on the class: it is the *only* seam whose non-default injection removes a real *enforced* restriction.** (A no-op `lock` can only make a test observe *less* containment; a narrowing filter actually shrinks the enforced baseline.) The variation is **test-wiring**, not a runtime build flag — but it is explicitly the one seam that can weaken the baseline, so its test-only discipline (D2) is load-bearing.
- **B. `BuildConfig.DEBUG` gate in `requiredRestrictionsForSdk`.** Rejected — manufactures a fail-open release variant (see Context #2). The distinction must be test-DPC-wiring level, not a boolean a release build could flip.
- **C. Leave it as-is (force-stop window only).** The status quo. Keeps criterion 2 un-automatable. Acceptable but strictly weaker than A; A is additive and costs nothing in release.
- **D. Profile-Owner test harness.** Exercises the control-plane without adb-dark, but a Profile Owner cannot set most of the DO-only restrictions, so it never exercises the real enforcement surface. Different test tier, not a substitute.

## Decision

Adopt **Option A**.

**D1 — Add the `restrictionFilter` seam, default identity.** `PolicyEnforcer` gains a final constructor parameter `restrictionFilter: (String) -> Boolean = { true }`. `requiredRestrictions` becomes `requiredRestrictionsForSdk(Build.VERSION.SDK_INT).filter(restrictionFilter)`. The default `{ true }` is an identity filter, so **every production construction site is unchanged** — the applied-and-verified set is exactly the ADR-020/022 baseline it is today.

**D2 — The seam is test-wiring; release is never relaxed at runtime.** No production code passes a non-identity filter — an invariant currently **enforced by code review plus the default-constructor regression test (which pins the full release set), NOT by a structural/type guard.** `restrictionFilter` is the last positional constructor parameter; a lint/architecture rule restricting it to test source sets would make this a durable guarantee rather than a point-in-time fact, and is a recommended follow-up (issue #131). There is no `BuildConfig`/runtime branch on the restriction set, and test/androidTest source is not compiled into the release artifact, so the narrowing lambda physically cannot reach a release build as the tree stands today. The only callers that narrow the set are instrumented tests, which inject `{ it != UserManager.DISALLOW_DEBUGGING_FEATURES }` to keep adb alive while asserting the rest of the enforced baseline. This preserves ADR-020 D1 for release while making the enforcement path testable.

**D3 — Fail-closed shape is unchanged.** `applyDayOneRestrictions` still applies-all → readback-verify → `lockNow()`+throw on any gap, over whatever set `requiredRestrictions` resolves to. A test that omits one restriction simply applies + verifies a one-smaller set; the fail-closed contract (no partial-unrestricted return) is identical. The omitted restriction in tests is the single adb-killing one, whose presence in the *release* set is proven by the default-constructor regression test and whose tamper-value is documented in the research.

## Consequences

**Good:**
- **Enables** automating exit criterion 2 (enforced baseline intact) on a provisioned device with adb alive — the gap `E2E_EXIT_CRITERIA.md` documents — without weakening release. **This change lands the seam + host tests ONLY; it does not itself automate criterion 2: `ExitCriteriaE2ETest` still does not assert it.** The instrumented consumer that injects the filter is a follow-on (issue #131); criterion 2 becomes automatable *once that lands*, not in this change.
- Uses the established seam pattern (`lock`, `isRestrictionSet`, …); zero new machinery, one constructor parameter.
- Release enforcement is provably unchanged: the default identity filter yields the exact current list (regression-tested).

**Bad / accepted limits (disclosed):**
- A test injecting the filter proves everything-but-`DISALLOW_DEBUGGING_FEATURES`; the omitted restriction itself is still only verified out-of-band (the adb-dark window) and by the redundancy analysis in the research. This is inherent — you cannot both keep adb alive and observe the restriction that kills adb.
- The seam *could* be misused to omit more than the one restriction. Mitigation: it is test-only by convention (no production caller), and the default-constructor regression test pins the full release set so any production drift fails CI.

## Test plan (binds the implementation)

`PolicyEnforcerTest` (Robolectric, host):
- **Release set preserved (regression):** the default-constructor `requiredRestrictions` still equals `requiredRestrictionsForSdk(SDK_INT)` and still contains `DISALLOW_DEBUGGING_FEATURES`.
- **Filter omits exactly one:** `restrictionFilter = { it != DISALLOW_DEBUGGING_FEATURES }` yields a set missing only that key; every other baseline entry remains.
- **Filtered apply verifies the filtered set:** with the filter and a readback reporting the filtered set as present, `applyDayOneRestrictions()` returns cleanly (no throw) and `missingRestrictions()` is empty — i.e. omitting the adb-killer lets the rest verify.
- **Filter cannot resurrect a dropped release entry:** the default (production) path is unaffected by any test filter instance.

## Amendment — 2026-07-02: instrumented consumer landed (issue #131)

The seam above shipped in PR #137 with host (Robolectric) tests only; this ADR explicitly deferred
"the instrumented consumer that injects the filter" to a follow-on so exit **criterion 2** (the
enforced Day-One baseline is intact — `docs/E2E_EXIT_CRITERIA.md`) could become ADB-automatable. That
consumer has now landed:

- **`ExitCriteriaE2ETest.exitCriterion2_enforcedBaselineIntactMinusAdbKiller`** constructs
  `PolicyEnforcer(context, restrictionFilter = { it != UserManager.DISALLOW_DEBUGGING_FEATURES })` and
  calls `applyDayOneRestrictions()` on the real Device Owner — which applies **and verifies** the
  filtered baseline against the DO-authoritative `getUserRestrictions(admin)`, throwing on any gap.
  `DISALLOW_DEBUGGING_FEATURES` is filtered out, so adb stays alive; the test flanks the apply with an
  independent recompute of the expected set and an assertion that the adb-killer is not enforced.

**What this proves — and does NOT (no over-claim):**
- It automates criterion 2 for the **whole baseline except the one adb-killing restriction** — that
  one stays out-of-band (inherent; see "Bad / accepted limits" above). Its presence in the *release*
  set is pinned by the host regression test, not by this instrumented run.
- Its added assurance over the host test is one specific thing: it runs against the **real
  `DevicePolicyManager`** (the host test injects a fake readback), so it proves the restrictions
  actually **stick on the platform**, not merely that the enforcer's logic is internally consistent.
- The readback oracle is `DevicePolicyManager.getUserRestrictions(admin)` — the **DO-authoritative**
  view, the same fail-closed-correct authority the enforcer verifies against. `UserManager`'s
  effective view is deliberately NOT the oracle: it can report a restriction set by another source and
  so **fail-OPEN** (see the `defaultRestrictionReader` note in `PolicyEnforcer`). The test reduces the
  "enforcer is both actor and oracle" coupling by (a) recomputing the expected set independently from
  the pure `requiredRestrictionsForSdk` and (b) asserting the adb-killer is not enforced (outside
  apply's own verify scope). A post-apply re-read of the filtered set is deliberately **omitted** — it
  would be redundant with apply's internal verify (same oracle), so the test does not dress it up as
  independent corroboration. It does **not** claim to fully break the circle — no more-independent
  *and* fail-closed-correct on-device oracle exists.
- **Release/production is untouched.** This change adds only `androidTest` source plus docs; the
  default identity filter and the shipped restriction set are byte-for-byte unchanged.

Run precondition: provisioned Device Owner with the watchdog **halted** (baseline absent at start); the
test reverts every restriction it set in `finally`. On a live watchdog the full baseline (incl. the
adb-killer) is re-asserted on the next tick — the documented enforcement behaviour, hence the halted
precondition. Status stays **Accepted**; the structural/lint guard of D2 remains a separate follow-up
(issue #139).

## Cross-refs
- [ADR-020](020-failclosed-dayone-restrictions.md) — the fail-closed baseline; D1 "no relaxed variant in v1" (release-scoped, preserved here)
- [ADR-022](022-allowlist-deny-by-default-profile-escape.md) — the profile-escape block, also in the required set
- `docs/research/09-disallow-debugging-and-cross-oem-provisioning.md` — why `DISALLOW_DEBUGGING_FEATURES` must stay always-on (redundancy analysis)
- `docs/E2E_EXIT_CRITERIA.md`, `scripts/e2e-exit-criteria.sh`, `ExitCriteriaE2ETest.kt` — the adb-dark constraint this seam relieves
- Issue #131 (this seam); issue #132 (the `setUserControlDisabled` + `adb_enabled=0` hardening gap the research also surfaced)
