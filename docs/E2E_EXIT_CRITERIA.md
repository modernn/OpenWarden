# E2E exit-criteria runbook — "Oliver's phone works"

The bench proof that a real provisioned device meets the v1 exit criteria
([`ROADMAP.md`](ROADMAP.md) → "Oliver's phone works"). Issue **#30**.

Four criteria:

1. **Provision a Pixel 7 from factory state in under 30 min.**
2. **Restart → self-unlock → restrictions intact** (owner-mode phone reboots and comes back locked down with no human action).
3. **Block + unblock an app from the parent phone, sub-5-sec latency.**
4. **Survive a 7-day uptime test on the bench device.**

## The constraint that shapes this runbook

**A fully-enforced OpenWarden device cannot be inspected over ADB.** The Day-One baseline includes
`DISALLOW_DEBUGGING_FEATURES`; the instant `PolicyService`'s watchdog applies it, the device drops
to ADB `offline` (verified live: provision → boot → `PolicyService` applies Day-One → `adb` loses
the device). So an ADB-driven instrumentation test can observe the baseline only while it is
**absent** — never while **enforced**. That is by design: it is the same restriction that stops a
kid from sideloading over ADB.

Consequence: criteria **1** (Device Owner) and **3** (latency) are ADB-automatable. Criterion **2**
(restrictions intact) is now **automated for the whole baseline except the one ADB-killing
restriction** — the ADR-045 `restrictionFilter` seam lets an instrumented test omit
`DISALLOW_DEBUGGING_FEATURES` and read back the rest while ADB stays alive (issue #131). That single
restriction remains out-of-band: the device going ADB-offline after boot is itself positive evidence
that `DISALLOW_DEBUGGING_FEATURES` is live.

| Criterion | Arm | Where |
|---|---|---|
| 1 — provision < 30 min | **automated** | `scripts/e2e-exit-criteria.sh` times provisioning; `exitCriterion1_deviceOwnerProvisioned` asserts the outcome (Device Owner active) |
| 2 — reboot → restrictions intact | **automated** (baseline **minus** `DISALLOW_DEBUGGING_FEATURES`, via the ADR-045 seam) + **out-of-band** (that one restriction) | `exitCriterion2_enforcedBaselineIntactMinusAdbKiller` applies + reads back the filtered baseline on the real Device Owner (ADB stays alive); the ADB-offline-after-boot signal + independent on-device spot checks substantiate the omitted restriction (below) |
| 3 — block/unblock < 5 s | **automated** (device-side **floor**) + **manual** (true end-to-end) | `exitCriterion3a/3b` measure the on-device DPM leg with the watchdog halted — a *floor*, excludes scheduling; the parent→child stopwatch below measures the real end-to-end number, which can only be larger |
| 4 — 7-day uptime | **manual** | soak procedure below |

## Automated arm (criteria 1 + 2 + 3)

### What it asserts

[`ExitCriteriaE2ETest`](../child-android/app/src/androidTest/kotlin/com/openwarden/child/ExitCriteriaE2ETest.kt)
(module `:app`, runs on the device as the Device-Owner app):

- **`exitCriterion1_deviceOwnerProvisioned`** — hard-asserts `isDeviceOwnerApp` + active admin. The
  one non-skippable test, so a non-provisioned device fails here rather than going vacuously green.
- **`exitCriterion2_enforcedBaselineIntactMinusAdbKiller`** — applies the Day-One baseline **minus**
  `DISALLOW_DEBUGGING_FEATURES` (via the ADR-045 `restrictionFilter` seam) on the real Device Owner.
  `applyDayOneRestrictions()` verifies the filtered set against the DO-authoritative
  `getUserRestrictions(admin)` and throws on any gap, so its clean return is the load-bearing check;
  the test flanks it with an independent recompute of the expected set and an assertion that the
  ADB-killer is **not** enforced. Its added assurance over the host test is that it hits the **real
  `DevicePolicyManager`** — proving the set actually sticks on the platform (the host test injects a
  fake readback). The omitted restriction stays out-of-band (you cannot keep ADB alive and observe the
  restriction that severs it); its release-set membership is pinned by the host `PolicyEnforcerTest`.
  Reverts in `finally`.
- **`exitCriterion3a_enforcementWriteReadbackLatencyUnder5s`** — times a Device-Owner enforcement
  write → authoritative readback (a reversible, non-baseline, **non-debugging** probe restriction)
  as a victim-free proxy for the block/unblock path. Always runs on a provisioned device.
- **`exitCriterion3b_appSuspendLatencyUnder5s`** — times the real `setPackagesSuspended` block +
  unblock on a benign user app. Skips (logged) only when the device has no safe victim app.

The latency tests revert their probe state in `finally`; the criterion-2 test reverts every
restriction it applied in `finally` (leaving a watchdog-halted device unrestricted — a live watchdog
re-asserts the full baseline on its next tick). **No test applies `DISALLOW_DEBUGGING_FEATURES`**, so
all of them keep ADB alive.

### Run it

```bash
# fresh AVD, no snapshot, wiped — the closest emulator analog of factory state
emulator -avd openwarden-pixel7-api35 -no-window -no-snapshot -wipe-data &
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'

# build → install → provision → assert DO + latency (prints provision wall-clock and PASS/FAIL)
./scripts/e2e-exit-criteria.sh
```

The script verifies criteria 1 + 3 in the **provisioned-but-not-yet-enforcing** window — it does
**not** reboot (a reboot triggers full enforcement, which severs ADB), and it force-stops the
watchdog before the test so `DISALLOW_DEBUGGING_FEATURES` cannot drop the device mid-run. Override
the target with `DEVICE=…` or the package with `PKG=…`.

> On a truly fresh AVD, the act of `dpm set-device-owner` starts the watchdog, which may sever ADB
> during provisioning (that is criterion 2 in action). If the script reports the device went
> offline, that is the enforcement signal — re-run criteria 1+3 against a device whose watchdog is
> halted, or read them in the pre-enforcement window.
>
> The latency tests revert their state in `finally`; a **hard-killed** run can leave the probe
> restriction set or the benign app suspended. Re-running clears it, or clear by hand:
> `adb shell dpm clear-user-restriction <admin> no_airplane_mode`.
>
> Gradle module is `:app` under `child-android/` — invoke as `cd child-android && ./gradlew :app:…`.

To re-run only the assertions against an already-provisioned device whose watchdog is not enforcing:

```bash
adb shell am instrument -w -e class com.openwarden.child.ExitCriteriaE2ETest \
  com.openwarden.child.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## Manual arm

### Criterion 2 — the out-of-band residual: `DISALLOW_DEBUGGING_FEATURES`

The bulk of criterion 2 is now automated (`exitCriterion2_enforcedBaselineIntactMinusAdbKiller`,
above). What remains out-of-band is the **one** restriction the automated test must omit to keep ADB
alive — `DISALLOW_DEBUGGING_FEATURES` — plus the full-enforcement reboot path. Cannot be read over
ADB on a fully-enforced device (see "The constraint"). Substantiate it with checks that are
**independent of the enforcer** — these do not route through the same component that applies the
restrictions:

1. **On-device spot checks (independent).** Confirm representative restrictions by hand: Settings →
   factory reset blocked, USB file transfer blocked, unknown-sources install blocked, add-user
   blocked. The Kid-Transparency screen (`docs/KID_TRANSPARENCY.md`) shows the monitored surface to
   the kid. This is human observation of the *effect*, not the enforcer's own report.
2. **The ADB-offline signal (independent).** Reboot the provisioned device. Within ~1 min it should
   drop to ADB `offline` — an external, positive observation that `DISALLOW_DEBUGGING_FEATURES`
   (a Day-One restriction) is enforced:
   ```bash
   adb -s <dev> reboot; adb -s <dev> wait-for-device
   adb -s <dev> shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
   # then poll: the device should become `offline` in `adb devices` once the watchdog applies Day-One
   ```
3. **The app's own fail-closed self-verify (corroborating only).** `PolicyEnforcer.applyDayOneRestrictions`
   applies the full baseline, reads every restriction back via the DO-authoritative
   `DevicePolicyManager.getUserRestrictions(admin)`, and **locks the device
   and throws** if any required restriction is not set (ADR-020) — so a device that boots, enforces,
   and stays **usable (not locked)** has internally verified `requiredRestrictionsForSdk`. Treat this
   as **corroboration, not proof**: it is partially circular (the enforcer is both the actor and the
   oracle — a bug that both fails to set restriction X *and* omits X from the readback would self-
   report green). Checks 1 and 2 are what break that circle.

To read the full restriction list over ADB you would need a build that omits
`DISALLOW_DEBUGGING_FEATURES` (a debug-only enforcement change — out of scope for #30; would need an
ADR), or a non-ADB readout channel (see Known gaps).

### Criterion 3 — true end-to-end block/unblock latency

`exitCriterion3a/3b` bound the **device-side** enforcement leg; the exit criterion is the **whole**
parent-tap → child-enforced loop, which needs a signed command the test can't mint. Measure it with
two phones (or two emulators):

1. On the child, watch enforcement land: `adb -s <child> logcat -s OpenWardenEnforcer`.
2. On the parent app, block a currently-allowed app on the child and start a stopwatch.
3. Stop when the child shows the app suspended/hidden (logcat line, or the app vanishes from the
   launcher). **Must be < 5 s.** Repeat for unblock.

The parent's command path is `/lock` · `/unlock` · `/policy` on the child (`ApiServer`); a new
allowlist that omits an app is the block. There is **no in-code latency instrumentation yet** — this
is a human stopwatch until one lands (see Known gaps).

### Criterion 4 — 7-day uptime soak

Not automatable in one run. On a bench device left powered for ≥ 7 days:

1. Provision (per above) and note the start time.
2. Daily: confirm the device is still enforced (it stays ADB-offline / locked-down) and has not
   self-locked from a drift.
3. Mid-soak, force a reboot and confirm self-unlock (criterion 2) still holds.
4. Pass = 7 days with no missed watchdog re-assert and no restriction drift.

## Known gaps (surfaced, not silently worked around)

- **Criterion 2 is automated except for `DISALLOW_DEBUGGING_FEATURES` itself** — the ADR-045 seam
  (issue #131) closed the gap for the rest of the baseline via `exitCriterion2_…MinusAdbKiller`
  (test-wiring, not a debug build variant — release is untouched). The one ADB-killing restriction
  stays inherently out-of-band: keeping ADB alive to read the set is mutually exclusive with
  observing the restriction that severs ADB. It is substantiated by the ADB-offline-after-boot signal
  and on-device spot checks (above), not by an automated readback.
- **No non-ADB health/readout channel** — `docs/PROVISIONING_V2.md` references a `/health` content
  provider, but `ApiServer` does **not** implement it; `scripts/test-emulator.sh` still asserts that
  nonexistent endpoint and invokes a stale `:childAndroid:` module (the real module is `:app` under
  `child-android/`). `e2e-exit-criteria.sh` depends on neither. Repairing `test-emulator.sh` is out
  of scope for #30.
- **No in-code block/unblock latency metric** — criterion 3's true end-to-end number is a manual
  stopwatch. An instrumented timestamp (command-received → enforcement-applied) would let the
  automated arm own criterion 3 end-to-end.
- **DNS floor is policy-applied, not Day-One** — `DnsFloor` lands on the first `/policy` apply, not
  at provision; DNS-floor coverage is issue #75's scope.
