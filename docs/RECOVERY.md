# Recovery

Companion to [`ATTACKS.md`](ATTACKS.md) (esp. H6 phrase shoulder-surf, K2 social-engineer parent into using the phrase, K3 physical OPSEC failure) and [`DEFENSES.md`](DEFENSES.md) (esp. defense #15 BIP39 phrase + 7-day time-lock, #20 co-parent feed).

Recovery is the single highest-leverage attack surface in OpenWarden: anything that lets a parent fix a broken phone *also* lets a sufficiently clever 10-year-old fix the phone in a way the parent did not intend. This document specifies what recovery covers, how the phrase is generated and stored, the four flows we support, and the OPSEC scaffolding that keeps K2/K3 from quietly winning.

Locked decisions (do not relitigate in v1):

- BIP39 24-word mnemonic (256-bit entropy).
- 7-day time-locked unlock as the "dad got hit by a bus" backstop.
- Every use of the phrase is logged to the family feed; non-emergency phrase commands are delayed 24 hours and cancelable by any co-parent.
- Apache 2.0 OSS — no closed crypto, no SaaS dependency.

---

## 1. What recovery covers

Four scenarios. R1-R3 are designed for; R4 is documented and pushed onto Google.

- **R1 — Parent device lost or broken.** Parent still has the recovery phrase and access to the FRP Google account. New phone, restore identity keys from phrase, re-pair with child (which already has the old parent pubkey pinned in StrongBox). Common case.
- **R2 — Parent intentionally factory-resets the child device** (kid outgrew it, family selling the phone, device being handed down). Phrase signs a decommission command, OpenWarden releases the FRP policy and clears device-owner, phone returns to clean factory state with no orphaned FRP lock.
- **R3 — Child device factory-reset by a kid attack** (DPC bug, social-engineered parent, hardware unlock). FRP blocks setup-wizard completion until the parent's Google account is entered. The phone stays bricked from the kid's perspective; either the parent re-provisions OpenWarden or the phone is unusable. This is a *defense*, not really a recovery path — we document it so parents understand what FRP buys them.
- **R4 — Parent loses both the phrase AND the FRP Google account.** Phone is permanently bricked from OpenWarden's perspective. Last resort is Google's `support.google.com/android/answer/9459346` proof-of-purchase unlock for the FRP account, using the Pixel serial number printed on the recovery sheet. We don't build this; we tell parents where it lives.

---

## 2. BIP39 generation

- 256 bits of entropy from `SecureRandom` (the platform CSPRNG; on Pixel 7 this is hardware-backed via Titan M2).
- Converted to a 24-word mnemonic using the standard BIP39 English wordlist and BIP39 checksum byte. The checksum is what catches a single-word typo on entry — see §11.
- Implementation reference: `NovaCrypto/BIP39` (Apache 2.0, pure Java, no Bouncy Castle dependency). Wrapped in our Kotlin Multiplatform `recovery` module.
- The phrase is shown to the parent **exactly once** during onboarding. The display screen forces:
  1. Read full 24 words on a non-screenshottable surface (`FLAG_SECURE`).
  2. Confirm by re-typing 6 randomly-chosen indices (e.g. "word #3, #7, #11, #14, #19, #22"). Random indices defeat muscle-memory; a parent who scrolled past without reading will fail.
  3. Generate a printable sheet (§3) before the screen can be dismissed.
- Argon2id (RFC 9106; `m=256 MiB, t=4, p=2`, 64-byte output) is the KDF from the 24 words to the Ed25519 / X25519 seed — the canonical parameters live in [`CRYPTO.md`](CRYPTO.md) §2 (ADR-033 D2). We never use the phrase directly as key material.

---

## 3. Printable PDF design

One sheet, optimized for being legible after 7 years of garage humidity. No logos, no OpenWarden branding — branding makes the sheet OCR-findable in someone's photo roll and increases the chance a houseguest recognizes what it is.

Layout (top to bottom, single-column, black-and-white, plain serif):

- Title: **OPENWARDEN RECOVERY**
- Date generated.
- Family name (parent-chosen label, e.g. "Larson family — Oliver's Pixel").
- **24 words in 2 columns** with line numbers `01`–`24`. Numbering is required: order matters and parents will dictate-and-type at recovery time.
- Below the words: parent's FRP Google account email.
- Below that: child Pixel serial number (for the Google FRP escalation in R4).
- Below that, in a small box: *"Store this in a safe place. If you lose this, Oliver's phone is bricked. There is no online recovery."*
- QR code in the bottom-right encoding all of the above as a single signed payload. Used at recovery time so the parent can scan instead of typing on a phone keyboard for ten minutes.

v2: an optional encrypted PDF variant with a parent-chosen passphrase (separate from the phrase). v1 ships unencrypted because the threat model is "physical theft of paper from a safe-deposit box," not "casual snoop."

---

## 4. Phrase storage best practices

Shown to the parent on the onboarding screen, on the printed sheet, and as a one-time tip the first time they open the parent app post-onboarding.

Recommended:

- Print, laminate, store in a fire-safe or safe-deposit box.
- Tell the co-parent (if any) where it is. Don't tell the child.
- Optional power-user: split via Shamir's Secret Sharing 3-of-5 (`codahale/shamir`, Apache 2.0). Defer the UI to v2 — for v1, document it as an external workflow for users who want it.

Don't:

- Email the phrase to yourself.
- Paste it into a password manager (defeats the purpose of an offline backup; a password-manager compromise now also compromises the child's phone).
- Photograph the sheet (Google Photos cloud sync defeats the purpose).
- Store the phrase digitally in the same Google account that's the FRP account (single point of failure for R4).

The parent app shows one OPSEC tip per week for the first month, then quarterly, until the parent dismisses them.

---

## 5. Recovery flow R1 — parent device lost

1. New phone, install OpenWarden parent app.
2. Tap "Restore from phrase."
3. Type all 24 words. Each word field autocompletes from the BIP39 wordlist (typo defense; the BIP39 checksum byte will *also* reject a wrong word, but autocomplete prevents the frustration of getting through 24 boxes only to discover word #7 was wrong).
4. App runs Argon2id KDF → derives Ed25519 signing key and X25519 box key.
5. **Proof step.** Open child phone, scan its pairing QR. The child still has the old parent pubkey pinned in StrongBox; the new device proves it can sign with the corresponding private key by signing a fresh nonce the child generated.
6. Once child accepts the signature, the new parent device issues a self-rotation command: new Ed25519/X25519 pubkeys signed by the *just-derived* old private key.
7. Child verifies, re-pins the new pubkeys in StrongBox, and the rotation is logged (sealed-box) for the parent.
8. New device fully operational. Old device, if it surfaces, can no longer sign valid commands.

---

## 6. Recovery flow R2 — parent factory-resets child (clean decom)

1. Parent app → "Decommission child device" → enter phrase.
2. App signs a `DEC_COMMAND` with the phrase-derived key (this command type bypasses the 24h delay, see §10).
3. Sends to child over whatever transport is available (LAN, store-and-forward).
4. Child verifies the signature, then in order:
   - `clearDeviceOwnerApp()`
   - `setFactoryResetProtectionPolicy(null)` (releases FRP so the next owner isn't locked out — critical for resale)
   - Prompts the user "Erase all data" — kid or parent confirms.
5. Phone returns to factory state with FRP cleared. Safe to re-provision or sell.

If any step fails, the device stays in its current OpenWarden-enforced state. We don't auto-retry across boots; fail-closed.

---

## 7. Recovery flow R3 — kid wiped the child device

The "recovery" here is mostly *not recovering*. Kid factory-resets somehow (DPC vulnerability, social-engineered parent into running an adb command, hardware path on an unlocked bootloader if FRP defense-in-depth failed). Phone reboots into setup wizard. FRP demands the parent's Google account. Phone is unusable until parent signs in.

Parent's options:

- Sign in. Phone is usable as a stock Pixel, but OpenWarden is gone and must be re-provisioned (factory reset + adb dpm flow from `PROVISIONING.md`).
- Re-provision OpenWarden end-to-end.
- Sell the phone (parent enters Google credentials, releases FRP via Settings, sells clean).

The point of R3 in this doc is to be honest: it isn't a recovery path for the kid's data or state, it's the FRP defense doing its job. We document it so parents reading this doc understand the failure modes.

---

## 8. Recovery flow R4 — parent loses phrase AND Google account

Phone is FRP-locked with no usable account. Google's last-resort path is `support.google.com/android/answer/9459346`: proof of purchase plus the Pixel serial number, submitted to Google support, can release FRP.

We don't implement anything for R4. We make it less likely by:

- Printing the Pixel serial number directly on the recovery sheet (§3).
- Onboarding copy: *"FRP can be unlocked via Google with proof of purchase plus the Pixel serial number. Keep the purchase receipt with the recovery sheet."*
- A one-paragraph FAQ link in the parent app.

---

## 9. 7-day time-locked unlock — the "dad got hit by a bus" backstop

The defining backstop. On the child phone, hold power + volume-down for 60 seconds at the lock screen. This triggers a visible 7-day countdown banner.

Properties (each one defeats a specific attack):

- **Visible countdown banner shows at every unlock during the 7 days.** Kid cannot hide it. Family members notice. Parent (if alive) gets the immediate alert below.
- **Parent app receives an immediate push alert: "Time-locked unlock initiated on Oliver's device."** Any parent device can CANCEL in one tap. If the parent is alive and reachable, this is a 5-second resolution.
- **Cannot be sped up.** Monotonic clock; clock changes are blocked by `DISALLOW_CONFIG_DATE_TIME`; the 7-day timer survives reboots via StrongBox-backed persistent state and is keyed to `SystemClock.elapsedRealtime` deltas plus signed parent timestamps before the parent went silent.
- **Cannot be hidden from the kid.** This is intentional. The kid seeing the countdown is part of how a *family member* notices when the kid initiates it during a slumber party.
- After 7 days uncanceled, child calls `clearDeviceOwnerApp()` and `setFactoryResetProtectionPolicy(null)`, returning the phone to factory.

Use case: parent in a coma. Aunt adopts the kid. Kid needs phone access without a forensic phone unlock or a Google support ticket. The 7-day window is long enough that "parent comes back online" wins almost every realistic case, and short enough that "we have to wait two years" is not the answer for a genuinely orphaned child.

---

## 10. Phrase-use audit + 24h delay

Defeats K2 ("kid social-engineers parent into using the phrase to 'fix' the phone").

- **ANY use of the phrase logs a sealed-box event to the family feed**: who used it (which device), when, and what command was signed.
- **Non-emergency commands signed with the phrase REQUIRE a 24-hour delay** before they take effect on the child. A banner on all parent devices counts down the 24 hours. Any co-parent can cancel.
- **Emergency commands execute immediately** but still log. The emergency set is intentionally tiny: R1 self-rotation, R2 decommission, R4 escalation prep. Everything else — rule changes, child unlocks, app allowlist edits — is non-emergency.
- The 24h delay is what stops the failure mode where a kid hands a flustered parent the phone and says "I have to email my teacher right now, just use the phrase." Even if the parent caves, the co-parent sees the banner and the kid doesn't actually get the unlock for a day.

---

## 11. Phrase entry security

The entry surface is high-value: a successful shoulder-surf here gives a kid the whole phone.

- BIP39 wordlist autocomplete (typo defense).
- BIP39 checksum byte rejects a single-word substitution at submit time, with a helpful "the word at position 7 is likely wrong" error (the checksum doesn't actually tell us which word — but with autocomplete + a misspelling-distance heuristic we can guess).
- Words obscured by default with a per-field "show" toggle. Reduces shoulder-surf surface area.
- **Randomized entry order**: the app asks for word #14 first, then #3, then #21, etc. A shoulder-surfer can't reconstruct the phrase from a partial glance without the index sequence.
- **Biometric lock on the entry screen itself.** Parent fingerprint or face required to even see the entry UI. If the kid grabs an unlocked parent phone and navigates to "Restore," they hit a biometric wall.

---

## 12. Phrase rotation

For when the parent suspects compromise (kid found the printed sheet, sheet was photographed by a houseguest, etc.).

- Rotation requires the OLD phrase plus a new device set up with a NEW phrase.
- Both phrases are derived from independent 256-bit entropy draws; they are not related.
- The flow signs a rotation command with the old key, attests the new pubkey, and on child acceptance the old phrase is voided. Any subsequent use of the old phrase logs an alarm to the family feed and is rejected.
- Documented as the "if you suspect compromise" workflow in the parent app's help section.

---

## 13. PDF generation implementation

Two options considered:

- **Kotlin Multiplatform shared module**: `kotlinx-html` → render via Apache PDFBox (Android) and PDFKit (iOS). Pros: one codepath, fully styled output. Cons: pulls a PDF dependency into the parent app's release binary; iOS PDFKit and Android PDFBox have different glyph-rendering quirks, so we'd need golden-image tests.
- **Plain text + QR + OS print dialog**. Generate a styled in-app view; hand it to the platform's standard "Print" intent. Parent prints to actual paper from whatever printer they have.

**Recommendation: ship the print-dialog path for v1.** No PDF library dependency, no font-embedding bugs, and the output format the parent ends up with (a sheet of paper) is identical. The PDFBox/PDFKit path becomes a v2 option for users who want to save a digital copy for the encrypted-PDF flow.

---

## 14. Test plan

Each test runs on bench Pixel 7 with a known-good provisioning baseline.

- **R1 clean restore.** Provision child, generate phrase on parent A, factory-reset parent A, install on parent B, restore from phrase, verify child accepts new pubkey signature.
- **R2 clean decommission.** Sign DEC_COMMAND with phrase, confirm FRP is released (`setFactoryResetProtectionPolicy(null)` returns success), confirm clean OOBE on next boot with arbitrary Google account.
- **7-day countdown — parent cancels at day 3.** Initiate from child, verify banner visible at every unlock, verify parent alert fires within 30 seconds, cancel from parent, verify countdown is gone on child within one heartbeat interval.
- **7-day countdown — uncanceled.** Initiate, no parent action, advance test clock 7 days (signed parent timestamps must reflect this; do not rely on `DISALLOW_CONFIG_DATE_TIME` bypass), verify child returns to factory state with FRP released.
- **24h delay non-emergency command.** Sign a rule-change with the phrase, verify it does not take effect immediately, verify banner on co-parent device, cancel from co-parent, verify the command never reaches the child.
- **BIP39 checksum on typo.** Type 24 words with one substituted from the wordlist (so it's a valid word but wrong); verify checksum failure with a helpful error.
- **Wrong-position word.** Swap two valid words; verify checksum failure (the BIP39 checksum byte does catch this).
- **Phrase rotation.** Old phrase + new phrase end-to-end; verify old phrase produces "rotation detected, this phrase has been retired" on subsequent use and that the attempt logs to the family feed.
- **R3 documentation check.** Manually factory-reset child via a simulated DPC bug; verify FRP demands parent's Google account at OOBE.
- **Print sheet round-trip.** Generate, print to a PDF (Android print-to-PDF), scan the QR with the parent app, verify the parsed payload exactly matches what the parent app generated.

---

## References

- BIP39 spec — `bitcoin.org` / `github.com/bitcoin/bips/blob/master/bip-0039.mediawiki`
- `NovaCrypto/BIP39` — Apache 2.0 pure-Java BIP39
- Argon2id KDF — RFC 9106
- Shamir's Secret Sharing — `codahale/shamir` (Apache 2.0)
- Google FRP escalation — `support.google.com/android/answer/9459346`
- Pixel hardware unlock / proof-of-purchase docs — Google Pixel support
- libsodium sealed-box (referenced from [`DEFENSES.md`](DEFENSES.md) Pattern B for the audit-log encryption)
