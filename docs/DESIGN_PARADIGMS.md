# Design Paradigms — Contributor Guide

> **Audience:** open-source contributors adding features, screens, or
> protocols to OpenWarden.
>
> **Companion docs:** [`UX_PATTERNS.md`](UX_PATTERNS.md) is the user-facing
> design canon; this doc is the *contributor-facing* enforcement layer for
> it. [`SIMPLIFY.md`](SIMPLIFY.md) is the scope-creep filter your feature
> must clear before this doc even applies. [`ARCHITECTURE.md`](../ARCHITECTURE.md)
> is the three-plane mental model; [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md)
> is the code layout; [`PROTOCOL.md`](PROTOCOL.md) is the wire format you
> must not break.

The goal of this document is one sentence: **anyone can add a feature
without breaking the design, the threat model, or the funding model.**
If you internalize the rules below, your PR merges faster and Oliver's
phone stays safer.

---

## 1. UI rules — the contributor-facing summary

The full UX canon is [`UX_PATTERNS.md`](UX_PATTERNS.md). Do not re-state
patterns here; reference them. The rules below are the ones reviewers
will flag in PR comments.

**Kid side (child Android):**
- Pictogram-first, text second. Never text-only on a kid screen.
- Matter-of-fact copy. No exclamation points. No emoji except the four
  reason pictograms in §A1.
- Every blocked-app screen routes to **Ask dad** (§A2). No dead ends.
- Never hide an app. Gray-and-suspend, never hide. (§A3.)
- Emergency dial is always reachable from every locked screen. Non-negotiable.

**Parent side (KMP — Compose & SwiftUI):**
- Three top-level tabs: **Family Feed**, **Devices**, **Rules**. Do not
  add a fourth without an ADR. (§C8.)
- Defaults are visible. Every rule the parent has not explicitly set
  shows a "default" tag.
- Friction is asymmetric: locking more is one tap, locking less takes
  several. (§C1.)
- Audit is always visible. Every rule change is in the Family Feed,
  immediately, signed, undoable for 24h. (§B.)
- Never market to the parent. "Take control of your child's screen!" is
  a PR-blocking phrase. (§C7.)

If your PR adds a kid screen or a parent screen, link to the §x.y of
`UX_PATTERNS.md` it satisfies in the PR description.

---

## 2. Code conventions

### Kotlin

- **ktlint defaults.** Run before PR. CI fails noisy diffs.
- **Indentation 4 spaces.** No tabs.
- **Function length:** under 40 lines is the soft target. If it grows,
  extract — small composables and small presenters compose better.
- **Nullability is information.** Don't `!!`. Use `?.` and explicit
  fallbacks. The one exception is post-bootstrap singletons after
  `LibsodiumInitializer.initialize()`.
- **No `runBlocking` outside tests.** Use structured `CoroutineScope`s.

### Compose (Android, parent + child)

- **Stateless composables.** State is hoisted to the presenter layer.
- **Presenter pattern:** `@Composable fun presenter(events: Flow<Event>): Model`
  using Molecule. No `ViewModel` subclasses; no AAC `LiveData`; no
  manual `remember { mutableStateOf() }` for screen-level state. Per-widget
  state (a TextField cursor) is fine local.
- **No `LaunchedEffect` for side effects that should live in the
  presenter.** Effects in the UI tree should be UI-only (focus, scroll,
  animation).
- **Theming via `MaterialTheme` tokens.** No hardcoded colors. See §6.

### SwiftUI (iOS, parent)

- **`@Observable` macros** (iOS 17+). No `ObservableObject` /
  `@Published` boilerplate where the macro applies.
- **No MVI / Redux libraries.** State flows from the shared KMP
  `StateFlow` via SKIE's `AsyncSequence` bridge.
- **`@MainActor` on view models** that touch SwiftUI state. The shared
  Kotlin code is thread-safe; the bridge is not.
- **No third-party SwiftUI charting/animation libs** for v1. Keep the
  Swift dependency surface near-zero.

### SKIE bridge

- Every Kotlin `suspend fun` exposed to Swift becomes a real
  `async throws`. Annotate Kotlin with `@Throws` so the Swift signature
  is correct.
- Every Kotlin `Flow<T>` becomes a Swift `AsyncSequence<T>`. Cancellation
  is propagated; honor it in iOS view-model `Task`s.
- Sealed classes ship as Swift enums with associated values. Use this
  for the `Result<T>` type below.

### Error handling

- **No `throw` on crypto failure.** Crypto errors return a sealed
  `Result.Failure` type. The protocol layer fail-closes, the UI shows
  a calm error state.
- **Sealed result types** for every operation that can fail at a
  trust boundary:
  ```kotlin
  sealed interface SignResult {
      data class Ok(val sig: UByteArray) : SignResult
      data object KeyUnavailable : SignResult
      data object StrongBoxRevoked : SignResult
  }
  ```
  Forces the caller to handle every case at compile time.
- **No silent retries** in security paths. A retry with the same
  expired bundle should not happen; a retry on transport failure is fine
  in the sync layer with bounded backoff.
- **Fail closed.** Every catch block that doesn't know what to do should
  re-throw, not swallow. The default posture is "deny."

---

## 3. Policy + protocol invariants

These are not negotiable. PRs violating them get rejected at review.
Cross-reference [`SECURITY.md`](SECURITY.md), [`PROTOCOL.md`](PROTOCOL.md),
[`CRYPTO.md`](CRYPTO.md).

1. **All persistent state at rest is encrypted.** StrongBox-backed
   AES-GCM on Android, Secure Enclave-wrapped on iOS, libsodium sealed
   boxes for inter-app payloads. No SharedPreferences with sensitive
   fields, ever.
2. **All commands are signed Ed25519.** No unsigned command crosses
   the trust boundary, not even from localhost. Pinned pubkey checked
   on every receive.
3. **Replay protection is mandatory.** Every command carries
   `policy_seq` (monotonic counter) and `not_before` / `not_after`
   timestamps. Stale or out-of-order → drop and log.
4. **Fail closed on any verification error.** Signature fails →
   restrictions intensify, not relax. Bundle expired → stale-policy
   mode ([`UX_PATTERNS.md`](UX_PATTERNS.md) §A5), not "let it slide
   while we recover."
5. **No third-party SaaS in the core path.** Tailscale and ntfy.sh
   are *optional convenience transports*. The LAN + WireGuard path
   must always exist and always work.
6. **No telemetry, no analytics, no crash beacons.** Crash logs stay
   on device, opt-in for the parent to share by email.
7. **No content exfiltration.** AI classifiers run on-device. Events
   carry category + severity + timestamp. Never the message, never the
   image, never a hash from which content can be reconstructed.

---

## 4. Feature-adding checklist

Before you open a PR, your feature must pass every line:

- [ ] Does it require a paid service to function? → **refuse**. (See
      [`SIMPLIFY.md`](SIMPLIFY.md) tier rules; the Apple Developer
      Program TestFlight is the only acknowledged exception, and it
      can't apply to new core features.)
- [ ] Does it leak child content to any third party (including the
      parent's cloud backups)? → **refuse**.
- [ ] Does it weaken the threat model in [`SECURITY.md`](SECURITY.md)
      or open a new bypass path documented in [`ATTACKS.md`](ATTACKS.md)?
      → **refuse**.
- [ ] Does it require >Medium ongoing maintenance per release? → **defer**
      to a v2+ milestone; document the cost in
      [`ROADMAP.md`](ROADMAP.md).
- [ ] Is it a Tier 0 / Tier 1 feature per [`SIMPLIFY.md`](SIMPLIFY.md)? If
      Tier 2, link the rationale. If Tier 3, close the PR.
- [ ] Are there tests? Protocol vectors in `commonTest`, UI behavior in
      Compose preview snapshots + screenshot tests, security paths with
      property tests.
- [ ] Does it touch `:proto`? If yes, you must bump
      `policy_bundle_format_version` and document the migration in
      [`PROTOCOL.md`](PROTOCOL.md).
- [ ] Did you add a kid-facing screen? Link the
      [`UX_PATTERNS.md`](UX_PATTERNS.md) §A.x it derives from.
- [ ] Did you add a parent-facing screen? Compose **and** SwiftUI
      implementations both, or none.
- [ ] Did you write the empty state? Every list needs one.
      ([`UX_PATTERNS.md`](UX_PATTERNS.md) §C9.)

---

## 5. "I want to add X" — mini-cookbook

### Add a new restriction (e.g., "block app installs after 8pm")

1. Add the enum value to `PolicyDoc.Restriction` in `:proto`.
2. Bump `policy_bundle_format_version` (e.g., 3 → 4). Old child devices
   must reject the new bundle with a clean error, not crash.
3. Add the enforcement code in `child-android/.../PolicyEnforcer.kt`.
   Use the appropriate `DevicePolicyManager` call. Fail-closed if the
   call throws.
4. Write a unit test that verifies the restriction applies on a fresh
   policy and a property test that verifies the canonicalization is
   stable.
5. Add the parent-side toggle in `androidApp/.../ui/policy/` (Compose)
   **and** `iosApp/.../Views/PolicyEditorView.swift` (SwiftUI). Same
   copy, same default.
6. Document the new restriction in [`DEFENSES.md`](DEFENSES.md) row N+1,
   with the attack it counters in [`ATTACKS.md`](ATTACKS.md).
7. Add to [`ROADMAP.md`](ROADMAP.md) under the milestone it ships in.

### Add a new transport (e.g., Briar / Matrix)

1. Implement the `Transport` interface in `shared/commonMain/sync/`.
   The interface is small: `discover()`, `connect()`, `send()`,
   `receive()`, `close()`. Read existing impls (`LanMdnsTransport`,
   `WireguardTransport`) first.
2. Add to `TransportRegistry` with a stable string ID.
3. Add platform-specific code in `androidMain` / `iosMain` only if
   required (BLE, NFC, etc.).
4. Add a parent-side toggle in **Settings → Advanced → Transports**.
   Never expose new transports on the top-level Settings list; they
   live behind Advanced.
5. Tests: a fake transport in `commonTest` that mirrors the new one's
   semantics; integration tests for the real one on a CI runner if
   possible.
6. Document in [`README.md`](../README.md) under "Transport modes."

### Add a new on-device AI classifier (e.g., "spam-DM detector")

1. Read [`LOCAL_AI.md`](LOCAL_AI.md) end to end. The constraints are
   strict: on-device only, opt-in per category, output is a flag event
   (category + severity + timestamp), never content.
2. Pick a model that runs in <500ms on a Pixel 7 Tensor chip. If it
   doesn't, it doesn't ship.
3. Output flows into the shared event log via a sealed `AiEvent` type.
4. Parent UI: a single toggle under **Rules → AI signals**, with plain
   copy explaining what is detected, what is shared (a flag, never
   content), and what the false-positive rate looked like in your
   eval set.
5. Default: **off**. Always.
6. Document the model's training data, eval results, and known
   blind spots in [`LOCAL_AI.md`](LOCAL_AI.md) §N.

### Add a new parent UI screen

1. Decide which top-level tab it lives under. Do not add a fourth tab.
2. Build Compose first; build SwiftUI to match.
3. Empty state, loading state, error state — all three required.
4. Accessibility: TalkBack labels, VoiceOver labels, 48dp tap targets,
   color-blind-safe iconography.
5. Add to the navigation graph in both apps.
6. Screenshot test on the canonical Pixel 7 + iPhone 15 simulator
   geometries, light and dark and high-contrast.
7. Copy review: post a screenshot to the PR; reviewers will flag tone.

---

## 6. Design system tokens

Keep the token set small. New tokens require an ADR.

**Color (semantic, not hex):**
- `surface` / `surfaceContainer` / `surfaceContainerHigh` — Material 3.
- `primary` / `onPrimary` — calm blue, never red.
- `error` — used only for genuine error states. Never for "blocked."
- `warningSubtle` — used for stale-policy / over-budget banners. Yellow,
  not red.
- `pictogramFill` — for the four reason pictograms. Single color.

**Type scale:** Material 3 default: `displayLarge` → `labelSmall`. Kid
screens use `displayMedium` for reason copy, `bodyLarge` for descriptions.
Parent screens use `titleLarge` for tab headers and `bodyMedium` for
content.

**Spacing:** 4dp / 8dp / 12dp / 16dp / 24dp / 32dp. Nothing in between.
Most screens snap to 16dp gutters.

**Elevation:** Material 3 levels 0–3 only. No floating action buttons in
the kid UI; one at most per parent screen.

**Motion:** standard Material 3 easing. No bouncing, no celebratory
animations. The product is calm; the motion should be too. Lottie
animations are not allowed in v1.

---

## 7. Iconography

- **Android:** **Material Symbols** only. No bespoke SVG icon sets.
- **iOS:** **SF Symbols** only. No bespoke icon sets.
- The four kid-side reason pictograms (clock, lock, question mark,
  hourglass) are the only custom-drawn symbols. They live in
  `shared/commonMain/resources/icons/` and are identical across both
  platforms.
- Icons are always paired with text labels. Color is never the only
  signal. ([`UX_PATTERNS.md`](UX_PATTERNS.md) §C10.)

---

## 8. Copy tone

Matter-of-fact. Respectful. Never punitive. Never marketing.

Good:
- "Roblox is paused until 4pm."
- "Dad got it. Usually answers in about 5 min."
- "Phone paused by dad."
- "Sync with dad to keep using all your apps."

Bad:
- "Oh no! It's bedtime, sleepyhead!" (infantilizing)
- "ACCESS DENIED." (authoritarian)
- "Take control of your child's screen time!" (marketing)
- "🚫 You can't use that app right now! 😢" (emoji noise, condescending)

Reviewers will copy-edit your PR. Don't be precious about it.

---

## 9. Anti-patterns to reject in PRs

1. **Hidden apps.** Use `setPackagesSuspended`, not
   `setApplicationHidden`, for kid-visible apps. Lying to the kid breaks
   the model.
2. **Sound or vibration on a kid block.** Block screens are silent.
3. **Modal nag screens to "review your rules!"** No nagging the parent.
   The Sunday digest is the only proactive nudge.
4. **A "remember this device" checkbox** for skipping recovery-phrase
   re-entry. The phrase is the master key; weakening its enforcement
   defeats the model.
5. **Auto-bypass for "trusted networks"** (home Wi-Fi, etc.). Policy is
   policy regardless of network.
6. **Custom theming or color pickers** for the parent. We have one
   default scheme and a high-contrast variant. That's it.
7. **Read receipts on parent-side messages.** We don't message between
   parents in v1. Don't open the door.
8. **Crash reporters that auto-send** (Firebase Crashlytics, Sentry's
   default config, etc.). Opt-in, manual export, never auto-upload.
9. **Storing the BIP39 phrase anywhere on disk**, ever, even encrypted.
10. **"Just for now" `TODO` comments in security paths.** They become
    permanent. Either implement it correctly or leave it unimplemented
    with a `Result.Failure` return.
11. **Hidden settings for power users** that change security posture.
    Power-user toggles that affect UX are fine under Advanced; toggles
    that affect security are not.
12. **Soft-disable of restrictions via a debug build flag.** Debug builds
    must enforce the same restrictions as release. The `/health`
    content provider from [`PROVISIONING_V2.md`](PROVISIONING_V2.md) §9
    is the only debug-only artifact.

---

## 10. Versioning strategy

SemVer everywhere.

- **MAJOR** bump = breaking protocol change. Old children cannot apply
  new bundles; old parents cannot read new feed entries. Requires a
  migration doc in [`PROTOCOL.md`](PROTOCOL.md), a compatibility table,
  and a release-notes "upgrade your child phone first" warning.
- **MINOR** bump = new restriction, new transport, new UI screen,
  backward-compatible protocol extension (additive fields only).
- **PATCH** bump = bug fix, copy edit, dependency bump.

`policy_bundle_format_version` in `:proto` tracks the wire-format major.
Pre-v1.0.0 we're free to break things; once v1.0.0 ships, breaks need an
ADR.

Tags: `v0.x.y` pre-release, `v1.0.0` for the first real shipping build,
strict SemVer from there. CI gates on the version in
`gradle/libs.versions.toml` matching the tag.

---

## 11. PR review rubric (for reviewers)

Reviewers look for, in order:

1. Threat model: does this PR weaken anything in
   [`SECURITY.md`](SECURITY.md)?
2. Scope: is this Tier 0–1 per [`SIMPLIFY.md`](SIMPLIFY.md)?
3. UI parity: Compose + SwiftUI both?
4. Empty / error / loading states?
5. Tests, especially crypto and protocol vectors?
6. Copy tone (§8)?
7. ktlint / SwiftFormat clean?
8. Docs cross-references (this doc, `UX_PATTERNS.md`, etc.) intact?

If any answer is "no," request changes. If §1 or §2 is "no," close the
PR with the polite no-template from [`SIMPLIFY.md`](SIMPLIFY.md).
