# ADR-033: Parent root key — BIP39 recovery phrase → Argon2id → HKDF → Ed25519/X25519 (issue #24)

Status: Accepted
Date: 2026-06-22
Relates: **CRYPTO.md §1–§2** (the key inventory + the BIP39→root-key derivation this implements), **RECOVERY.md §2/§11** (phrase generation, confirm-back, entry security), **ADR-015/019** (the one signing rule the derived identity key feeds), **ADR-031** (the child-side `IdentityKeyProvider` seam this mirrors on the parent), **PARENT_KMP_STRUCTURE.md §3/§13** (the locked KMP crypto stack + library matrix this amends); docs/ATTACKS.md (H6 shoulder-surf, K2/K3), docs/DEFENSES.md (#15 phrase + time-lock)

> **Amendment 2026-06-29 (issue #144):** the `setUserAuthenticationRequired(true)` MasterKey **throws** on a device with no secure lock screen — and the unguarded read path crashed the parent app the instant the pairing screen ran. The `SecureKeyStorage` open/read path is now **fail-closed to `null`** (never throws), so the no-key/no-lock case degrades to the graceful `PairingPhase.NotProvisioned` instead of an uncaught crash; `write` still refuses loudly. See the **Amendment (2026-06-29)** section at the end.

## Context

Issue #24 (`area:parent-kmp`, `priority:critical`, `crypto`, `agent-blocked`): the parent app must generate its Ed25519 **root authority** key from a BIP39 24-word recovery phrase, display the phrase exactly once, force a confirm-back step, and store the derived key material in the platform keystore. The root key is the top of the parent trust chain — it signs policy bundles (#27) and anchors pairing (#23) and recovery (RECOVERY.md R1/R2). "Recovery phrase = root authority, BIP39 24-word" is a CLAUDE.md architecture invariant.

**The derivation is already specified in CRYPTO.md §2** — this ADR turns that spec into concrete, fail-closed, testable code and records the decisions the spec left open or stated wrongly:

1. CRYPTO.md §2 and RECOVERY.md §2 **disagree on the Argon2id parameters**.
2. The Argon2 **library CRYPTO.md §3 names does not ship Argon2**.
3. The "store in keystore" line is **under-specified** for a *derived* (recoverable) key.

**State of the code this lands on.** `parent-kmp/` builds today (Gradle 8.11, JDK 17, `:proto`/`:shared`/`:androidApp`). `shared/commonMain/crypto/` already has `Identity` (volatile Ed25519 keygen via libsodium), `PolicySigner`, `SealedBox`, `CryptoBootstrap`. There is **no** BIP39, **no** Argon2/HKDF wiring, **no** key persistence. libsodium (ionspin bindings 0.9.2) is wired but, per the existing `PolicySigner`/`SealedBox` doc-comments, **its native lib is absent on the desktop JVM** — libsodium round-trips run on-device only. The iOS targets are already host-gated behind `HostManager.hostIsMac` in `shared/build.gradle.kts` (no Mac on the current dev platform).

## Options (the three open decisions)

### Argon2id parameters (CRYPTO.md vs RECOVERY.md)

| Source | memory | iterations | parallelism |
|---|---|---|---|
| **CRYPTO.md §2** (crypto canon) | 256 MiB | 4 | **2** |
| RECOVERY.md §2 (recovery UX doc) | 64 MiB | 3 | 1 |

- **A. CRYPTO.md 256/4/2 governs, chosen.** CRYPTO.md is the dedicated cryptographic specification; RECOVERY.md mentions params in passing while describing UX. The crypto doc wins and RECOVERY.md §2 is corrected to match. The derivation runs **once** on the *parent* phone at onboarding and again only at restore — not a hot path — so the heavier memory-hardness is affordable.
- **B. RECOVERY.md 64/3/1.** Lighter; would let the already-wired libsodium `pwhash` perform the step. Rejected: changing the crypto canon to the weaker of two documented values, to fit a tool, is the wrong default.

### Crypto provider for the derivation

CRYPTO.md §3 names `andreypfau/kotlinx-crypto` for Argon2. **That library ships no Argon2 module** (its modules are AES/BLAKE2/CRC32/HMAC/KECCAK/PBKDF2/Poly1305/Salsa20/SHA1/SHA2 as of 0.0.5). The doc reference is aspirational and cannot be followed. A second candidate, `ionspin/multiplatform-crypto`'s pure-Kotlin Argon2, self-documents as *"unsafe… prototyping only"* — disqualified for a root key. libsodium's `crypto_pwhash` cannot express **p=2** (it fixes parallelism at 1) and is desktop-JVM-untestable.

- **A. Bouncy Castle for the entire derivation, chosen.** `bcprov-jdk18on` provides production-grade, RFC-conformant **SHA-256** (`SHA256Digest`), **Argon2id** (`Argon2BytesGenerator`, RFC 9106 v0x13, `withMemoryAsKB/withIterations/withParallelism` — expresses p=2 exactly), **HKDF-SHA256** (`HKDFBytesGenerator`, RFC 5869), and **Ed25519/X25519 public-key derivation** (`Ed25519PrivateKeyParameters.generatePublicKey`, `X25519PrivateKeyParameters.generatePublicKey`, RFC 8032/7748). All run on the **host JVM**, so the full derivation — and the ratified test vector — are verifiable in `jvmTest`/CI without a device. Every output is a standard RFC primitive, so it is **byte-identical to libsodium's** equivalent: the parent derives with BC, the child verifies with libsodium, and they interoperate. On Maven Central with source jars, MIT-style license (Apache-2.0-compatible per PARENT_KMP_STRUCTURE §1, clears the F-Droid bar §8). JVM + Android only; **iOS derivation is deferred** to a follow-up (iOS build is already host-gated off).
- **B. Mix libsodium (HKDF/keypair) + a separate Argon2.** Rejected: libsodium's derivation primitives are untestable on the desktop JVM (no native lib), forcing the whole root-key vector onto a device run; using one vetted library for the deterministic derivation is simpler to audit and fully host-testable.

### At-rest storage of the derived key

The root key is **derived from the mnemonic** and must stay reproducible from it (recoverability is the whole point). AndroidKeyStore **generates** non-exportable keys and cannot import a caller-supplied derived private scalar as a hardware key — so "native keygen in the Keystore" is incompatible with a recoverable key.

- **A. Wrap the derived key bytes in `EncryptedSharedPreferences` under a StrongBox-backed `MasterKey`, chosen.** Matches the child's `ReplayFloorStore` idiom (`MasterKey.Builder().setRequestStrongBoxBacked(true)`); the master KEK lives in StrongBox/TEE, the derived scalars are AES-GCM-wrapped at rest, `setUserAuthenticationRequired(true)` per CRYPTO.md §1.
- **B. Native AndroidKeyStore Ed25519 keygen.** Rejected — incompatible with recoverability.

## Decision

Adopt **A** on all three. Concretely:

**D1 — Derivation pipeline (CRYPTO.md §2, made executable).**
```
entropy      = SecureRandom(32 bytes)                       // 256 bits
mnemonic     = BIP39-encode(entropy)                        // 24 words + checksum
seed(64)     = Argon2id(NFKD(mnemonic), salt, m=256MiB, t=4, p=2, out=64)
                 salt = SHA-256("openwarden-bip39-v1")[0..16]
prk(32)      = HKDF-SHA256-Extract(salt="openwarden-v1", ikm=seed)
ed25519_priv = HKDF-SHA256-Expand(prk, info="openwarden-parent-ed25519-v1", L=32)
x25519_priv  = HKDF-SHA256-Expand(prk, info="openwarden-parent-x25519-v1",  L=32)
ed25519_pub  = Ed25519 public(ed25519_priv)                 // RFC 8032
x25519_pub   = X25519 public(x25519_priv)                   // RFC 7748 (scalarmult base)
```
No SLIP-10/BIP-32 hierarchy. Same mnemonic ⇒ same two keys, deterministically and independently. **NFKD note:** the BIP39 English wordlist is pure ASCII, so NFKD normalization is the identity on every supported mnemonic; the code passes the UTF-8 mnemonic bytes directly and documents that a non-ASCII wordlist (not supported in v1) would require platform NFKD.

**D2 — CRYPTO.md §2 params (256 MiB / t=4 / p=2) are canonical.** RECOVERY.md §2's `m=64 MiB, t=3, p=1` line is corrected to match in this PR (a doc-consistency fix, not a scope change).

**D3 — The deterministic derivation runs entirely on Bouncy Castle** (`SHA256Digest`, `Argon2BytesGenerator`, `HKDFBytesGenerator`, `Ed25519PrivateKeyParameters`/`X25519PrivateKeyParameters`), in the JVM/Android source set, host-testable. No hand-rolled crypto. CRYPTO.md §3's library table is corrected (Argon2 row `andreypfau` → Bouncy Castle; and the SHA-256/HKDF/scalar rows note BC on the parent derivation path) in this PR.

**D4 — The provider signs via Bouncy Castle Ed25519** (`Ed25519Signer` over the stored 32-byte seed), which keeps the whole provider host-testable. This is RFC 8032 Ed25519 — byte-identical to libsodium — so a signature made with the derived seed verifies under the child's libsodium verifier, and when #27 later feeds the **same** 32-byte seed to `PolicySigner` (libsodium `crypto_sign_seed_keypair`) the two paths interoperate. #24 therefore needs **no libsodium**; libsodium remains the child-side verification path and the bundle-signing path #27 will wire in. The stored canonical private form is the 32-byte Ed25519 seed (the form both BC and libsodium accept), plus the 32-byte X25519 private scalar.

**D5 — BIP39 is a vendored pure-Kotlin `commonMain` module** (the canonical 2048-word English list, verified by its published SHA-256 `2f5eed53…3b24dbda`; entropy↔mnemonic packing; checksum byte = first 8 bits of `SHA-256(entropy)`). `NovaCrypto/BIP39` is JVM-only and cannot live in `commonMain`; we port the algorithm (small, fully covered by the standard vectors). The bit-packing is pure Kotlin in `commonMain`; the one SHA-256 it needs for the checksum is provided by the JVM/Android crypto provider (BC). Single-word-typo detection (RECOVERY §11) rides the checksum.

**D6 — Storage seam mirrors the child.** `commonMain` `interface RootKeyProvider { rootPublicKey(); encryptionPublicKey(); sign(msg) }` with a fail-closed `NotProvisionedRootKeyProvider` (all `null`) exactly like child `IdentityKeyProvider`/`NotProvisionedIdentityKeyProvider`. `androidMain` `KeystoreRootKeyProvider` persists the derived scalars in `EncryptedSharedPreferences` under a StrongBox-backed `MasterKey` (`setUserAuthenticationRequired(true)`), unwrapping the private seed only transiently to sign. **The phrase itself is never persisted** (CLAUDE.md: no BIP39 phrases in storage/repo) — only the derived keys are stored; restore re-derives from re-entry.

**D7 — Confirm-back gate is fail-closed and testable.** `commonMain` `ConfirmGate` state machine: after display, the parent re-types 6 randomly-chosen word indices; the gate stays `Unconfirmed` until **all six** match, then emits `Confirmed`. **Until `Confirmed`, the derived key is not persisted and `RootKeyProvider` stays not-provisioned** — a parent who scrolled past without reading cannot proceed (any mismatch ⇒ stay closed). The deferred Android display screen **will** set `FLAG_SECURE` (the screen itself is a follow-up — see Consequences — so no `FLAG_SECURE` code lands in this PR; the testable orchestration does). Also deferred (the #24 scope cut): the printable recovery sheet (RECOVERY §3), randomized full-entry order + per-field show-toggle (RECOVERY §11), and the biometric lock on the entry screen.

**D8 — Test-vector ratification (the all-zeros mnemonic), host-side.** CRYPTO.md §2's placeholder vector is ratified and asserted host-side — Bouncy Castle is pure-JVM, so **no device is required**. Because `RootKeyDerivation` lives in `androidMain`, its tests run under **`testDebugUnitTest`** (the Android *unit* test task — host JVM, no emulator), while the platform-agnostic `Bip39`/`ConfirmGate` tests run under `jvmTest`. Both are host/CI tasks. Coverage:
- The **entire** vector — `seed`, `prk`, `ed25519_priv`, `x25519_priv`, `ed25519_pub`, `x25519_pub` — is frozen in `RootKeyDerivationTest` (`ratifiedVector` + `ratifiedPrk`), and CRYPTO.md §2 publishes the **same** complete vector (canon and test pin identical values).
- **BC ≡ libsodium is pinned host-side** by RFC known-answer tests (`BcRfcInteropTest`): BC Ed25519 reproduces RFC 8032 §7.1 Test 1 (pubkey + signature) and BC X25519 reproduces RFC 7748 §6.1 — so the BC outputs are the same RFC primitives libsodium emits. The on-device BC-sign → libsodium-verify cross-check is a tracked follow-up for #27 integration (it needs the libsodium native lib).
- The **BIP-39 wordlist is hash-pinned** (`Bip39Test.wordlistMatchesCanonicalSha256` asserts the assembled list's SHA-256 == the published `2f5eed53…`), and the Argon2 password contract is pinned (`argon2PasswordIsSingleSpacedAscii`).
- Fail-closed provider/onboarding semantics are proven host-side against an in-memory `SecureKeyStorage` double. Only the real `EncryptedSharedPreferences`/StrongBox persistence needs a device (`androidInstrumentedTest`); nothing in #24's correctness gate depends on that run. **CI must run `:shared:testDebugUnitTest` + `:shared:jvmTest`** for these gates to bite (ties to the CI-gating work, #31).

## Consequences

- **iOS derivation is an open follow-up.** Only the iOS implementation is missing; the iOS target is already host-gated off on non-Mac builds. A follow-up issue tracks an iOS Argon2id/derivation (Secure Enclave / a vetted Swift Argon2) before the parent app ships on iOS.
- **+1 dependency (`bcprov-jdk18on`, ~6–8 MB, JVM/Android only).** On Maven Central with source jars (clears the F-Droid + license bar in PARENT_KMP_STRUCTURE §8/§13). Confined to the JVM/Android source set.
- **256 MiB test heap.** The `jvmTest` task is configured with `-Xmx` headroom so the Argon2id seed vector can run host-side.
- **Doc corrections in-PR:** RECOVERY.md §2 params and CRYPTO.md §2 ratified vector + §3/§13 library rows. These are consistency fixes that flow from D2/D3/D8, not a re-scope.
- **Best-effort wipe only.** `RootKeys.wipe()` zeroes the private scalars after persist, but the serialized blob, its hex string in `EncryptedSharedPreferences`, and the mnemonic `List<String>` held during onboarding are JVM-managed and cannot be reliably zeroized — a known residual that matches the child's posture.
- **At-rest format.** The 128-byte key blob is hex-encoded into `EncryptedSharedPreferences` (which takes `String`, not `ByteArray`); it is AES-256-GCM-wrapped under the StrongBox/TEE MasterKey, so the encryption-at-rest property holds. `EncryptedSharedPreferences` is deprecated in current `androidx.security:security-crypto`; the child uses the same idiom (`ReplayFloorStore`), so any future migration is a coordinated decision.
- **Follow-ups tracked:** Compose onboarding screen (with `FLAG_SECURE`); on-device BC-sign → libsodium-verify cross-check (host-side RFC KATs cover the provable half); iOS derivation; printable sheet + randomized entry + biometric entry-lock.
- **Maintainer sign-off (recorded):** (a) **StrongBox→TEE fallback for the parent KEK MUST be disclosed to the parent**, mirroring the child's disclosed-downgrade posture (ADR-029 D4) — maintainer ruling at merge; tracked as a follow-up (surface the attestation/TEE-fallback signal in the parent app). (b) #27's background bundle signing MUST NOT relax `setUserAuthenticationRequired` — a derived session key is the safe answer, not weakening the root KEK's at-rest gate.
- Accepted on maintainer approval (PR #86).

## Amendment (2026-06-29) — `SecureKeyStorage` open/read is fail-closed-to-`null`, never a crash (issue #144)

**Finding (found live).** Opening `Pair a child device` on a device with **no secure lock screen** hard-crashed the parent app with an uncaught `IllegalStateException: Secure lock screen must be enabled to create keys requiring user authentication`. Chain: `PairingFlowScreen` → `PairingController.ensureStarted()` → `PairingSessionManager.start()` → `StoredRootKeyProvider.rootPublicKey()` → `AndroidSecureKeyStorage` builds the `setUserAuthenticationRequired(true)` `MasterKey` → throws (no lock screen) → nothing caught it → app died. This **violated the `RootKeyProvider`/`SecureKeyStorage` nullable contract** (D6 says accessors return `null` fail-closed) and was a fail-**not**-closed defect (an uncaught crash, not graceful refusal).

**Decision.** The Android `SecureKeyStorage` open/read path is **fail-closed to `null` and never throws**:
- `read()` / `clear()` swallow *any* store-open failure (no secure lock screen, keystore error) and degrade to "no usable key" / no-op. `StoredRootKeyProvider` (and through it pairing + bundle signing) already treats `null` as not-provisioned and refuses gracefully — so the pairing flow now reaches `PairingPhase.NotProvisioned` instead of crashing.
- **The guarantee extends end-to-end, not just to the store-open step.** A present-but-malformed blob (interrupted/partial write, future format skew) makes `RootKeys.deserialize()` throw `require(size == 128)`; unguarded that escaped `StoredRootKeyProvider.rootPublicKey()` into `PairingSessionManager.start()` and reproduced the **same crash one layer up** (#144 Finding 2, found in crypto review). `StoredRootKeyProvider.load()` now wraps `deserialize` and degrades a corrupt blob to `null` (logged loudly), and `isProvisioned()` reflects a *well-formed* key — so neither a store-open failure **nor** a corrupt blob can throw out of a `RootKeyProvider` accessor. Treating a corrupt blob as not-provisioned is safe: the AES-GCM-authenticated store fails decryption (→ `read()` null) on external tamper, so a valid-decrypt-but-wrong-size blob can only be our own bad write, and re-onboarding mints a **new** identity the already-paired child will not trust (not a takeover path).
- `write()` remains **loud**: it throws the typed `SecureStorageUnavailableException` (not the raw keystore exception) so a provisioning flow never believes a key was persisted when it was not, and can show "set a screen lock first."
- The encrypted prefs are built lazily and **re-attempted after a failure** (cached only on success), so the store **recovers** once the user sets a screen lock — no app restart needed.

**Unchanged (NOT a crypto relaxation).** The MasterKey is still `setUserAuthenticationRequired(true)` + StrongBox-requested; the at-rest gate (D6) and the recovery-grade auth requirement are untouched. This amendment only stops an *uncaught crash* and makes the impl honor the existing fail-closed nullable contract. The parent-facing `NotProvisioned` copy is widened to name the screen-lock precondition. A distinct "device not secure" pairing phase (vs. reusing `NotProvisioned`) is a possible UX follow-up, not required for the fix.

**Tests.** Host tests (`AndroidSecureKeyStorageTest`, injected throwing `prefsFactory`): `read()` → `null` (no crash); `clear()` → no-throw; `write()` → `SecureStorageUnavailableException`; a failed open is re-attempted (not cached). `StoredRootKeyProviderTest.corruptBlobIsFailClosedNotCrash`: a wrong-size stored blob degrades every accessor to `null`/`false`, never throws (Finding 2 regression). The success round-trip stays on-device (androidInstrumentedTest).

**Deferred follow-ups (crypto review).** (1) `RecoveryOnboarding.confirm()` should wrap `write()` + `keys.wipe()` in `try/finally` so the derived scalars are zeroized even when `write()` throws; and the deferred Compose onboarding screen (D7) MUST catch `SecureStorageUnavailableException` and render "set a screen lock first" so the loud refusal has a listener (today nothing catches it, so a failed provisioning write would crash onboarding the way #144 crashed pairing). Tracked as a follow-up issue, not in this PR's scope. (2) Whether a corrupt at-rest blob should instead surface a distinct *tamper* signal (vs. silent re-onboard) is a maintainer ruling; the fail-closed-to-not-provisioned default above is the conservative crash-fix.

## Amendment (2026-07-01) — onboarding derivation runs off-main under `largeHeap`; derivation is non-abortable (issue #155)

**Finding (found live).** Completing recovery onboarding (the Confirm step) hard-crashed the parent with `java.lang.OutOfMemoryError` inside BouncyCastle's `Argon2BytesGenerator$Block.<init>`, on the **main thread** (`FATAL EXCEPTION: main`). Chain: the Compose Confirm click → `RecoveryOnboardingViewModel.confirm()` → `RecoveryOnboarding.Session.confirm()` → `RootKeyDerivation.deriveRootKeys()` → the D1 Argon2id at the ratified **256 MiB** memory cost (D2). BC's pure-Java Argon2 materialises that 256 MiB working set as `Block` objects on the Dalvik heap, but the androidApp had **no `largeHeap`**, so the ~192 MB default heap OOM'd. Because the derivation ran synchronously on the main thread, the OOM landed as a main-thread crash (and even without the OOM, a ~2 s 256 MiB derivation on main is an ANR). This blocked **all** parent provisioning (both onboarding and the debug-seed path call `deriveRootKeys`).

**Decision.**
- **`android:largeHeap="true"` (app-global) is the accepted posture, NOT a scoped-process derivation.** The ratified D2 parameter (256 MiB / t=4 / p=2) is **unchanged** — lowering it would break recoverability and cross-impl (libsodium) agreement, so the fix addresses the *container*, not the cost. `largeHeap` raises the per-app heap ceiling so the ratified derivation fits. We accept that `largeHeap` applies to the whole process lifetime (a permanently larger heap, a fatter heap-dump target) rather than confining the 256 MiB working set to a short-lived `:derivation` process. Rationale: the secret material (mnemonic `List<String>`, 64-byte seed, PRK, derived scalars) is *already* heap-resident and dumpable during the ~2 s derivation window regardless of heap size (an accepted residual in Consequences), and `keys.wipe()` clears the derived scalars right after `provision()`, so post-onboarding no plaintext root scalar should linger; `largeHeap` does not create a new class of secret-in-heap exposure, only a larger heap. A scoped-process derivation is a possible hardening follow-up, not required here. A better long-term fix is a **native Argon2 (libsodium) on Android** so the working set lives off the Dalvik heap entirely (BC stays the ratified host-side vector engine) — tracked as a follow-up.
- **Derivation runs off the main thread.** `RecoveryOnboardingViewModel.confirm()` is now `suspend` and runs `confirmSession` via `withContext(Dispatchers.Default)`. The single-flight CAS→`Submitting` still runs on the caller thread **before** dispatch (a double-tap derives at most once), and the fail-closed `catch(Exception)→StorageError` is preserved — JVM `Error`s (OOM) still propagate (not masked as a storage hint), so once `largeHeap` makes the OOM not fire, a genuine low-RAM OOM still fails closed rather than falsely provisioning.

**Atomicity contract (derivation is non-abortable; persist-then-cancel self-heals).** The Compose caller launches `confirm()` in a `rememberCoroutineScope`, cancelled when the screen leaves composition. BC's `generateBytes` is a tight CPU loop with no suspension points, so cancellation cannot abort it mid-derivation — it runs to completion on the `Default` worker, and `withContext` then throws `CancellationException` instead of delivering the result, so `provision()` never runs (wasted CPU, but nothing persisted — fail-closed). There is no suspension point between derive and the single `commit()` write, so cancellation cannot land *between* derive and a half-written blob (and a torn blob is already fail-closed to not-provisioned per the 2026-06-29 amendment). If `confirm()` *succeeds* (key persisted) but the scope is cancelled before the `Provisioned` UI transition, the key is on disk and the next launch's `showOnboarding = !isProvisioned()` reads `true`/skips onboarding — the persisted-but-UI-cancelled case self-heals. This is the intended contract, recorded here rather than left emergent.

**Unchanged (NOT a crypto relaxation).** The D1/D2/D3 derivation (256 MiB Argon2id v0x13 → HKDF-SHA256 → Ed25519/X25519) and the ratified vector are byte-identical. This amendment changes only *where* the derivation runs (off-main) and *how much heap it may use* (largeHeap). Verified live (2026-07-01): with both changes, onboarding provisions without OOM and the full pair → signed-lock trust-path works end-to-end.

**Tests.** `RecoveryOnboardingViewModelTest` converted to `runTest` + an injected `UnconfinedTestDispatcher`; single-flight, wrong-answers, storage-error, unexpected-throw, and retry contracts all still asserted with the now-`suspend` `confirm`.
