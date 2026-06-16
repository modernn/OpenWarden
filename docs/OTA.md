# OTA — Auto-Update and Signing Key Infrastructure

> **Audience:** maintainers cutting releases, contributors writing CI,
> parents asking "is the update on my kid's phone safe?", auditors
> verifying that the signed artifact a child installed matches the
> source we publish.
>
> **Companion docs:** [`DISTRIBUTION.md`](DISTRIBUTION.md) covers how
> parents *get* OpenWarden in the first place; this doc covers how the
> install stays current after that. [`PROTOCOL.md`](PROTOCOL.md) §2.1 +
> §5 is the verification flow we extend for *binaries*.
> [`CRYPTO.md`](CRYPTO.md) §1 governs the keys in motion;
> [`SECURITY.md`](SECURITY.md) is the threat model we don't widen.
> [`SIMPLIFY.md`](SIMPLIFY.md) §3 caps paid line items at one and bans
> server-side anything; this doc respects both.

Two truths shape every decision here:

1. **A bad update on a child phone is worse than no update.** A bricked
   DPC strands a kid with either no restrictions (fail-open — never)
   or no working phone (call-mom-from-a-friend). Both are failures.
2. **The signing key is the project's keel.** Lose it and OpenWarden's
   identity dies; compromise it and every child device must be
   re-bootstrapped. Treat it like the keel.

---

## 1. Update channels

Three channels, opt-in at the parent app's About → Updates screen.

- **Stable.** Monthly cadence (first Tuesday). Security fixes from the
  prior month, polish, minor features. This is the default.
- **Beta.** Parent-opted-in early access. Each Stable release sits on
  Beta for 7 days first. Beta parents accept that policy semantics may
  shift; child DPC stays on Stable unless parent explicitly enrolls
  the child too (rare; usually only co-maintainers).
- **Crisis.** Out-of-band emergency channel for critical CVEs. Skips
  Beta. Pushed to all installs with a parent-visible "Critical update"
  banner. Triggered only by the criteria in §5.

Child DPC channel is **always equal to or one minor behind** parent
channel (§11). A parent on Beta with a child on Stable is the
recommended posture: the parent eats the cutting-edge risk, the
child's enforcement layer stays boring.

---

## 2. Distribution paths

Mirrors [`DISTRIBUTION.md`](DISTRIBUTION.md) §1; the update-time
nuance lives here.

- **F-Droid (v2 onward, Android).** Auto-update via the F-Droid client
  is the recommended path. F-Droid verifies our reproducible build on
  its build server before publishing; users never see an installer
  whose hash we haven't pinned.
- **GitHub Releases (v1 + forever).** Signed APK + manifest +
  CycloneDX SBOM + Sigstore release-note signature on every release.
  Always the fallback if F-Droid stalls.
- **Play Store.** **Not used.** Requires Play Services in the
  manifest; conflicts with the FOSS pledge and the Tier-3 ban in
  [`SIMPLIFY.md`](SIMPLIFY.md) §2.
- **TestFlight (v1, iOS).** Parent app only. Users update via the
  TestFlight client; Apple owns the transport.
- **App Store (v2 onward, iOS).** Standard auto-update. TestFlight
  remains as the Beta channel.

There is no OpenWarden-operated update server. Ever. Adding one would
require uptime, certificate rotation, abuse handling, and a privacy
policy for the IPs that fetch updates. None of those things are on
the maintainable surface.

---

## 3. Update verification

Every Android update — F-Droid, GitHub Releases, store-and-forward,
sideload — runs the same gate:

1. **Signature.** APK is signed with the project release key (§4).
   `PackageInstaller` verifies signature-match against the installed
   APK; mismatched key = installer refuses, full stop.
2. **Manifest.** Each release ships an `update-manifest.json` listing
   `{version, apk_sha256, manifest_sig}` for every artifact. Manifest
   is Ed25519-signed by the same release key, over JCS-canonicalized
   bytes ([`PROTOCOL.md`](PROTOCOL.md) §3).
3. **Hash match.** Downloaded APK's SHA-256 MUST match the manifest
   value before the child DPC hands it to `PackageInstaller`. If
   mismatch: drop the file, log `OTA_HASH_MISMATCH`, surface to parent
   app on next sync.
4. **Reproducible build hash.** Manifest also carries the unsigned APK
   SHA-256 ("rb_hash"). Any parent or auditor running
   `./build-reproducible.sh` at the tagged commit MUST get the same
   value; if not, the release is repudiable and we hear about it
   loudly (§6).

The child DPC verifies steps 1–3 before applying any update,
regardless of source. Reproducibility (step 4) is verifier-side, not
runtime; the DPC trusts the signature, not the build environment of
the parent's machine.

---

## 4. Signing key infrastructure

Two release keys (parent app + child DPC, per
[`DISTRIBUTION.md`](DISTRIBUTION.md) §2). Both follow the same
storage model.

- **Cold-storage root.** Generated on an air-gapped Tails or NixOS
  machine. Held on a hardware token (**YubiKey 5C+ or Nitrokey HSM
  2**) in a fireproof safe. Two USB-stick backup copies of the PKCS#8
  bytes in separate fireproof envelopes; one BIP39-style paper backup
  in a bank safety deposit box. (Same shape as the parent recovery
  flow in [`CRYPTO.md`](CRYPTO.md) §7.)
- **Active signing key.** For v1 the "active" key *is* the root —
  rotation is annual at major-version bumps. We do not split into a
  separate active subkey in v1; the management burden of a key
  hierarchy outweighs the benefit at our scale. v2 may revisit.
- **Backup root.** A second hardware token loaded with the same key
  material at generation time, stored in a different physical
  location (a different maintainer's safe in a different city).
  Single point of failure mitigation only — never used unless the
  primary is destroyed.
- **Annual rotation.** Each year at the major-version cut (v1 → v2,
  v2 → v3), a new root is generated and the rotation procedure
  (§4.1) is run. The old root is destroyed (cut the YubiKey, shred
  the paper) only after **two consecutive minor releases** have
  successfully signed under the new root and existing child devices
  have re-anchored.
- **Procedure in `CONTRIBUTING.md`.** The full step-by-step (cold
  boot, USB plug, sign, unplug, verify, publish) lives in the
  contributor docs, not here. This doc is the contract; that doc is
  the runbook.

### 4.1 Key rotation procedure (summary)

1. Generate new keypair on the air-gapped signing machine.
2. Sign a **rotation manifest**: `{old_pub, new_pub, effective_at}`,
   signed by **both** the old and new private keys.
3. Publish the rotation manifest on GitHub Releases and pin to the
   project website.
4. Cut the next release signed by both old and new keys (dual-sign
   transition period — one minor cycle).
5. Cut the release after that signed by new only.
6. After 6 months with no reports of dual-sign-trusting installers in
   the wild, retire the old key.

### 4.2 Key compromise

The worst case. The procedure is in
[`DISTRIBUTION.md`](DISTRIBUTION.md) §9 ("Compromised signing key"):
revoke via Sigstore Rekor, generate fresh key on the air-gap, publish
the rotation notice dual-signed by old + new, force re-verification
on next install. The child DPC ships a **key-revocation manifest**
endpoint: every parent sync includes the latest revocation list, and
the child refuses updates signed by a revoked key even if the APK
signature itself verifies.

---

## 5. Crisis update fast-path

Critical CVE → emergency release within 24 hours, hitting all
channels at once.

**Threshold for "critical":**
- Remote code execution on parent or child app.
- DPC authentication bypass (a kid can drop Device Owner).
- Sealed-event decryption leak (data exfiltration).
- Signing key compromise.
- Active in-the-wild exploitation regardless of severity.

Anything below this bar is a normal Stable release, even if uncomfortable.

**Crisis flow:**
1. Maintainer-on-call triages the report (target: 4 hours).
2. Fix branched off the latest Stable tag, two-maintainer review
   (target: 24 hours).
3. CI runs the release workflow with `SECURITY_HOTPATCH=1` (skips
   non-essential builds — iOS path is slow, Android hot-fix can ship
   in < 2 hours).
4. Tag `v1.x.x-security`, sign on the air-gap, publish.
5. Parent app surfaces an in-app banner: **"Critical update available
   — tap to install."**
6. Child DPC auto-applies on next sync **if parent has pre-consented**
   (the toggle at pairing — §7); otherwise waits for parent's
   single-tap "Apply now" from the parent app.
7. Crisis email to opt-in list + ntfy.sh public channel + GHSA + Signal
   broadcast + Mastodon (priority order in
   [`DISTRIBUTION.md`](DISTRIBUTION.md) §9).
8. Post-mortem in [`SECURITY.md`](SECURITY.md) within two weeks.

Crisis updates skip Beta entirely. The Beta-soak step is a luxury we
do not have when a known exploit is live.

---

## 6. Reproducible builds CI

Builds the bedrock of step 4 in §3. Implementation follows
[`DISTRIBUTION.md`](DISTRIBUTION.md) §3 verbatim; OTA-specific notes:

- **Pinned toolchain.** JDK 21 (Temurin), Gradle 8.11, Android SDK 35,
  KMP 2.0.x — all version-locked in `metadata.yml` and
  `.tool-versions`.
- **`org.gradle.parallel=false`** for release builds (parallelism is
  the most common reproducibility breaker).
- **CI publishes both signed and unsigned APK SHA-256** in the
  release notes. The unsigned hash is what a verifier compares
  against; the signed hash is what `PackageInstaller` matches.
- **`./build-reproducible.sh`** is a one-line entry point that any
  parent with the pinned toolchain can run; CI runs the exact same
  script and a manual run by a third party MUST produce the same
  bytes.
- **`diffoscope` on mismatch.** Failed reproducibility produces a
  diffoscope artifact diff attached to the failing CI run for
  human-readable analysis. This has caught us twice (Gradle daemon
  PID embedded in a timestamp file; SOURCE_DATE_EPOCH not set on a
  resource-processing task) — both times before the affected build
  shipped.

---

## 7. Auto-update strategy

The default posture is "boringly current." Defaults err toward
applying updates promptly; surfaces err toward visibility.

- **Child DPC.** Auto-applies signed updates if the parent ticked
  "Allow automatic security updates" at pairing (default on, with
  the consent banner explicit about what it means). One-tap parent
  override available in the parent app at any time. If parent
  declined pre-consent, the DPC queues the update and waits for
  in-band confirmation.
- **Parent app, Android.** F-Droid auto-update (v2) is opt-in per
  package via the F-Droid client; we don't override that UX. The
  parent app self-checks the GitHub Releases API on launch (opt-in,
  off by default) and surfaces a non-modal "v1.2.4 available" banner.
- **Parent app, iOS.** TestFlight or App Store; standard OS-managed
  update flow.
- **Update window.** Child auto-updates are **deferred to 03:00 local
  time** in the device's signed-anchor timezone
  ([`PROTOCOL.md`](PROTOCOL.md) §5.1). Don't interrupt a kid mid-use
  with an enforced "Updating OpenWarden…" interstitial. Crisis updates
  override this; everything else waits for the quiet window.
- **Changelog before applying.** The parent app always shows a
  "What's changed" summary (truncated to one screen of bullets)
  before any non-crisis update is applied to the child. The child
  DPC also shows a 5-second toast on next unlock after auto-update:
  "OpenWarden updated to v1.2.4 — tap for details." Visibility prevents
  the "did my kid's phone change without me knowing?" anxiety.

---

## 8. Update over store-and-forward

For v2 (post-Iroh integration; see
[`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md)), the parent device
can push a signed APK to a child phone over the existing sync
transport. Useful when:

- The child phone is on a network the F-Droid client can't reach
  (school Wi-Fi blocking F-Droid mirrors, captive portal, cellular
  with no GitHub Releases connectivity).
- A crisis update needs to land *now* and the child is on Wi-Fi only
  via the parent's hotspot.

Mechanics:
- Parent device downloads the APK + manifest from GitHub Releases /
  F-Droid (whichever is reachable).
- Parent app verifies signature + hash before transmission.
- Streams over the same transport that carries `PolicyBundle` (Iroh
  in v2; LAN/mDNS bulk transfer with chunked range requests).
- Child DPC verifies signature + manifest hash again on receipt;
  installs via `PackageInstaller` only if both pass.

The child never trusts the parent's verification — every check
happens on both ends. The store-and-forward layer is a *delivery*
channel, not a trust channel.

---

## 9. Failure modes

What can go wrong, and what catches it.

- **Bad update bricks the child.** Android apps don't get A/B
  partitions like the OS, but the DPC ships a **signed rollback
  bundle** with each release: a one-back-version APK + manifest. If
  the DPC fails to start three times in a row post-update (detected
  via boot-watchdog), it installs the rollback bundle automatically.
  Rollback bundles are signed by the same release key; the same
  verification gate applies.
- **Signing key compromise.** Procedure in §4.2 +
  [`DISTRIBUTION.md`](DISTRIBUTION.md) §9. Revocation list pushed to
  every child DPC on next sync; key-rotation manifest dual-signed
  by old + new so legitimate installs can verify the transition.
- **F-Droid build server down.** GitHub Releases is always the
  fallback. F-Droid downtime is not a blocker — only the slowest
  user-visible signal.
- **GitHub down at release time.** Mirror to Codeberg as a static
  fallback (cheap, no auth, no maintenance burden). Manifest URLs
  are hard-coded to GitHub; an outage means parents see "update
  check failed," not silent corruption.
- **Manifest signature verifies but APK download corrupted
  mid-transfer.** Hash mismatch catches it at step 3 of §3. Re-fetch
  with exponential backoff (max 3 attempts), then give up and
  surface to parent.
- **Old child DPC behind a major version.** §11 covers the
  compatibility window; outside it, child enters stale-policy mode
  ([`PROTOCOL.md`](PROTOCOL.md) §5) and the parent app shows "child
  needs update" prominently.

---

## 10. Versioning policy

SemVer 2.0.0, with the wire-protocol mapping below:

- **MAJOR.** Breaking protocol change (canonicalization swap,
  signature suite change, hash algorithm migration). Per
  [`PROTOCOL.md`](PROTOCOL.md) §8: requires re-pair. No "v1.5
  transitional" path.
- **MINOR.** New feature, backwards compatible. New optional fields
  in payloads, new transports, new policy attributes — all minor.
- **PATCH.** Bug fix, security fix, doc updates. Crisis updates are
  always PATCH bumps unless the fix requires a schema change.
- **Pre-release.** `alpha.N` (internal), `beta.N` (Beta channel),
  `rc.N` (Stable-candidate). Pre-release builds are never signed by
  the production release key; we use an ephemeral development key
  with a published "not for production" cert pin.

---

## 11. Backwards compatibility

The child accepts policy bundles from a parent within the same MAJOR
version. Within a MAJOR, the child supports the **N-2 minor window**:
a child on v1.4 accepts bundles from a parent on v1.2 through v1.6.
Outside that window the child enters stale-policy mode and the
parent app shows a prominent "Update your phone" interstitial on the
child's home screen and in the parent's Family Feed.

PolicyDoc carries the `v` field; jumps require migration logic in
the child DPC. Migration is **forward-only** — a v2 child cannot
read v1 bundles, and downgrading the child DPC is blocked at install
time by the manifest signature check (we never sign downgrades
without an explicit rollback bundle, which is itself version-pinned).

The parent app warns 30 days before any drop of compatibility for an
older minor: "Your child's phone is on v1.2; v2.0 ships in 30 days
and will require an update first."

---

## 12. Audit + transparency

- **Public changelog** for every release. Tier-grouped per
  [`SIMPLIFY.md`](SIMPLIFY.md).
- **Sigstore-signed release notes.** `cosign sign-blob` with the
  maintainer's OIDC identity; Rekor URL in the release page. No
  long-lived PGP infrastructure.
- **CycloneDX 1.5 SBOM** per artifact, auto-generated by CI.
- **CVE disclosure:** 90-day coordinated disclosure standard,
  shortened to "as fast as we can sign and ship" for actively
  exploited vulnerabilities. The clock starts at receipt of the
  report, not at fix availability.
- **CI logs are permanent and public.** Every release's pipeline,
  including failures, is on GitHub Actions forever. We do not delete
  failed runs.

---

## 13. Pre-September-2026 sideload window

Google's developer verification requirement kicks in September 2026.
After that date, Android sideloads from unverified developers face
escalating warnings and eventual Play Protect blocks. Our plan:

- **F-Droid main repo registration by July 2026** — two months of
  buffer. Reproducible-build spike by March 2026; metadata submission
  April–May; F-Droid inclusion lands by July.
- **Backup plan A:** register as a Google-verified developer. We do
  not want to. We will if F-Droid stalls. Fee from grant funds, not
  user payment.
- **Backup plan B:** F-Droid Archive repo (less strict than main).
- **Backup plan C:** IzzyOnDroid or Aurora alternative repos.
- **What we will not do:** ship a Play-Store-only required path. The
  Tier-3 ban in [`SIMPLIFY.md`](SIMPLIFY.md) §2 is non-negotiable.

Quarterly status updates between now and Sept 2026 on the project
blog. If the timeline slips, parents will know early. F-Droid
becomes our **primary** distribution path post-Sept-2026 regardless
of how the Google policy shakes out.

---

## 14. iOS update path

- **TestFlight (v1).** Parent app only. Updated via the TestFlight
  client; Apple handles delivery.
- **App Store (v2 onward).** Standard auto-update.
- **Crisis on iOS.** Same channel as Stable (TestFlight or App
  Store); we request expedited review with the CVE attached. Apple
  has historically honored these in 24–48 hours.
- **$99/yr Apple Developer Program fee.** The only paid line item in
  the project, documented in [`DISTRIBUTION.md`](DISTRIBUTION.md)
  §2 and [`FUNDING.md`](FUNDING.md). No subscription substitutes,
  no second paid line item.

---

## 15. Audit trail

Visible inside both apps; signed in the log.

- **Parent app:** Settings → Updates → History. Shows version,
  install date, SHA-256, source (F-Droid / GitHub / store-and-forward),
  and parent who approved (if explicit consent).
- **Child app:** About → Software. Shows current version, install
  date, and last update attempt's outcome.
- **Family Feed:** "Oliver's phone updated to v1.2.4" entry with
  manifest hash and changelog link.
- **Rollback events** are logged with the trigger reason
  (boot-watchdog failure count, manual parent rollback, etc.).
- **At-a-glance answer to "what version is Oliver running?"** — top
  of the parent app's child detail screen, always visible.

The audit log is local-only, per
[`SECURITY.md`](SECURITY.md) §Privacy. Update history never leaves
the parent or child device.

---

## 16. Testing strategy

- **Per PR:** full unit + integration test suite in CI. Reproducible
  build check (§6) runs on every PR touching `androidApp/` or
  `child-android/`.
- **Emulator end-to-end:** Pixel 7 system image, scripted pair → sync
  → policy-apply → update flow. Captures regressions in the OTA
  install path specifically.
- **Bench Pixel 7 smoke test** before every release. One physical
  device, one human running through the Stage 1 checklist
  ([`ROADMAP.md`](ROADMAP.md)). No CI substitute for "did the kid's
  phone actually install the new APK and reboot cleanly?"
- **Beta channel soak:** Stable candidates sit on Beta for 7 days
  before promotion. Crisis updates skip this and we accept the risk.
- **Negative testing:** signed-but-corrupt APK, signature
  mismatch, manifest tampering, expired key, revoked key,
  downgrade attempt. All must fail closed at the verification gate
  ([`PROTOCOL.md`](PROTOCOL.md) §10.8 fail-closed posture extends
  here).

---

## The shortest version

- Three channels: Stable (default, monthly), Beta (parent opt-in),
  Crisis (CVE fast-path).
- F-Droid + GitHub Releases on Android; TestFlight then App Store on
  iOS. No Play Store, no OpenWarden-operated update server.
- Cold-storage YubiKey root, annual rotation, dual-sign transition,
  cross-city backup.
- Every update verifies signature + manifest + hash before
  install; reproducibility verifies anyone can rebuild.
- Crisis updates skip Beta; everything else waits for the 03:00
  quiet window.
- Auto-rollback on boot-watchdog failure; key-revocation list pushed
  on every sync.
- The grant pays for one Apple Developer Program fee. That's the
  whole money story.
