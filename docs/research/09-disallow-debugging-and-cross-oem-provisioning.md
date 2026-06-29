# `DISALLOW_DEBUGGING_FEATURES` Analysis + Cross-OEM Device-Owner Provisioning Matrix

> **Status: research INPUT, NOT canon.** Per [`CLAUDE.md`](../../CLAUDE.md) doc-tier rules and
> [`docs/research/README.md`](README.md): this is raw research, not a decision. **No
> implementation without an ADR.** Anything touching crypto / `proto/` / policy / pairing is
> **agent-blocked** (human + ADR before any code). If this contradicts a canon doc, the canon
> doc wins.
> **Date:** 2026-06-29. **Method:** repo-context read (ADRs, ATTACKS.md, DEFENSES.md,
> PROTOCOL.md, PolicyEnforcer.kt, prior research 03-07) + primary-source review (AOSP javadoc,
> Jason Bayton DPC-allowlist/key-attestation posts, Samsung Knox docs, dontkillmyapp.com,
> Android Enterprise community bug reports). AI-generated; **human-verify before acting.**
> **Informs:** ADR-020 (fail-closed restrictions), ADR-026 (OEM release scope), ADR-027
> (provisioning model), ADR-029 (Tier-2 attestation posture). Does NOT supersede any of them.
> **Issues filed:** #131 (injectable restriction seam), #132 (setUserControlDisabled gap),
> #133 (Play Protect DPC allowlist), #134 (attestation root rotation to P-384), #135 (A55
> Knox Vault correction), #136 (OnePlus FGS Deep Optimization).

---

## Abstract

This report addresses two active engineering questions from the 2026-06-29 session. Thread 1
examines whether `DISALLOW_DEBUGGING_FEATURES` is redundant given other DPC restrictions, and
whether a build-conditional (debug-only) relaxation is safe. Verdict: keep it always-on, it is
not redundant, and relaxing it in any build variant manufactures a fail-open. A secondary
finding is that two belt-and-suspenders measures documented in DEFENSES.md
(`setUserControlDisabled` + `adb_enabled=0` global write) are not implemented in child-android
at the time of writing (filed as issue #132). Thread 2 examines testing and demo strategies
around the adb-dark constraint without weakening the release build, and proposes an injectable
`restrictionFilter` seam (issue #131) as the cleanest long-term solution. Thread 3 provides a
per-brand Device-Owner provisioning matrix covering Pixel, Samsung, OnePlus, Motorola, Nothing,
and Xiaomi under OpenWarden's local-only, no-SaaS constraints, and identifies two new
cross-cutting blockers not captured in the current ADRs: the Google Play Protect DPC allowlist
requirement (issue #133) and the attestation root rotation from RSA to ECDSA P-384 via Remote
Key Provisioning (issue #134).

---

## Thread 1 - Is DISALLOW_DEBUGGING_FEATURES Redundant? VERDICT: keep always-on.

### 1.1 What the restriction actually closes

`DISALLOW_DEBUGGING_FEATURES` (`UserManager.DISALLOW_DEBUGGING_FEATURES`) is applied in
`PolicyEnforcer.requiredRestrictionsForSdk()` at line 326 of
`child-android/app/src/main/kotlin/com/openwarden/child/PolicyEnforcer.kt` (the function
begins at line 322). When set by the Device Owner, Android sets `adb_enabled` to 0 and
prevents the user from re-enabling USB debugging through Developer Options or any Settings
path. This is the **only restriction in the required set that closes the adb console** from
the OS side.

No other restriction in the current required set has equivalent effect:

| Restriction | What it actually blocks | Does it block adb? |
|---|---|---|
| `DISALLOW_DEBUGGING_FEATURES` | USB debugging toggle + `adb_enabled` bit | **Yes -- this is the control.** |
| `DISALLOW_APPS_CONTROL` | Settings/launcher **UI** actions: clear data, force-stop, disable | **No.** AOSP javadoc explicitly notes the user "will still be able to perform those actions via other means (such as adb)." `adb shell pm clear <pkg>` and `adb shell am force-stop <pkg>` succeed with this restriction set and debugging on. |
| `DISALLOW_USB_FILE_TRANSFER` | MTP (Media Transfer Protocol) file browsing via USB | **No.** Does not cover `adb pull` / `adb push` -- those use the ADB daemon, not MTP. |
| `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` | UI consent screen for installing APKs from unknown sources | **No.** Does not block `adb install` / `adb shell pm install`, which bypass the UI consent gate entirely. |
| `DISALLOW_FACTORY_RESET` | Settings -> Factory Reset UI | **No** effect on adb. |
| `DISALLOW_SAFE_BOOT` | Safe-mode boot via long-power-button | **No** effect on adb. |

The implication: removing or relaxing `DISALLOW_DEBUGGING_FEATURES` while keeping every other
restriction would leave `adb shell pm clear`, `adb shell am force-stop`, `adb pull` (data
exfiltration), `adb push` (policy-state tampering), and `adb install` (sideload) all
functional, requiring only USB physical access.

### 1.2 DPC self-protection -- what IS protected and what is NOT

The DPC binary itself has OS-level uninstall protection once it is Device Owner:

- `adb uninstall com.openwarden.child` results in `DELETE_FAILED_DEVICE_POLICY_MANAGER`
- `adb shell dpm remove-active-admin` results in `SecurityException` (the DO cannot be
  removed via adb while active; only a factory reset clears it)

So the **package** cannot be removed via adb. However:

- The **runtime** (PolicyService, FGS watchdog, ApiServer) can be force-stopped via
  `adb shell am force-stop com.openwarden.child` if `DISALLOW_DEBUGGING_FEATURES` is off.
  Once the service is stopped the heartbeat ratchet (ADR-024) stops ticking, the restriction
  re-assert (ADR-021) stops firing, and the child is effectively unmonitored until the next
  reboot or watchdog wakeup.
- The **on-disk DataStore** (policy bundle, pinned pubkeys, replay floor) lives in the app's
  private data directory. With adb and debugging on, `adb pull /data/data/com.openwarden.child/`
  succeeds on a **non-rooted, bootloader-locked** device. An older permissive bundle or a
  crafted one can then be pushed back -- the K1 rollback attack.
- **File exfiltration** of the event log ciphertext is possible via `adb pull`. Content
  confidentiality is preserved (sealed-box encrypted to parent pubkey), but pulling and
  replaying old DataStore state is a real, non-root attack.
- **Sideload floor**: `adb install` works without the UI consent gate when `adb_enabled=1`,
  even when `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` is set.

### 1.3 Adversary model -- why this matters for a 9-13yo

ATTACKS.md section 1 establishes the declared threat: a motivated 9-13yo who "can follow
YouTube + run adb", with physical + USB access, non-root, bootloader-locked Pixel-class device.
This is within the declared in-threat-model envelope.

Two ATTACKS.md-rated attacks become non-root exploitable the moment `adb_enabled=1`:

| Attack | ATTACKS.md rating | Requires adb_enabled=1? |
|---|---|---|
| **K1** -- pull old DataStore, push back permissive policy bundle | **CRITICAL** | Yes. DataStore pull requires adb; no root needed on a non-rooted device. |
| **K2** -- event-log tail-truncation (adb push shorter log, reset `prev_hash` chain) | **HIGH** | Yes. `adb push` to the app data directory. |

Both attacks are currently rated under the assumption that `DISALLOW_DEBUGGING_FEATURES` is
on. Relaxing it promotes both from "requires the restriction to be bypassed first" to "free
given a USB cable." This alone disqualifies any relaxation.

### 1.4 Why a BuildConfig.DEBUG relaxation is rejected

A conditional such as `if (!BuildConfig.DEBUG) add(UserManager.DISALLOW_DEBUGGING_FEATURES)`
manufactures a distinct fail-open variant of the DPC. This violates the project
non-negotiable:

> "Fail-closed. Every error path defaults to MORE restriction, never less." (CLAUDE.md;
> ADR-020 D1)

A debug build with this restriction removed is not an "error path" -- it is a deliberately
weaker variant. The failure mode is real: a CI artifact signed with a debug key that
accidentally ships to a device, or a developer who installs the debug build on the child
device "just to check something." Either event leaves the child device adb-reachable. The
restriction is cheap to keep; there is no performance or dev-ergonomics cost to applying it
in every build variant. REJECTED.

### 1.5 Implementation gap: belt-and-suspenders measures are documented but not implemented

DEFENSES.md row 1 documents `setUserControlDisabled(self, true)` as shipped. ATTACKS.md
line 134 documents an `ADB_ENABLED=0` global write at provisioning as a second layer closing
attacks A5/A6.

A search of `child-android/app/src/main/kotlin/com/openwarden/child/` finds **neither** call
present in the current codebase:

- `setUserControlDisabled` -- not called anywhere in child-android main source.
- `Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)` or equivalent -- not
  present.

`DISALLOW_DEBUGGING_FEATURES` is therefore carrying the full adb-closure load without the
belt-and-suspenders that would handle edge cases (e.g., the restriction fires before
`setUserControlDisabled` on a given OEM, or `adb_enabled` persists across a partial factory
reset on some firmware variants). Filed as **issue #132**. The existing docs are correct; the
implementation is incomplete.

### 1.6 Per-OEM reliability caveat

`DISALLOW_DEBUGGING_FEATURES` is reliably enforced on Pixel (Tier 1 reference). On Tier-2
OEMs it is best-effort. Known risks:

- Samsung devices have historically had firmware variants where Developer Options re-enables
  adb after a Knox policy change is applied. This is not documented in current ADRs.
- OnePlus OxygenOS has at various points re-evaluated user restrictions after an OTA. The
  post-OTA re-assert (ADR-021 / DEFENSES.md row 10) is the intended mitigant but has not been
  bench-verified on OnePlus 11+.

Do not over-claim "adb is closed" as an absolute for Tier-2 OEMs without bench verification
(ADR-026 D5 / ADR-023 D4 bench QA gate).

---

## Thread 2 - Testing and Demo Around the adb-Dark Constraint

### 2.1 Why a redir to an enforcing child returned 0 bytes

During live demo work: `adb forward tcp:7180 tcp:7180` followed by `curl localhost:7180/health`
on a fully-enforcing (adb-dark) child returns connection refused or 0 bytes. The cause was NOT
the forward topology -- `adb forward` bridges host-to-guest cleanly and is operational regardless
of `DISALLOW_DEBUGGING_FEATURES` once the forward is set up before the restriction fires. The
actual cause was the Ktor/ApiServer not answering because `PolicyService` was not running (either
the FGS was killed or the service had not yet started). The `adb forward` plumbing is viable
with a live server; the forwarded port is simply dead without it.

**Key constraint:** on a fully-enforcing child device, `adb forward` itself requires adb to be
on. Once `DISALLOW_DEBUGGING_FEATURES` takes effect, `adb devices` may still show the device
but adb commands are rejected at the Android end. A forward established before enforcement fired
may persist but cannot be re-established after.

### 2.2 Options ranked for testing around the adb-dark constraint

| Rank | Option | Mechanism | Assessment |
|---|---|---|---|
| 1 (current) | **e2e-exit-criteria.sh pattern** | Force-stops the watchdog for the adb window; criterion-2 verified out-of-band | Sound and already in use. Demonstrates the restriction blocks adb at OS level. Criterion-2 must be verified via parent UI or log inspection, not automated adb. |
| 2 (best next) | **Injectable `restrictionFilter` seam** | A `(String) -> Boolean` lambda injected at test time; test path omits only `DISALLOW_DEBUGGING_FEATURES`; release passes the identity function (all restrictions in). Mirrors the existing `lock` seam in PolicyEnforcer. | Does not exist yet. Filed as **issue #131**. Clean, auditable, no risk of shipping a weaker build if the seam is constructor-injected rather than a BuildConfig flag. |
| 3 | **Profile-Owner test harness** | Stand up a Work Profile (not DO) for control-plane tests | Exercises policy push / bundle verify but misses the DO enforcement surface. `DISALLOW_*` restrictions in the required set are DO-only and do not apply in a Work Profile context on the primary user. Not useful for this class of test. |
| 4 | **android:testOnly APK flag** | Mark APK test-only to allow install alongside debug runtime | Irrelevant to the adb-dark problem. `android:testOnly` restricts Play distribution but does not affect which DO restrictions are applied at runtime. |

The recommendation is to implement option 2 as follow-up work on issue #131. The seam should be
constructor-injected into `PolicyEnforcer` and the test override excludes
`DISALLOW_DEBUGGING_FEATURES` only. No `BuildConfig.DEBUG` conditional; no weaker build variant
ships.

### 2.3 Two-emulator live-reporting demo without a fully-enforcing child

A non-DO reachable child (BootReceiver to PolicyService to ApiServer, adb alive,
`adb forward tcp:7180 tcp:7180`) gives a working two-emulator live-reporting demo including real
`/usage` reporting, heartbeat, and policy sync. Full-DO enforcement is separately proven by
running the provisioning path on a real device and observing adb go dark. These are two distinct
demonstration modes:

- **Demo mode (emulator, non-DO or DO with `restrictionFilter` seam):** shows the control-plane
  working end-to-end; suitable for developer walkthroughs and CI smoke tests.
- **Enforcement proof (real device, full-DO):** shows adb going dark, demonstrating the actual
  security posture. Can be captured as a video artifact; does not need to be an automated test.

---

