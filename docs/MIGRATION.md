# Migration — Device Transitions, Hand-Downs, and Decommission

> **Audience:** parents preparing for a phone upgrade or hand-down,
> maintainers who own the migration wizard, support volunteers fielding
> "kid got a new phone for their birthday" questions.
>
> **Companion docs:** [`PROVISIONING_V2.md`](PROVISIONING_V2.md) is the
> ground truth for any new-device setup invoked below.
> [`CRYPTO.md`](CRYPTO.md) §1-2 defines the key inventory and the BIP39
> root that makes parent migration possible.
> [`RECOVERY.md`](RECOVERY.md) §5 (R1) is the precise procedure for
> restoring a parent identity. [`FAMILY_MODEL.md`](FAMILY_MODEL.md) §2
> is why parent identities re-derive from the family root but child
> identities do not. [`OTA.md`](OTA.md) §11 governs version compatibility
> across mixed-version family graphs during the migration window.

**Locked product decisions:**

- Child device identity keys are **never migrated**. A new kid phone
  always means a new StrongBox keypair; the parent's pinned record is
  updated by a parent-signed Pair Migration token, never by exporting
  the old private key.
- Parent identity keys **always re-derive from the BIP39 phrase**. No
  cloud sync, no peer-to-peer key transfer.
- Every migration is a first-class signed event in the co-parent feed.
- Decommission is a single code path used by graduation, hand-down,
  loss replacement, and pre-repair surrender.

---

## 1. Scenarios

Seven supported transitions plus one explicitly unsupported one. The
wizard in §13 funnels each into a small set of code paths.

| # | Scenario | Code path | Typical wall-clock |
|---|---|---|---|
| 1 | Kid upgrades (Pixel 7 → Pixel 9) | §2 kid migration | ~1 hour |
| 2 | Parent upgrades phone | §3 parent migration | ~10 min |
| 3 | Sibling hand-down (older to younger) | §4 hand-down | ~45 min |
| 4 | Kid graduates at 18 | §5 graduation | 7-day delay + ~5 min |
| 5 | Parent + kid upgrade same day | §2 + §3 chained, parent first | ~75 min |
| 6 | Lost phone (parent or kid) | §6 lost | parent ~10 min; kid ~1 hour |
| 7 | Phone going to repair shop | §7 repair | 24h delay + ~5 min |
| 8 | Custody change between parents | §12 surrender | 7-day delay + ~10 min |

There is intentionally no "side-by-side clone" path. Cloning a child
device would mean exporting a StrongBox-wrapped key, which the
hardware refuses by design and which would defeat per-device attestation.

---

## 2. Kid phone migration (Pixel 7 → Pixel 9)

The old phone has Device Owner, FRP bound to the parent account,
signed policy bundles, an encrypted event log, the apphand allowlist,
and the parent's pinned pubkey. The new phone has nothing. The parent
has the recovery phrase memorized or printed (per
[`RECOVERY.md`](RECOVERY.md) §3) but does not need it for routine
migrations — only for §6 and §8.

**Procedure** (parent app drives the whole thing):

1. **Backup on the old phone.** Parent taps "Migrate Oliver's device"
   in the parent app. The old kid device, on receiving a signed
   `migration_prepare` command, exports the event log (already encrypted
   to the parent's X25519 pubkey, per [`CRYPTO.md`](CRYPTO.md) §6), the
   active PolicyDoc (already parent-signed, just opaquely copied), and
   the current trust level. These three blobs ride back to the parent
   app over the existing paired channel. No new keys, no plaintext
   logs ever cross the wire.
2. **Factory state on the new phone.** Unbox Pixel 9 or `fastboot -w`
   if it was previously used (and the bootloader is unlocked, which on
   a sealed Pixel 9 from Google it isn't — so unbox).
3. **Run provisioning.** Re-execute the full
   [`PROVISIONING_V2.md`](PROVISIONING_V2.md) state machine S0→S10
   against the new device. The FRP email is the same parent Google
   account as the old phone.
4. **Pair with the same parent.** During S8, the new device generates
   a fresh **StrongBox EC P-256 device-binding key** that attests + signs
   its TEE-resident Ed25519 + X25519 identity keys (per
   [`CRYPTO.md`](CRYPTO.md) §3, [ADR-032](adr/032-child-identity-hardware-binding-strongbox-p256.md);
   StrongBox cannot hold Curve25519). The parent app
   recognises this is a migration QR, not a fresh-pair QR, by the
   `migration_intent` field carried in the QR payload alongside the
   pinning data.
5. **Apply preserved policy.** The parent app signs a new PolicyDoc
   that carries forward the old allowlist, trust level, birthday/age
   data, and time-bank balance. It re-signs because `policy_seq` starts
   fresh on a new child device and the child pubkey is new.
6. **Replay the event log.** The decrypted event log is re-encrypted
   to the *new* child's pubkey for cache locality (so the kid app can
   show recent history) and replayed into the new device's local store.
7. **Decommission the old phone.** Parent app sends the old device a
   signed `decommission` command. After the standard 7-day notice
   countdown (§5), the old phone calls `clearDeviceOwnerApp`, releases
   FRP, and returns to factory clean. The kid hands it in or it's sold.

Typical wall-clock is dominated by step 3 (provisioning, ~45 minutes
on cellular, ~25 minutes on home Wi-Fi). The crypto-bearing steps add
about 5 minutes total.

---

## 3. Parent phone migration

The parent's Ed25519 and X25519 privkeys live in OS Keystore on the
old phone with `requireUserAuthentication=true` and never leave it.
Migration relies entirely on BIP39 re-derivation.

1. **Install OpenWarden parent app on the new phone.** F-Droid or Play
   Store per [`OTA.md`](OTA.md) §2.
2. **Enter the 24-word recovery phrase.** The app re-runs the
   Argon2id + HKDF-SHA256 derivation from [`CRYPTO.md`](CRYPTO.md) §2
   and rebuilds the exact same Ed25519 and X25519 keypairs.
3. **Announce to paired kid devices.** The new parent device signs a
   `parent_device_migrated` notice with the re-derived parent key. Each
   kid device verifies the signature against its pinned parent pubkey
   (which has not changed — the *device* is new, the *identity* is the
   same), then accepts the new device fingerprint into its
   trusted-device list.
4. **Co-parent feed entry.** The other admin parent sees: *"Mom moved
   OpenWarden to a new phone — Pixel 9, 2026-06-15 14:22 UTC."* No action
   required, but a visible audit trail that K1/K2-style impersonation
   would have to lie about.

Old phone, if still in hand, gets its OpenWarden parent app uninstalled
and Keystore entries cleared. If it's already lost, the re-derive
still works — that's the entire point of BIP39.

---

## 4. Sibling hand-down

Older sibling's Pixel 7 is being handed to a younger sibling. The
device is currently DO-locked and FRP-bound to the parent account, so
this is a controlled transition, not a free-for-all reset.

1. Parent app sends `decommission` to the older sibling's phone (§5
   countdown applies — schedule the hand-down 7 days out).
2. After decommission completes, parent does a `fastboot -w` or a
   Settings-side factory reset (now permitted because DO is cleared).
   FRP demands the parent's Google account, parent signs in, OOBE
   completes clean.
3. Parent re-provisions OpenWarden per
   [`PROVISIONING_V2.md`](PROVISIONING_V2.md) for the younger sibling.
   This creates a **new** child identity, a **new** event log, a **new**
   `kid_id`. No history carries over — the younger sibling does not
   inherit the older one's audit trail, which would be a privacy
   violation against the older sibling.
4. The family root in the parent app is unchanged. Both kids hang off
   the same `family_id` (per [`FAMILY_MODEL.md`](FAMILY_MODEL.md) §1).

---

## 5. Graduation at 18

The kid ages out. OpenWarden's job ends.

1. Parent app shows a graduation prompt within 30 days of the kid's
   18th birthday. Parent confirms with the recovery phrase (this is the
   one routine phrase use OpenWarden ships).
2. The parent app signs a `graduate` command. The kid device starts a
   **7-day countdown** with a persistent notification: *"OpenWarden will
   release this phone to you on 2026-06-22. You can ask your parent to
   cancel."* Either side can cancel during the window; cancellation is
   logged.
3. After 7 days: the DPC calls `clearDeviceOwnerApp`, removes the FRP
   policy, uninstalls the OpenWarden APK, and clears its own data.
4. The full event log is exported in a kid-readable format and handed
   to the kid — it is their data, their right. A redacted audit log
   (timestamps + categories, no message contents) is archived to the
   parent app for completeness.

Graduation and §4 hand-down share the decommission state machine. The
only difference is who owns the device afterward.

---

## 6. Lost phone

- **Parent loses phone.** Follow [`RECOVERY.md`](RECOVERY.md) R1
  exactly: phrase → new phone → re-derive → re-announce to kid devices.
  No kid-side work required.
- **Kid loses phone.** The phone is FRP-bound; a finder cannot factory
  reset and use it without the parent's Google account, which is the
  whole point of FRP. The parent buys a replacement Pixel and runs §2
  kid migration from scratch (no event log to recover — that died with
  the phone). The lost phone, if recovered later, gets `decommission`
  sent to it the next time it touches Wi-Fi, then is wiped.
- **Both lost simultaneously.** Restore the parent first per
  [`RECOVERY.md`](RECOVERY.md) R1, then provision a new kid phone per
  §2 without the export step. The event log is gone; accept it.

---

## 7. Phone going for repair

A repair shop with the phone in hand sees the OpenWarden lock screen and
cannot complete most repair workflows that require entering the OS.
Parents must surface this *before* shipping.

Two options, presented in the wizard:

- **Pre-decommission with 24h scheduled re-enable.** Parent signs a
  `temporary_decommission` command with `expires_at = now + repair_window`.
  OpenWarden releases DO and FRP for the repair window, then re-engages
  on receipt of a `recommission` signed bundle when the device is back.
  Lower trust, simpler.
- **Share recovery phrase with repair shop (NOT recommended).**
  Documented only as an anti-pattern. A repair shop with the phrase
  can impersonate the parent indefinitely.

The wizard hard-codes the 24h delay on `temporary_decommission` so
that a phone yanked out of a parent's hand cannot be repair-decommissioned
on the spot.

---

## 8. Migration cryptography

The atomic primitive is the **Pair Migration token**:

```
{
  "type": "pair_migration",
  "family_id": "...",
  "kid_id": "...",
  "old_kid_pub_ed25519": "...",
  "new_kid_pub_ed25519": "...",
  "new_kid_pub_x25519": "...",
  "new_kid_attestation_chain": [...],
  "ts": "2026-06-15T14:22:00Z",
  "sig": "<parent Ed25519 sig>"
}
```

The parent app produces this during §2 step 5. Co-parent devices
verify it against their pinned parent pubkey; child siblings (in
multi-kid families) verify it the same way. The token is appended to
the family feed and replayed to any device that comes online later,
so a parent device that was offline during the migration learns about
it on the next sync without anything special.

The old kid device does not need to sign anything — it's being
decommissioned. There is no migration handshake between the old and
new kid devices; the parent's signature is the sole authority.

---

## 9. Data continuity rules

What transfers and what doesn't, formalised:

| Data | Transfers? | Why |
|---|---|---|
| Event log | Yes (re-encrypted to new child key) | Kid's history of their own life |
| Allowlist + restriction policy | Yes | Family policy is per-kid, not per-device |
| Trust level | Yes | Trust is earned through behaviour, not hardware |
| Birthday + age | Yes | Trivially true |
| Time-bank balance | Yes | Earned screen time is the kid's |
| StrongBox-resident keys | No | Hardware refuses; per-device attestation is the point |
| `policy_seq` / `cmd_seq` counters | Reset to 1 | New device, new monotonic anchor |
| Heartbeat history | No | Tied to old device hardware ID |
| OS-level data (photos, contacts) | Out of scope | Use Google's transfer tool |

---

## 10. Audit trail

Every migration emits one or more entries to the co-parent feed:

- *"Dad migrated Oliver's phone from Pixel 7 to Pixel 9 on 2026-08-15."*
- *"Mom moved OpenWarden to a new phone (Pixel 9)."*
- *"Decommission scheduled for Oliver's old Pixel 7 — completes 2026-08-22."*
- *"Trust level 3 carried over from the old device."*

Both parents see all of these. A migration that suppresses these
entries is a defect, not a feature — the entire defense against K1
(co-parent impersonation) depends on the migration being noisy.

---

## 11. Cross-OEM migration

A parent moving a child from Pixel 7 to a Samsung Galaxy: technically
supported, with caveats per `ANDROID_COMPAT.md`. The new device may
have weaker StrongBox semantics (some Samsung devices expose
TEE-only Keystore), and the attestation cert chain roots in Samsung's
PKI, not Google's directly.

The parent app shows a one-time warning during S8 of provisioning the
new device: *"This device has weaker hardware attestation than the
previous one. OpenWarden will still work, but a sophisticated attacker
with physical access has a smaller bar to clear."* The warning gates
behind a "I understand" tap; it does not block provisioning. We are
not in the business of telling parents which phones they can buy.

---

## 12. Custody change

A custody change transfers parent admin authority from Parent A to
Parent B. Both options:

- **Joint custody (default).** Both parents remain admins (per
  [`FAMILY_MODEL.md`](FAMILY_MODEL.md) §2). No migration needed;
  nothing changes structurally. Co-parent feed already serves both.
- **Sole custody.** Parent A signs a `surrender_admin` command,
  designating Parent B as the new sole admin. Parent B accepts via QR
  pairing plus recovery-phrase verification — the new admin must
  demonstrate knowledge of the family root, otherwise a custody change
  doesn't actually move the keel. After Parent B accepts, Parent A's
  identity is removed from the kid's pinned-parents list on next sync.

**Death of a parent.** If the surviving parent does not have the
recovery phrase and is not already an admin, the family is bricked.
This is documented in onboarding as "tell your co-parent where the
phrase is." We do not build a corporate-style escrow.

---

## 13. UI flow

The wizard lives at parent app → Settings → **Migrate device**. Three
top-level options:

- **New kid device** → §2 wizard.
- **New parent device** → §3 wizard (also reachable from §6 R1).
- **Decommission** → §4, §5, or §7 wizard depending on reason picked.

Every wizard ends with a **signed command preview** that shows the
exact bytes of the signed token before it goes out, with a
"Confirm" + "Cancel" pair. This is K3 defense-in-depth: a parent who
has been shoulder-surfed into pressing Confirm at least sees what they
just signed.

---

## 14. Test plan

1. **Emulator kid migration.** Provision Pixel 7 AVD per
   [`PROVISIONING_V2.md`](PROVISIONING_V2.md) §10 (1), then run §2
   against a second Pixel 9 AVD. Verify policy, allowlist, event log,
   and trust level all carry over; verify old AVD decommissions clean.
2. **Parent re-derive.** Provision a kid AVD, pair to parent A, wipe
   parent A's Keystore, re-derive on a different parent device using
   the same BIP39 phrase, verify kid AVD accepts the new parent device
   after announcement.
3. **Graduation.** Set kid AVD birthday to 18 years 1 day ago,
   trigger graduation, verify 7-day countdown, fast-forward emulator
   clock, verify `clearDeviceOwnerApp` and FRP release.
4. **Surrender.** Set up a 2-admin family, run `surrender_admin` from
   parent A to parent B with phrase verification, verify pinned-parents
   list updates and parent A loses signing authority.
5. **Lost-phone replay.** Provision, capture state, simulate "lost"
   by destroying the AVD, replace with a fresh AVD using R1, verify
   nothing carries over that shouldn't.
6. **Cross-OEM warning.** Provision against a non-Google-attestation
   device profile, verify the §11 warning appears exactly once and
   does not block.

A v1 release without (1), (2), and (3) green on CI is not a v1.

---

## References

- [`PROVISIONING_V2.md`](PROVISIONING_V2.md) — invoked by §2, §3, §4, §6.
- [`CRYPTO.md`](CRYPTO.md) — §2 BIP39 derivation, §3 StrongBox attestation,
  §6 event log encryption.
- [`RECOVERY.md`](RECOVERY.md) — R1 is §3 of this doc; R2 is §5; R3 is §6.
- [`FAMILY_MODEL.md`](FAMILY_MODEL.md) — `family_id`, `kid_id`, pinned-parents list.
- [`OTA.md`](OTA.md) — version compatibility during mixed-version migration windows.
