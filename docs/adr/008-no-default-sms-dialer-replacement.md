# ADR-008: No default SMS/Phone replacement; CallScreeningService v2

Status: Accepted
Date: 2026-06-15

## Context

User asked: make OpenWarden the default SMS app + default phone dialer for "easier monitoring." Research at [`docs/TELEPHONY.md`](../TELEPHONY.md) investigated feasibility.

## Options

1. **Replace default SMS + Phone.** Read all SMS, log all calls. ~12 engineer-months. Stalkerware boundary crossed.
2. **Partial: CallScreening role only.** Screen incoming calls (block, log, allow) without replacing dialer. Reads no content.
3. **Drop entirely.** Stay stock SMS/Phone. DPC time-window suspension covers basic control.

## Decision

Adopt **option 2: CallScreeningService v2** with DPC telephony policies in v1.

**v1 ships:**
- DPC time-window suspension of `com.google.android.apps.messaging` + `com.google.android.dialer` (bedtime locks)

**v2 ships:**
- `RoleManager.ROLE_CALL_SCREENING` claimed silently via `setRoleHolder` API 34+
- Block unknown / non-allowlisted numbers
- Log call attempts (metadata only: who, when, allow/block) sealed-box to parent
- DPC-managed contact allowlist
- Contact-add approval flow

**Never ships:**
- SMS content reading
- Call audio (AOSP doesn't expose it via dialer role anyway)
- Per-conversation tracking
- Call recording

## Rationale (load-bearing)

1. **Call audio is not accessible** even as default dialer — AOSP gates `CAPTURE_AUDIO_OUTPUT` to signature-level. The "monitoring" promise of default-dialer is illusory.
2. **RCS killed default-SMS.** Google Messages binds private `com.google.android.ims` for RCS; replacement = SMS+MMS only = degraded social UX = kid moves to Signal/Snap/Discord = zero parental gain.
3. **`CallScreeningService` covers ~90% of useful control** without `ROLE_DIALER`. Truecaller / Hiya / RoboKiller proof point.
4. **Stalkerware boundary line.** Content access = stalkerware. Policy-decision metadata = control. Default-SMS only adds content access.

## Consequences

**Good:**
- 90% of useful control at 5% of build cost (~2-3 engineer-months for v2 vs ~12 for replacement).
- Stays clear of stalkerware boundary.
- Doesn't break 911.
- Doesn't fight Google Messages / Dialer for "default" role on every Android update.

**Bad:**
- Some parents will expect SMS contents to be visible. Documented in [`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md) why we don't.
- "Anti-stalkerware OpenWarden" positioning leaves a flank exposed vs Bark's monitoring marketing.

## Future option

A separate kid-safe messenger product (e.g., "OpenWarden Messenger") could ship in its own repo, sharing crypto + policy modules. Not in OpenWarden core scope.

## Cross-refs

- [`docs/TELEPHONY.md`](../TELEPHONY.md)
- [`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md)
- [`docs/PRIVACY_LEGAL.md`](../PRIVACY_LEGAL.md) (stalkerware boundary)
