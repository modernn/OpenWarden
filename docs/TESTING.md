# OpenWarden Test Strategy & CI Design

Status: Normative for the OpenWarden reference implementation. SPDX-License-Identifier: Apache-2.0.

This document defines the testing posture and continuous-integration design for OpenWarden. It is bound by the architectural locks: **KMP shared core + Android child + iOS parent, libsodium via [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium), reproducible builds, no SaaS.** Read [`PROTOCOL.md`](PROTOCOL.md), [`CRYPTO.md`](CRYPTO.md), [`PROVISIONING_V2.md`](PROVISIONING_V2.md), [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md), [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md), and [`OTA.md`](OTA.md) first — those docs define *what* must be built; this doc defines *how we prove it works*.

Anything described as "MUST" here gates a release. The pre-release checklist in §17 is the merge-to-main gate.

---

## 1. Test pyramid

OpenWarden is a security product with a small surface and an unlimited adversary. The pyramid is heavy at the base because *fast feedback on protocol/crypto regressions* is the only way to ship securely.

| Tier | Mass | Where | Runtime |
|---|---|---|---|
| **Unit** | 70% | JVM (shared KMP code), pure Swift modules | <30 s full suite |
| **Integration** | 20% | JVM with mocked Android APIs, Ktor in-process server, Android emulator headless | <5 min full suite |
| **End-to-end** | 10% | Real Pixel 7 or full-fat AVD with provisioning + sync round trip | <20 min full suite |

The pyramid inverts the usual SaaS convention because OpenWarden has no backend to integration-test against. The protocol *is* the contract. JCS canonicalization, Ed25519 signing, sealed-box framing, and `policy_seq` regression checks (`PROTOCOL.md` §2.1) are all pure-function logic that belongs in the unit tier where it can be hammered.

---

## 2. Test categories

Categorized by what they prove rather than where they run.

- **Crypto.** Test vectors are mandatory per `PROTOCOL.md` §9. Both child (Kotlin) and parent (Swift) consume the same `docs/test-vectors/` corpus, and CI cross-checks byte-identical canonical output and signatures. Negative vectors are first-class.
- **Protocol.** Signed bundle verify, replay rejection (`policy_seq` regression → `REGRESSION`), fail-closed posture on parse error, stale-policy mode on `EXPIRED`, hash-chain continuity. Covers `PROTOCOL.md` §10 conformance items.
- **Policy.** Time-window evaluation against `parent_anchor + elapsedRealtime` (per §5.1), allowlist enforcement, blocklist precedence, restriction-set materialization.
- **DPC.** Android device-policy restrictions actually applied. Verified by parsing `dumpsys device_policy` and `dumpsys package` output; we *do not trust* that calling `setUserRestriction` did anything until `dumpsys` confirms it.
- **Sync.** LAN mDNS discovery (`_openwarden._tcp.local`) + REST round trip end-to-end, including the §4.3 state-machine transitions and idempotent partial-sync resume from §4.5.
- **UI.** Snapshot tests on Compose preview (Android) and SwiftUI previews (iOS). Per locale, per theme, per dynamic-type size.
- **Performance.** Heartbeat latency, sync time, battery attribution, peak resident memory.
- **Accessibility.** TalkBack/VoiceOver, contrast, large-text reflow (§11).
- **Failure modes.** Storage full, network down, bundle expired, clock skew, signature parse fail (§15) — each must drop to strict baseline, never to unrestricted.

---

## 3. Tools

- **Kotlin unit:** [JUnit 5](https://junit.org/junit5/) + [Kotest](https://kotest.io/). JUnit 5 for matrix runners and parameterized vectors; Kotest for matcher ergonomics and property-based generators.
- **Compose UI:** Compose Preview annotations + [Paparazzi](https://github.com/cashapp/paparazzi) for hermetic JVM screenshot rendering (no emulator). Paparazzi runs on the JVM unit tier despite testing UI — this is intentional; it keeps screenshot baselines reproducible.
- **iOS:** XCTest + [swift-snapshot-testing](https://github.com/pointfreeco/swift-snapshot-testing) for SwiftUI snapshot diffs.
- **Mocking:** [MockK](https://mockk.io/) (KMP-friendly; works in `commonTest` via `MockKMatcherScope`).
- **Property-based:** Kotest's `forAll { ... }` generators. Used heavily on canonicalization (`PROTOCOL.md` §3) and policy evaluation.
- **CI:** GitHub Actions. Free tier; we never pay for compute on this project.
- **Android emulator:** [reactivecircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner). Hardware accelerated where the runner supports it; falls back to swiftshader on x86_64.
- **Fuzzing:** [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) for JVM-side fuzz harnesses (bundle parser, sealed-box decoder, policy JSON).
- **Reproducible builds:** [`diffoscope`](https://diffoscope.org/) for hash-mismatch root-cause when reproducibility breaks.
- **License/SBOM:** [CycloneDX gradle plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin) + [licensee](https://github.com/cashapp/licensee).

---

## 4. GitHub Actions matrix

Seven jobs; jobs 1, 5, 6 run on every PR; jobs 2, 3, 4, 7 run on merge-to-main and on `release/*` branches.

| # | Job | Trigger | Runner | Wall time |
|---|---|---|---|---|
| 1 | JVM unit tests (KMP shared) | every PR | `ubuntu-latest` | ~3 min |
| 2 | Android instrumented tests on emulator API 34 + 35 | merge + release | `ubuntu-latest` w/ KVM | ~10 min |
| 3 | iOS XCTest (parent app) | merge + release | `macos-14` | ~8 min |
| 4 | Reproducible build hash check (deterministic AGP + Gradle) | merge + release | `ubuntu-latest` | ~5 min |
| 5 | Lint: ktlint + detekt + SwiftLint | every PR | `ubuntu-latest` + `macos-14` | ~2 min |
| 6 | License audit (no GPL deps; enforce Apache-2 / MIT / BSD allowlist) | every PR | `ubuntu-latest` | ~1 min |
| 7 | SBOM generation (CycloneDX) | release tag | `ubuntu-latest` | ~2 min |

PRs cannot merge red on jobs 1, 5, or 6. Jobs 2–4 are *release blockers* but allowed to be amber on a PR while iterating, to preserve developer velocity.

---

## 5. Test vectors (per PROTOCOL.md mandate)

Lives at [`docs/test-vectors/`](test-vectors/). The corpus is the source of truth and the *only* artifact that proves cross-platform parity between Kotlin and Swift.

Required content:

- **Keys.** `parent-keys.json` with a fixed parent Ed25519 + X25519 keypair derived from a fixed BIP39 mnemonic (per `CRYPTO.md`).
- **Bundles.** Known mnemonic → expected Ed25519/X25519 pubkeys; known PolicyBundle JSON → expected canonical bytes (RFC 8785) → expected Ed25519 signature.
- **Events.** Known plaintext + recipient X25519 pubkey → expected sealed-box shape (constant 2048-byte canonical envelope per §6.4). Ciphertext is randomized per encryption, so we assert *shape and decryptability*, not literal byte-equality of ciphertext.
- **Negative vectors.** Bad signature, expired bundle (`not_after` < `monotonic_now`), `policy_seq` regression, hash-chain break, ORANGE verified-boot attestation. Each carries an expected reason code (`SIG_FAIL`, `EXPIRED`, `REGRESSION`, `CHAIN_BREAK`, etc.) and the assertion is that the verifier produces exactly that code.

Both Android and iOS test suites load the same corpus via a shared loader. A vector that passes on Kotlin but fails on Swift (or vice versa) is a P0 release blocker.

---

## 6. Emulator full-provisioning E2E

Job 2 boots a fresh AVD from the factory state on every release candidate.

```
AVD: Pixel 7 system image (google_apis), API 35
Emulator launch: -no-window -no-audio -no-boot-anim -no-snapshot
Provisioning: QR-based device-owner flow per PROVISIONING_V2.md
```

Sequence:

1. Boot AVD from cold (no snapshot reuse — provisioning must work from factory).
2. Run the provisioning script: generates a QR, pipes its payload into the emulator as the device-owner setup token.
3. Assert state: OpenWarden is the Device Owner, all `DISALLOW_*` restrictions from the test PolicyBundle are present in `dumpsys device_policy`, FRP account is bound, the child's `/health` endpoint (test-mode only) is reachable on `localhost:8443` with the pinned self-signed cert.
4. Smoke test: a JVM-hosted mock parent sends a signed bundle over LAN; child applies it; emulator-side `dumpsys package` confirms blocked app suspended; the kid screen renders the expected "ask parent" surface.
5. Teardown: AVD destroyed, no state persists into the next run.

This catches provisioning regressions that unit tests never can — the DPC API surface is too stateful to mock faithfully.

---

## 7. Bench-Pixel manual test cards

The maintainer keeps a "Bench Pixel" — a physical Pixel 7 used exclusively for release validation. For each release, a human runs through six cards. Each card produces a video walkthrough plus a checklist artifact stored in the release notes.

- **Card 1: Provision from factory.** Factory reset → QR pair → bundle applied → kid screen shown. End-to-end timing recorded.
- **Card 2: Apply allowlist, verify suspension.** Push a bundle that drops Chrome from the allowlist. Confirm Chrome icon greys, launching it surfaces the "ask parent" screen, no crash.
- **Card 3: A-class attacks.** Run every A-class attack from [`ATTACKS.md`](ATTACKS.md). Confirm blocked or surfaced per `DEFENSES.md`.
- **Card 4: K-class attacks.** Run every K-class attack from `ATTACKS.md`. Confirm surfacing (parent sees signed K-class event) within the heartbeat window.
- **Card 5: 7-day uptime.** Leave Bench Pixel running for a week, confirm the foreground service rebinds across forced restarts, simulate an OTA per [`OTA.md`](OTA.md) and confirm policy survives.
- **Card 6: Recovery phrase entry + decommission.** Parent enters BIP39 phrase, child is decommissioned via the documented flow, device returns to a re-provisionable state.

Total time: ~2 hours of human attention. This is non-negotiable on a release; the threat model assumes a motivated adversary and our test discipline must reflect that.

---

## 8. Continuous fuzzing

Jazzer harnesses cover the three highest-risk parsers:

- **Bundle parser.** Random byte sequences fed to the entry deserializer. Pass condition: zero crashes, zero `MALFORMED` passes-when-it-shouldn't.
- **Sealed-box decoder.** Random ciphertexts fed to `crypto_box_seal_open`. Pass condition: zero crashes, decoder either returns the plaintext or fails closed.
- **Policy bundle JSON.** Structured random JSON satisfying the schema shape, fed through canonicalization + sign + verify. Pass condition: round-trip identity, zero verifier crashes.

Runs nightly on `ubuntu-latest` for 15 minutes per harness. Corpus persisted via Actions cache. New crashes file a P0 issue automatically; the build is red until the crash either has a fixture in the regression suite or is dispositioned as a false-positive in the harness.

`PROTOCOL.md` §10.9 mandates a 1M-byte-sequence parser fuzz pass — that's our minimum acceptance; nightly fuzzing exceeds it.

---

## 9. Performance benchmarks

Tracked in CI as benchmark jobs with thresholds. A regression beyond threshold fails the job.

| Benchmark | Target | Tier |
|---|---|---|
| Sealed-box encrypt + decrypt | <10 ms p99 | unit |
| Bundle verify (Ed25519 + canonical) | <5 ms p99 | unit |
| Heartbeat sync round-trip (LAN) | <2 s p99 | integration |
| Child app peak resident memory | <80 MB | E2E |
| Battery: OpenWarden-attributable drain | <2% / day | Bench Pixel |

The crypto numbers are easy on a Pixel 7 Titan M2; the LAN heartbeat number is the load-bearing one — sync latency is what makes OpenWarden feel alive or dead from the parent's perspective.

---

## 10. Snapshot testing

Compose UI snapshots via Paparazzi on Android; SwiftUI via swift-snapshot-testing on iOS.

Per-screen matrix:

- **Locales:** en, es, fr at minimum (matches `I18N.md` priority). Snapshots are not stretched-rendered — RTL is added with ar in a later release.
- **Themes:** light + dark.
- **Dynamic type:** default + XXXL.

CI fails on snapshot diff. Intentional UI changes regenerate baselines via `./gradlew recordPaparazzi` and the diff lands as part of the same PR. The reviewer sees the visual delta in the PR; no surprise UI changes can slip through.

---

## 11. Accessibility tests

- **TalkBack / VoiceOver.** Every screen has assigned content descriptions; an Espresso accessibility validator + an XCTest accessibility audit run on E2E builds and fail on missing labels.
- **Color-blind safe.** Contrast checked against WCAG AA. Critical state (allowed/blocked/expired) never communicated by color alone — always paired with an icon and a text label.
- **Large text.** Layouts validated at the largest dynamic-type setting. No clipped affordances.

The parent app is the primary i18n target; the child app's surface is intentionally minimal so accessibility coverage there is bounded but uncompromised.

---

## 12. Security tests

- **JVM checks.** Memory-safety equivalents to ASAN for native (libsodium via ionspin is JNI-backed on Android; we run the JNI surface with `-Xcheck:jni` in CI).
- **Dependabot.** Auto-PRs for vulnerable transitive deps. Auto-merge on patch bumps with green CI; held for human review on minors and majors.
- **Secret scanning.** Gitleaks on every PR. No API keys, no signing keys, no test mnemonics that aren't documented as public test data.
- **License scan.** Enforces a license allowlist of Apache-2.0, MIT, BSD-2/3, ISC, and a few documented exceptions (e.g. RFC 8785 reference impl from cyberphone is Apache-2). GPL/AGPL anything is a hard fail.

---

## 13. Test data

- **Deterministic seeds.** Keypair generation in tests uses a fixed seed (`parent-keys.json`). Production code paths use the OS CSPRNG; the seedable variant lives behind an interface that is only swapped in tests.
- **No real Google accounts.** Provisioning tests use throwaway managed test accounts created with `adb shell account add-account` against the AVD's mock account manager. FRP is tested with synthetic email strings, never a real Google account.
- **Mock APIs.** Where the SDK forces a real-Android-only API (Keystore attestation), we use the AVD's StrongBox emulation and a synthetic attestation root (`test-vectors/pairing/pair-01-success.json`).
- **Test mode flag.** `-DOPENWARDEN_TEST_MODE=1` at build time enables the `/health` endpoint and verbose logging. Release builds *must* be built without this flag — the release job asserts the flag is absent from the resulting APK's manifest meta-data.

---

## 14. Coverage targets

Measured via Kover (Kotlin) and Slather (Swift). Targets are **module-scoped**, not global, because pulling UI coverage up is cheap and misleading and pulling crypto coverage up is expensive and necessary.

| Module | Target |
|---|---|
| `crypto` | 100% |
| `protocol` (canonicalization, signing, verification) | 95% |
| `policy` (window eval, allowlist) | 90% |
| UI (Compose + SwiftUI) | 60% (snapshot-driven) |
| Overall | 85%+ |

CI fails any module that regresses below its target. We do not chase coverage on UI — snapshot tests are the better signal there.

---

## 15. Failure-mode tests

Every fail-closed transition documented in `PROTOCOL.md` §10.8 has a dedicated test. These are the "what happens when the universe punches us" tests:

| Stimulus | Expected response |
|---|---|
| Storage full → policy load fails | Strict baseline applied, parent alert emitted |
| Network down for >12 h | Enforce last-known bundle until `not_after` |
| Bundle `not_after` < now | Enter stale-policy mode (strictest baseline, faster heartbeat) |
| Signature parse fail / `sig` truncated | Reject `SIG_FAIL`, strict baseline |
| Wall clock skew >5 min vs `parent_anchor + elapsedRealtime` delta | Emit tamper event, stale-policy mode |
| Hash-chain break mid-sync | Drop session, log `CHAIN_BREAK`, require re-pair |

These tests live in the integration tier and run on every merge.

---

## 16. CI cost budget

The project runs on GitHub Actions' free tier and intends to stay there. Cost forecast per PR:

| Step | Time |
|---|---|
| JVM unit + lint + license (PR jobs) | ~6 min |
| Emulator E2E (merge job) | ~10 min |
| Reproducible build (merge job) | ~5 min |
| **PR total** | **~6 min** |
| **Merge total** | **~21 min** |

We get 2000 free Linux minutes / month for a public repo at the time of writing; even at 30 PRs/week and 4 merges/day this fits with headroom. Quarterly grant reviews per `FUNDING.md` reconsider scaling if external contributor traffic warrants paid runners.

---

## 17. Pre-release gate

A merge to a `release/*` branch must satisfy all of:

- [ ] All unit tests pass (job 1 green).
- [ ] All integration tests pass.
- [ ] Android emulator E2E passes (job 2 green).
- [ ] iOS XCTest passes (job 3 green).
- [ ] Reproducible build hash matches the previous CI-published hash for an unchanged source tree (job 4 green).
- [ ] Bench Pixel cards 1–6 completed within the last 7 days; checklists + video walkthroughs attached to the release.
- [ ] No new fuzzer findings in the last 7 nightly runs.
- [ ] License + SBOM audits clean (jobs 6 + 7 green).
- [ ] All §9 negative vectors reject with the documented reason code.

The gate is the release manager's checklist. No automation overrides it.

---

## 18. Beta channel

Beta builds are signed but explicitly not stable. Distribution channels:

- **Android:** F-Droid beta repo + a signed APK on a release page. Per `DISTRIBUTION.md`, no Play Store presence.
- **iOS:** TestFlight, public link.

Beta users opt in. Crash reports are **opt-in only**, never automatic — OpenWarden's privacy posture is incompatible with default-on telemetry. Feedback flows through GitHub Discussions; we do not run a forum or a Discord.

Beta builds carry a beta-channel flag that surfaces a small "BETA" chip in the parent app's settings and refuses to provision a Bench Pixel device — beta firmware should not run on a kid's actual phone.

---

## 19. Regression detection

Two rules:

1. **Every bug fix ships with a regression test.** No exceptions. The PR template enforces a "test added" checkbox. If a bug can't be unit-tested, the reviewer is expected to push back hard on whether the fix is actually targeting the root cause.
2. **CI matrix runs on every merge.** Red CI on `main` is a stop-the-world event. A Matrix bot in the maintainer channel pings on red CI within 60 seconds of detection (Matrix, not Slack — OpenWarden's collaboration posture matches its product posture).

---

## References

- [`PROTOCOL.md`](PROTOCOL.md) — wire format and conformance checklist (§10).
- [`CRYPTO.md`](CRYPTO.md) — key derivation and libsodium binding choices.
- [`PROVISIONING_V2.md`](PROVISIONING_V2.md) — DPC bootstrap flow exercised by §6.
- [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) — shared/parent module layout.
- [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md) — UX principles informing snapshot/accessibility scope.
- [`OTA.md`](OTA.md) — over-the-air policy update model exercised by Bench Pixel Card 5.
- [`ATTACKS.md`](ATTACKS.md) — adversary catalogue for Bench Pixel Cards 3–4.
- [`DEFENSES.md`](DEFENSES.md) — fail-closed posture exercised by §15.
- [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) — JCS, the canonicalization scheme cross-validated in §5.
- [ReactiveCircus android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) — emulator harness for job 2.
- [Paparazzi](https://github.com/cashapp/paparazzi), [swift-snapshot-testing](https://github.com/pointfreeco/swift-snapshot-testing) — snapshot tooling.
- [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) — JVM fuzzer used in §8.
