# ADR-029: Tier-2 attestation posture — accept OEM-root + TEE-level attestation for the committed Samsung/OnePlus targets, with a disclosed downgrade (amends ADR-025; reconciles CRYPTO §3/§10)

Status: Accepted
Date: 2026-06-18
Amends: **ADR-025 D2 (check 1 root, check 3 security-level), D7 (device scope)**; reconciles **CRYPTO.md §3** (StrongBox-non-negotiable) + **§10** (attestation-root + securityLevel rules)
Relates: ADR-026 (commits Pixel + Samsung + OnePlus at v1.0 — this ADR is its load-bearing crypto prerequisite), ADR-027 (QR-OOBE provisioning reuses this pairing), ADR-023 (enforcement-floor tiers + disclosed gaps), ADR-001 (device tiers), ADR-015 (sealed-box audience = the pinned child X25519 key), ADR-019 (canonical signing); docs/ANDROID_COMPAT.md §3 (StrongBox/TEE matrix) + §4 (per-OEM attestation roots), docs/ATTACKS.md §1 (threat model) + H3 (pubkey substitution), docs/DEFENSES.md #4/#14 (attestation → anti-swap)

## Context

ADR-026 (Proposed) commits **Samsung (S22+/A55+/Note) + OnePlus 11+** to the v1.0 release at the ADR-023 disclosed-gap floor. ADR-025 (Accepted) — the ratified pairing trust model — makes those devices **unpairable as written**:

- **ADR-025 D2 check 1** requires the attestation cert chain to root in the **Google Hardware Attestation root**; **check 3** requires `securityLevel == STRONGBOX`. **CRYPTO §3 (L124)** calls `setIsStrongBoxBacked(true)` "non-negotiable … we **do not** fall back to TEE-only … requires Pixel 6/7/8." **CRYPTO §10 (L453/L456)** refuses any non-Google root (`ATTEST_ROOT_UNKNOWN`) and rules `TRUSTED_ENVIRONMENT` (TEE) "not acceptable."
- **ANDROID_COMPAT §3** establishes that the committed Tier-2 targets are **TEE-only** (Samsung A55, most OnePlus) and that where StrongBox exists (Knox Vault flagships) its cert chain roots in **Samsung Knox / Snapdragon SPU, not Google**.

So every committed non-Pixel target fails ADR-025 D2 checks 1 and 3 and refuses the pair. This ADR defines the **minimum** trust-model relaxation that lets them pair **without opening a hole**: widen exactly two checks (the accepted root set, and the accepted attestation security level) for the committed OEMs, and **change nothing else** — VERIFIED boot, locked bootloader, model allow-list, the four-key SAS, and fail-closed-refuse-on-unknown all stay mandatory. The maintainer has already chosen the disclosed-gap posture (ADR-026); this ADR is the crypto execution of that choice, kept as conservative as possible.

## Decision

**D1 — Per-tier attestation acceptance (amends ADR-025 D2 checks 1 & 3).** Attestation stays mandatory; the *accepted* root set and security level become tier-scoped:

| Check | Tier 1 (Pixel) — UNCHANGED | Tier 2 (committed Samsung/OnePlus) |
|---|---|---|
| Cert-chain **root** | Google Hardware Attestation root only | **allow-listed OEM root** in `oem_roots.json` (Google **+** Samsung Knox **+** OnePlus). **Any root not in the allowlist → refuse (`ATTEST_ROOT_UNKNOWN`), fail-closed.** |
| Attestation **security level** | `STRONGBOX` required (TEE = kill — a Pixel reporting TEE is forged/mis-provisioned) | `STRONGBOX` **or** `TRUSTED_ENVIRONMENT` (TEE). **`SOFTWARE` is always an immediate kill.** StrongBox used where present (Knox Vault flagships); TEE accepted otherwise. |

**Widening the allow-listed root set is the *only* root relaxation** — an unrecognized root never passes on any tier. **Accepting TEE is the *only* security-level relaxation** — and only for Tier 2; SOFTWARE is killed everywhere, and TEE remains a kill on Tier 1.

**Tier is an *output* of verification, never an input.** A device is Tier 2 only as a *consequence* of its attestation chain validating to an **allow-listed OEM root** for an **allow-listed model** — it can **never self-report a tier** to unlock TEE acceptance. The implementation MUST derive `tier` from the matched root+model, not from any device-reported property; otherwise the two relaxations could become a self-declared bypass.

**D2 — Every other ADR-025 D2 check stays mandatory for Tier 2, and the four-key SAS becomes *more* load-bearing.** Unchanged and required on the committed OEMs:
- `verifiedBootState == VERIFIED` (GREEN) and **bootloader locked** — the committed OEMs ship these (ANDROID_COMPAT §1); no relaxation;
- **allow-listed model** — a per-OEM model allow-list (the specific committed S22+/A55+/Note + OnePlus 11+ model strings); an unknown Samsung/OnePlus model **refuses**, exactly like an unknown root;
- attestation challenge == the issued `provisioning_nonce`; leaf-cert pubkey == `child_ed25519_pub`;
- **the four-key SAS (ADR-025 D2a) — MANDATORY.** Because TEE-level attestation is a weaker hardware-genuineness proof than StrongBox (D4), the human six-emoji MITM compare is the load-bearing second factor on Tier 2; it is never dropped. SAS-only (no attestation) stays out of scope (D7).

**D3 — The downgrade is DISCLOSED, and that disclosure is release-gating (binds ADR-026 D3 / ADR-023 D5).** Accepting OEM-root + TEE-level attestation is a real reduction in the anti-device-swap (H3 / DEFENSES #14) hardware-genuineness proof versus Pixel StrongBox + Google-root. The pairing/onboarding flow **must** show the parent, before pinning: *"this device's hardware identity is verified at TEE level via <OEM>'s attestation root, not Google's hardware root — slightly weaker physical-tamper resistance than a Pixel, and a compromised attestation key cannot be revoked per-device (only retired via an app update)"* — and require acknowledgement. Silent acceptance would be the over-promise the non-negotiables forbid.

**D4 — Accepted residual (the security-meaningful consequence the maintainer is signing off).** TEE attestation **still proves genuine OEM hardware** (the OEM's attestation key signs the chain) and **still binds the identity key to the device**. Versus Pixel StrongBox + Google-root it loses on **two** axes:
- **(a) Physical-extraction / side-channel resistance** of the key store — a discrete secure element (Titan M2, Knox Vault) survives EM-glitching/probing better than TEE-on-SoC. For the **declared threat model** (ATTACKS §1: a kid with `adb` + YouTube, **no JTAG, no oscilloscope, no nation-state**) this is acceptable — a kid cannot physically extract a TEE-bound key.
- **(b) Root-of-trust provenance** — the anti-device-swap / hardware-genuineness proof (DEFENSES #14, Pattern E) is now **OEM-rooted (TEE)** rather than **Google-rooted (StrongBox)**: weaker *provenance*, but the anti-swap proof itself is **still carried** by the retained mandatory checks (OEM-root-chain validation + VERIFIED boot + locked bootloader + model allow-list). **The four-key SAS does NOT compensate this axis** — the SAS proves key-liveness against a live MITM, not hardware genuineness; the retained chain + boot checks are the anti-swap compensator.

**One in-threat-model attack the SAS *does* catch:** a **leaked OEM TEE attestation key** (CRYPTO §3 notes TEE attestation keys have leaked more often than StrongBox) would let a kid forge a chain that roots in a genuinely allow-listed OEM root with TEE level, VERIFIED boot, and an allow-listed model — passing every *attestation* check. This is a download-a-tool attack, arguably **inside** the threat model. It is caught by the **four-key SAS** (the forged chain must still survive a live human six-emoji compare with the real device in hand) **plus** the requirement to be a live LAN-MITM at pairing — which is exactly why the SAS is kept load-bearing (D2). The retirement path for a *known*-leaked OEM key is removing its root from `oem_roots.json` (D6); per-leaf OEM revocation does not exist (CRYPTO §10 note), and that absence is disclosed (D3).

This residual is **accepted, scoped to the threat model, and disclosed (D3)** — it is **not** a fail-open (the key remains hardware-bound, the chain still validates to a known allow-listed root, and VERIFIED-boot + the four-key SAS still hold). If the threat model ever expands to physical attackers, this is the line to revisit.

**D5 — Reconcile CRYPTO §3 + §10 in the same PR** (so the spec does not contradict this ADR):
- **§3 (L124):** the "StrongBox non-negotiable / no TEE fallback / Pixel-only" rule becomes the per-tier rule (D1): Tier 1 requires StrongBox (keygen throws → refuse); Tier 2 attempts StrongBox and **falls back to TEE keygen** (`setIsStrongBoxBacked(false)`) when `StrongBoxUnavailableException` is thrown, **never** to `SOFTWARE`.
- **§10 (L453):** "root must be Google's" → "root must be in the `oem_roots.json` allow-list (Google + committed OEM roots); any other root → `ATTEST_ROOT_UNKNOWN` refuse."
- **§10 (L456):** "`TRUSTED_ENVIRONMENT` not acceptable" → "`SOFTWARE` is always an immediate kill; `TRUSTED_ENVIRONMENT` (TEE) is **acceptable on Tier 2**, refused on Tier 1 (a Pixel reporting TEE means a forged extension or mis-provisioned device)."

**D6 — `oem_roots.json` + model-allow-list update path (closed by construction).** Both the trusted-root set and the per-OEM model allow-list ship in `:shared:crypto` and change **only via a signed parent-app release** — **never** over the runtime signed-bundle/policy channel. (ANDROID_COMPAT §4 floats a "refresh via the bundle channel" idea; it is **rejected** here — a runtime data-push of trusted roots would let a compromised *day-to-day parent signing key* widen trust without the recovery phrase.) A change that **adds a root or a model** is therefore gated by the app-signing key **and**, per **ADR-025 D8**, additionally **recovery-phrase-gated** (BIP39 phrase + 24-h delay) — so neither a stolen parent signing key nor a hijacked app-update channel alone can introduce an attacker root/model. Verification is parent-side; an unknown root or unknown model is **never** auto-trusted — it surfaces `unverified_attestation` and refuses to pin (fail-closed). (ANDROID_COMPAT §4.)

**D7 — Amends ADR-025 D7 (device scope).** ADR-025 D7's "attestation mandatory on the Pixel-class tier; SAS-only out of scope" is amended only to **widen the accepted attestation tier** to `{Pixel: StrongBox + Google-root}` ∪ `{committed Samsung/OnePlus: StrongBox-or-TEE + allow-listed OEM-root}`. **Attestation remains mandatory on every committed device** — SAS-only (no attestation) stays out of scope (v3+); we never drop to SAS-only for a committed target.

## Blocking before implementation (adds to ADR-025's list)

These do not block ratifying the posture, but each must be resolved fail-closed before code:
- **Per-OEM model allow-list** — the exact committed model strings (Samsung S22+/A55+/Note variants, OnePlus 11+ variants) and the maintenance/update path; an unknown model refuses.
- **TEE-keygen fallback path** — the Tier-2 `StrongBoxUnavailableException` → TEE keygen branch, with a test that a `SOFTWARE` security level is still killed.
- **Provisioning-QR nonce semantics** (carried from ADR-027 / ADR-025's list) — lifetime, single-use, burn-on-failure.

## Consequences

**Good:** the committed Samsung/OnePlus targets can pair; the relaxation is the **minimum** (two checks, tier-scoped); fail-closed-refuse-on-unknown-root and the four-key SAS are untouched; the human MITM catch still holds on the weaker-attestation devices.

**Bad / accepted limits:** weaker physical-extraction resistance on TEE-only targets (the accepted residual D4 — disclosed, threat-model-scoped); a per-OEM root **and** model allow-list to maintain; the test matrix grows; the parent-app trusted-root set is now an updatable artifact (D6), a small new surface.

**Security note:** the **only** two relaxations are *root: Google → allow-list* and *security-level: STRONGBOX → TEE-acceptable (Tier-2 only)*. Everything else — VERIFIED boot, locked bootloader, model allow-list, nonce binding, leaf-pubkey binding, the four-key SAS, recovery-gated rotation (ADR-025 D8), and **refuse-closed on any unknown root or SOFTWARE level** — is unchanged. This is the smallest change that lets the committed devices pair without widening the trust boundary beyond the disclosed, threat-model-scoped residual.

## Test plan (binds the implementation issues)

- **Tier-2 accept:** a Samsung/OnePlus chain rooting in an **allow-listed OEM root**, `securityLevel ∈ {STRONGBOX, TRUSTED_ENVIRONMENT}`, VERIFIED boot, locked bootloader, **allow-listed model**, matching nonce, leaf-pubkey == `child_ed25519_pub`, four-key SAS Match ⇒ **pin**.
- **Tier-2 refuse (each, deterministic/seam-injected, fail-closed):** unknown/non-allow-listed root; `securityLevel == SOFTWARE`; `verifiedBootState != VERIFIED`; unlocked bootloader; non-allow-listed model; nonce mismatch; leaf-pubkey mismatch; **any** four-key SAS substitution (incl. either X25519).
- **Tier-1 invariant preserved:** a Pixel chain reporting `TRUSTED_ENVIRONMENT` (not STRONGBOX) ⇒ **still refuse** (D5 / §10 L456).
- Vectors under `docs/test-vectors/pairing/` — add `pair-04-tier2-tee-ok` and `pair-05-fail-unknown-oem-root` alongside the existing ADR-025 §9.1 vectors.

## Cross-refs
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) (the ratified trust model amended here — D2 checks 1/3, D7)
- [ADR-026](026-top-oem-release-scope.md) / [ADR-027](027-provisioning-distribution-model.md) (Proposed — unblocked by this ADR)
- [docs/CRYPTO.md](../CRYPTO.md) §3 (L124), §10 (L453/L456) — reconciled here
- docs/ANDROID_COMPAT.md §3 (StrongBox/TEE matrix), §4 (per-OEM roots); docs/ATTACKS.md §1 (threat model), H3; docs/DEFENSES.md #4/#14
