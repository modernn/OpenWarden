# Distribution — How Parents Get OpenWarden

> **Audience:** maintainers shipping releases, contributors writing CI,
> parents wondering "is this safe to install?", auditors verifying that
> what we publish is what we built.
>
> **Companion docs:** [`ONBOARDING.md`](ONBOARDING.md) is what parents
> see when they install; this doc is what gets them the installer in the
> first place. [`PROVISIONING_V2.md`](PROVISIONING_V2.md) §5 chose the
> Desktop GUI Provisioner; this doc covers how that Provisioner is
> built, signed, and distributed.
> [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) §7–§8 covers the
> iOS pipeline and the F-Droid reproducible-build constraints; this doc
> connects them into a release process.
> [`SIMPLIFY.md`](SIMPLIFY.md) §3 caps the paid line items at one;
> this doc is where that single line item is documented in detail.

Two truths shape every decision here:

1. **No subscription, ever** (project pledge,
   [`README.md`](../README.md)). One Apple Developer Program license at
   $99/yr is the only money out the door.
2. **Google's developer verification requirement kicks in September
   2026.** After that, Android sideloads from unverified developers
   become user-hostile. We must be on F-Droid before that date, or have
   a backup plan, or both.

The rest of this doc is the execution plan.

---

## 1. Channels by version

### v1 (today through Sept 2026)

- **Parent app (Android):** signed APK on GitHub Releases. SHA-256 in
  release notes. SBOM next to the APK. Reproducible-build instructions
  in the same release.
- **Parent app (iOS):** TestFlight, public invitation link. 90-day test
  windows reset on every build push; we cut a TestFlight build at
  minimum every 60 days to stay ahead of expiry.
- **Provisioner (laptop tool):** signed binaries for macOS (notarized),
  Windows (Authenticode-signed), and Linux (signed tarball + .deb +
  .rpm). All three on the same GitHub Release as the parent APK.
- **Child DPC APK:** also on GitHub Releases. Parents do *not* download
  this directly. The Provisioner downloads it on demand and verifies
  the SHA-256.

### v2 (Sept 2026 onward — target)

- **Parent app (Android):** F-Droid main repo, with reproducible builds
  verified by the F-Droid build server. Same APK still on GitHub
  Releases for direct download.
- **Parent app (iOS):** App Store. TestFlight remains for beta tracks.
- **Provisioner:** unchanged distribution path. Adds a Homebrew tap
  for macOS, a Chocolatey package for Windows, a Flathub entry for
  Linux.
- **Child DPC APK:** F-Droid + GitHub Releases. SHA-256 in F-Droid
  metadata too.

### v3 (aspirational — late 2027+)

- **Parent app (iOS), FOSS-purist path:** AltStore or sideloadly
  variant for users who refuse the App Store. 7-day cert refresh
  documented. We do not endorse a specific third-party sideload
  helper, but we publish the unsigned `.ipa` so users can re-sign
  with their own developer account.
- **Self-hosted relay node** package for parents who want to run a
  OpenWarden relay on a $5 VPS. (Tier 2 per [`SIMPLIFY.md`](SIMPLIFY.md).)

The v1 → v2 transition is the critical one. v3 is aspirational and
should not delay v2.

---

## 2. Signing keys

Three keys, three storage strategies. None of them ever lives on a
networked machine for more than the few minutes of a signing operation.

### Child Android APK signing key (DPC release key)

- **Type:** RSA-4096, generated on an air-gapped Tails or NixOS
  machine.
- **Storage:** offline. Two copies on USB sticks in fireproof
  envelopes; one printed paper backup (BIP39-style word list of the
  PKCS#8 bytes) in a bank safety deposit box. Documented in detail
  in [`RECOVERY.md`](RECOVERY.md).
- **Use:** sign the DPC APK on a clean machine for each release.
  Plug USB, sign, unplug, verify, publish.
- **Rotation:** major version only (v2 → v3). Pre-rotation, the new
  key is announced in two consecutive minor releases; the parent app
  warns "key rotation coming in v3.0" on launch.
- **If compromised:** see §9 (crisis distribution). Short story: we
  burn the key, ship a new release signed with a fresh key, and
  push a hard-update banner.

### Parent Android KMP APK signing key

- Same as the DPC key: RSA-4096, offline, two USB copies + paper.
- **Separate from the DPC key.** A compromise of one should not
  cascade. The parent app signing key is used at most once per
  minor release; the DPC key is used the same frequency.

### iOS signing identity (Apple Developer Program)

- The single paid line item in the project: **$99/year**.
- Identity stored in the maintainer-of-record's macOS keychain
  with a strong passphrase. CI uses an ephemeral signing identity
  via Fastlane Match's `readonly` mode, with the cert PEM stored
  in a 1Password vault accessible only to the release maintainer.
- This is in deliberate tension with the FOSS pledge. We
  acknowledge it explicitly in §11 below, and reference that
  acknowledgment in [`README.md`](../README.md) and
  [`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) §7.

### Provisioner signing

- **macOS:** notarized via `notarytool` with the same Apple
  Developer ID used for the parent app. No additional cost.
- **Windows:** Authenticode cert from a community-friendly CA
  (SignPath.io offers free OSS signing; we use that). Free.
- **Linux:** detached PGP signature using a project key checked
  into Sigstore Rekor. No long-lived PGP infra.

---

## 3. Reproducible builds

A parent should be able to take our source, build it on their machine,
and get **byte-identical** APKs to the ones we publish. This is the
F-Droid main-repo bar, and it's the bar we hold ourselves to even
where F-Droid doesn't require it.

### Android (parent + child)

Setup (per
[`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) §8):

- `gradle.properties`: `org.gradle.parallel=false`,
  `org.gradle.caching=false` for release builds.
- JDK: **Temurin 21 LTS**, exact version pinned in
  `metadata.yml` for F-Droid and in `.tool-versions` for asdf users.
- Gradle: 8.11, exact, wrapper checked in.
- Android SDK: API 35, build-tools 35.0.0, exact.
- All timestamps stripped:
  `tasks.withType<Jar> { isPreserveFileTimestamps = false;
  isReproducibleFileOrder = true }`.
- libsodium built from a pinned source tarball (SHA-256 verified)
  via `androidApp/native/build-libsodium.sh`. Output `.so` files
  are deterministic across builds on the same host arch.

Verification (in CI, every release):

```bash
./gradlew :androidApp:assembleRelease
SHA1=$(sha256sum androidApp/build/outputs/apk/release/app-release-unsigned.apk)
./gradlew clean
./gradlew :androidApp:assembleRelease
SHA2=$(sha256sum androidApp/build/outputs/apk/release/app-release-unsigned.apk)
[ "$SHA1" = "$SHA2" ] || { echo "NON-REPRODUCIBLE BUILD"; exit 1; }
```

CI runs the same comparison against a `diffoscope` artifact diff for
human-readable failure analysis.

### iOS

iOS reproducibility is harder (Xcode embeds build timestamps,
machine UUIDs, and ad-hoc randomness in archives). We don't claim
byte-identical iOS reproducibility for v1. We do claim:

- **Source reproducibility:** anyone can clone the repo and build
  an `.ipa` from the same source tree.
- **SHA-256 of the source tarball** is in the release notes.
- **No binary-only dependencies.** Every Swift Package consumed by
  `iosApp/` is open source with a pinned commit. SKIE is open
  source. The KMP `:shared` framework is generated from our source.

This is the same posture as Signal-iOS and other FOSS iOS projects.
We will revisit when Apple offers genuine reproducible Xcode builds.

### Provisioner

- Python script frozen with PyInstaller, single-file output per
  platform.
- PyInstaller's `--bootloader-ignore-signals` and a pinned Python
  version (3.12.x) get us 95% reproducibility; the residual delta is
  PyInstaller's archive timestamp, which we patch out with
  `strip-nondeterminism` post-build.
- Source-build path is documented in `provisioning/README.md`; any
  parent with Python can skip our binaries and run from source.

---

## 4. Release workflow

A release is a tag + a CI run + a human approval gate. No release
ever happens by clicking around a UI; every release is reproducible
from the tag alone.

### Steps (executed by CI on tag push)

1. **Tag.** Maintainer pushes `v1.2.3` from `main` after the
   release checklist (see §5) is green.
2. **CI: prepare.** GitHub Actions checks out the tagged commit,
   restores the pinned JDK / Android SDK / Xcode toolchain.
3. **CI: build child DPC.** `./gradlew :child-android:assembleRelease`
   → unsigned APK + SHA-256 + SBOM.
4. **CI: build parent Android.**
   `./gradlew :androidApp:assembleRelease` → unsigned APK +
   SHA-256 + SBOM. Reproducibility check (§3) runs here.
5. **CI: build parent iOS.** `xcodebuild archive` →
   unsigned `.xcarchive` + `.ipa` + SBOM. Source tarball SHA-256
   computed.
6. **CI: build Provisioner.** PyInstaller for each platform,
   SHA-256 + SBOM.
7. **CI: pause for signing.** CI uploads unsigned artifacts to a
   release-staging GitHub Release marked as draft. Maintainer
   receives a signal-bot ping.
8. **Human: sign.** Maintainer plugs USB key on the air-gapped
   signing machine, signs each Android APK with the matching key,
   signs the iOS build via Fastlane, signs the Provisioner binaries.
   Computes SHA-256 of signed artifacts. Uploads signed artifacts
   back to the draft release.
9. **CI: verify.** Verifies each signature against the published
   pubkey/cert, verifies SHA-256s match the release notes draft.
10. **CI: publish.** Promotes the draft release to public. Pushes
    F-Droid metadata to our F-Droid manifest fork. Submits the iOS
    build to TestFlight (or App Store in v2).
11. **CI: announce.** Posts to the project's discussion forum and
    ntfy channel.

### Release notes contents (per release)

- Version, date, signing key fingerprint used.
- SHA-256 of every artifact (parent Android, child DPC, parent iOS,
  Provisioner per platform).
- SHA-256 of the source tarball at the tagged commit.
- Sigstore Rekor URL for the signed release notes.
- Reproducibility instructions (copy-paste shell commands).
- SBOM links (CycloneDX JSON).
- "What changed" summary, grouped by Tier per
  [`SIMPLIFY.md`](SIMPLIFY.md).
- Security advisories, if any (also published as GHSA).
- Upgrade-from-prior-version notes.

### Sigstore signing of release notes

We do not maintain long-lived PGP infrastructure. Instead, the
release notes themselves are signed via Sigstore's `cosign sign-blob`
with the maintainer's OIDC identity at sign time. Verification:

```bash
cosign verify-blob \
  --certificate release-notes-v1.2.3.pem \
  --signature release-notes-v1.2.3.sig \
  --certificate-identity-regexp '@openwarden\.org$' \
  --certificate-oidc-issuer https://accounts.google.com \
  release-notes-v1.2.3.md
```

The Rekor transparency log makes this auditable without us running a
keyserver.

---

## 5. Update strategies

### Android child DPC

- Updates ship as a new signed APK with a matching key.
  `PackageInstaller` auto-updates on signature-match, no user
  interaction.
- Parent app pushes an update notification when a new DPC version
  is available; the DPC self-updates on next sync.
- F-Droid users get auto-updates via F-Droid client (opt-in per
  package).
- Manual sideload remains the fallback path forever.

### Android parent KMP app

- F-Droid auto-update (v2 onward) or manual sideload.
- The parent app self-checks for updates against the GitHub
  Releases API on launch (opt-in, off by default), and surfaces a
  non-modal banner if a new version is available.
- We will **not** push updates. The parent decides when to update.

### iOS parent app

- TestFlight pushes the build; tester opens TestFlight and taps
  Update. There is no auto-update on iOS for TestFlight.
- v2 App Store: standard App Store auto-update.

### Provisioner

- Manual re-download. The Provisioner runs at most once per kid
  phone; auto-update is unnecessary.
- The Provisioner self-checks for updates on launch and warns if
  it's > 90 days old.

---

## 6. The pre-September-2026 sideload window

Google's developer verification requirement starts September 2026.
After that date, an Android device installing an APK from an
unverified developer will see escalating warning dialogs and may
ultimately be blocked by Play Protect.

**Our plan:**

- **Register on F-Droid by July 2026.** Two months of buffer before
  Sept 2026. The F-Droid build server's verification of our
  reproducible build is part of the registration. Plan: spike the
  reproducible build by March 2026, submit metadata in April–May,
  F-Droid inclusion lands by July.
- **Backup plan A: register as a verified Google developer.** This
  requires legal entity, ID verification, and an annual fee. We do
  not want to be a Google-verified developer, but we will be one
  if F-Droid stalls. The fee comes from grant funds, not from any
  user payment.
- **Backup plan B: F-Droid Archive repo.** Less strict than F-Droid
  main; accepts non-reproducible builds. Slower-updating but a
  legitimate fallback.
- **Backup plan C: independent app store** like Aurora or
  IzzyOnDroid. These do not require Google verification but have
  smaller user bases.
- **What we will not do:** ship a Play-Store-only build to avoid
  the verification problem. The Play Store path requires Google
  Play Services in the manifest, which we reject by design.

We will publish a status update every quarter from now until Sept
2026 on the project blog with the F-Droid timeline. If the timeline
slips, parents will know early.

---

## 7. Audit + transparency

The whole project is auditable; the release pipeline is no
exception.

- **CI logs are public.** Every release's CI run, including failure
  output, is permanent on GitHub Actions. We do not delete failed
  runs. Failures are part of the public record.
- **SBOMs.** Every release ships a CycloneDX JSON SBOM next to the
  artifacts. Generated by `cyclonedx-gradle-plugin` (Android) and
  `cyclonedx-cli` (iOS).
- **Dependency policy.** All deps must be Apache 2.0 / MIT / BSD /
  ISC / MPL-2.0 compatible at the file level. **No GPL** (we ship
  on Apple's App Store eventually, and Apple's distribution terms
  conflict with GPL). Compliance checked by the
  `dependencyLicenseReport` task in CI.
- **Public bug bounty:** **not in v1.** A bounty program needs
  budget, triage capacity, and legal scaffolding we don't have at
  v1 scale. We will revisit for v2 with grant funding.
- **Independent security audit:** budgeted in the v2 grant request.
  Target: an audit of the DPC, the protocol, and the signing
  pipeline by a recognized cryptography-and-mobile-security firm.
  Findings published in [`SECURITY.md`](SECURITY.md) §audit-2026
  (or whenever it happens).

---

## 8. Verifying a release as a parent or auditor

You don't need to be a developer to verify what you installed
matches what we published.

### Android (parent or child APK)

```bash
# Download APK from GitHub Releases
curl -LO https://github.com/openwarden/openwarden/releases/download/v1.2.3/openwarden-parent-v1.2.3.apk

# Compare SHA-256 to the release notes value
sha256sum openwarden-parent-v1.2.3.apk

# Verify the APK signing certificate fingerprint matches our published key
apksigner verify --print-certs openwarden-parent-v1.2.3.apk
# Expected SHA-256 fingerprint: <pinned in our keys.md>
```

### iOS (.ipa)

```bash
# Verify Apple's notarization
xcrun stapler validate openwarden-parent-v1.2.3.ipa

# Check the embedded signing identity
codesign -dvvv openwarden-parent-v1.2.3.ipa
# Expected Team ID: <pinned>
```

### Source tarball

```bash
git clone https://github.com/openwarden/openwarden.git
cd openwarden
git checkout v1.2.3
git tag --verify v1.2.3
# Uses Sigstore-signed tag; verify via cosign as in §4.
```

### Reproducible-build verification

```bash
./gradlew :androidApp:assembleRelease
# Compare the resulting unsigned APK SHA-256 to the
# "Unsigned APK SHA-256" field in the release notes.
```

If any of these checks fail, do not install. Open a GitHub Discussion
and we will investigate within 24 hours.

---

## 9. Crisis distribution

Security incidents need a fast path. The plan:

### Hot patch (critical CVE)

1. Maintainer-on-call triages the report (target: 4 hours).
2. Fix branched off the latest release tag, reviewed by at least
   two maintainers (target: 24 hours).
3. CI runs the full release workflow (§4) with `SECURITY_HOTPATCH=1`
   skipping non-essential builds (the iOS path takes longest;
   Android hot-fix can ship in <2 hours).
4. Release tagged `v1.2.4-security` and signed.
5. In-app banner pushed to all parent apps on next sync:
   "Security update available. Update now."
6. Email to anyone who has subscribed to security updates
   (opt-in mailing list at `security-announce@openwarden.org`).
7. ntfy.sh public channel post.
8. GitHub Security Advisory (GHSA) published with CVE number.
9. Signal broadcast to the project's official Signal channel.
10. Post-mortem in [`SECURITY.md`](SECURITY.md) within two weeks.

### Compromised signing key

This is the worst case. The procedure:

1. **Immediately revoke** the compromised cert via the relevant
   transparency log (Rekor for code-signing) and via Apple's
   developer portal (iOS).
2. **Generate fresh key** on the air-gapped signing machine.
3. **Publish key-rotation notice** with the new pubkey fingerprint
   signed by *both* old (one last time) and new keys, so existing
   parent apps can verify the rotation message.
4. **Release `v1.x.x-rekey`** signed by the new key. Parent app
   shows an interstitial: "OpenWarden's signing key has rotated.
   Re-verify the new key fingerprint at <URL> before updating."
5. **Force re-verification** on next install: the parent app
   refuses to apply policy updates from any source until the
   parent re-verifies the new key fingerprint by reading aloud
   the BIP39 phrase representation.

This is heavyweight. It should never happen. If it does, the recovery
exists.

### Notification channels (in priority order)

- In-app banner (fastest, but requires the parent to open the app).
- Email to opt-in security list.
- ntfy.sh public channel (anyone can subscribe).
- Signal broadcast channel.
- Mastodon post on the project account.
- GitHub Security Advisory.
- HackerNews / Reddit (last resort).

We do not own a push notification pipeline (see
[`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) on the
iOS no-APNs decision). That choice means crisis comms is best-effort
in the worst case. Parents should subscribe to at least one of the
channels above.

---

## 10. SBOM and dependency hygiene

- SBOMs ship as CycloneDX 1.5 JSON next to each artifact.
- Generated automatically by CI; the maintainer never edits them by
  hand.
- Dependency-Track instance (Tier 2, post-v1) will host SBOMs and
  alert on CVEs in our deps.
- Pre-v1.0, we run `gradle dependencyCheck` and
  `cargo audit`-equivalent for any Rust deps (we don't currently
  have any) in CI on every PR.

---

## 11. The TestFlight tension, acknowledged

This is the most-asked question after "is OpenWarden really free?".

**Why we use TestFlight (and eventually the App Store) at all:**

- iOS has no equivalent of Android sideload that is friendly to
  non-tech parents. AltStore requires Mac, a 7-day cert refresh,
  and trust in a third-party signing service.
- Apple Push Notification Service (APNs) requires an Apple Developer
  Program license. Without APNs, the v1 parent iOS app already has
  to fall back to "open the app" sync; the v2 ntfy.sh doorbell
  improves this but isn't push-grade.
- The user base of iOS parents is real. We will not abandon them.

**What this costs:**

- $99/yr Apple Developer Program fee. Paid from grant funds. Logged
  in [`FUNDING.md`](FUNDING.md).
- A long-lived relationship with Apple's distribution terms, which
  conflict with some FOSS licenses (GPL specifically; Apache 2.0 is
  fine).
- TestFlight builds expire after 90 days; users must reinstall
  periodically.

**What's closed and what isn't:**

- The **OpenWarden source code is fully open** under Apache 2.0.
  Anyone can clone it, build it, sign it with their own Apple
  identity, and run it on their own device.
- The **distribution channel** (TestFlight, App Store) is closed by
  Apple, not by OpenWarden. We have no influence over Apple's
  policies, and we explicitly cede that they could refuse to
  distribute OpenWarden in the future. If that happens, the FOSS-purist
  iOS path (§v3) becomes the primary iOS path overnight.

**We will not:**

- Add a second paid line item to "fix" this.
- Try to be cheaper by skipping the iOS audience.
- Pretend the tension doesn't exist.

The acknowledgment is in [`README.md`](../README.md) and in the
parent app's About screen. New users learn about it before they
install. That is the most we can do.

---

## 12. The shortest version

- v1: GitHub Releases (Android), TestFlight (iOS).
- v2 (Sept 2026): F-Droid (Android), App Store (iOS).
- One paid line item: $99/yr Apple Developer Program.
- Every release is signed and reproducible.
- Every parent can verify what they installed.
- Crisis comms: in-app banner + ntfy + email.
- If something looks off, file a GitHub Discussion. We answer.
