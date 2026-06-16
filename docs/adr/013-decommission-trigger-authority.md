# ADR-013: Single authoritative trigger for self-decommission / 7-day wipe

Status: Proposed
Date: 2026-06-16

## Context

Red-team finding R1: the self-decommission / 7-day self-wipe trigger is specified three contradictory ways across canon. These cannot all be true, and the most permissive of them is exploitable.

- **[`docs/RECOVERY.md`](../RECOVERY.md):136** (§9, "dad got hit by a bus" backstop) — triggered on the child phone by "hold power + volume-down for 60 seconds at the lock screen." **No age gate. No authentication.** A kid who knows the lock PIN (or just reaches the lock screen) can start an irreversible 7-day self-wipe.
- **[`docs/RECOVERY.md`](../RECOVERY.md):141** — the only defense after the chord is a parent push alert with a one-tap **CANCEL**. This is **absence-based**: if no parent acts, the wipe proceeds. An inattentive, traveling, or alert-fatigued parent loses the device by *not* responding. A single alert, no retry/escalation specified.
- **[`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md):111-117** and **:279-281** — describes the same 7-day decommission as an **age-14+** capability initiated from the **Kid Transparency Screen**, cancelable by either party. No mention of a lock-screen chord, and an explicit age floor that §9 of RECOVERY.md does not have.
- **[`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md) §9 (lines 482-506)** — "Graduation = decommission," **parent-initiated**, requires the parent to **type the recovery phrase**, then a 7-day delay cancelable by either parent. This is the recovery-phrase-rooted path and contradicts both the unauthenticated chord (RECOVERY.md:136) and the kid-initiated-from-transparency-screen framing (PARENT_AS_ADVERSARY.md:279) on *who* may trigger and *what authentication* is required.

A secondary contradiction compounds R1: **[`docs/RECOVERY.md`](../RECOVERY.md):155-156** (§10) places "R2 decommission" in the **emergency set that bypasses the 24h delay and co-parent cancel**. So the one command class that ends the device is also the one with the *weakest* procedural brakes.

This collides with two non-negotiables in [`CLAUDE.md`](../../CLAUDE.md): **fail-closed** ("every error path defaults to *more* restriction, never less") and **recovery phrase = root authority**. An unauthenticated lock-screen chord that can erase the device is fail-*open* and roots device-ending authority in the lock-screen PIN instead of the phrase.

## Options

1. **Status quo (three specs coexist).** Rejected. Ambiguous specs ship as the most permissive reading; the unauthenticated chord (RECOVERY.md:136) wins by default and a PIN-knowing kid can wipe the device. Directly violates fail-closed.
2. **Keep the lock-screen chord, add a visible countdown only.** This is essentially today's §9. Trade-off: the "family member notices" property is real but weak — it assumes a co-resident adult sees the banner within 7 days and understands it. Absence-based cancel + single alert means inattention = wipe. Still no age gate, still PIN-rooted. Rejected.
3. **Chord allowed but gated by biometric/auth at the lock screen.** Reduces the casual-PIN-kid attack, but the lock screen is the wrong surface for a device-ending decision: it is reachable by anyone holding the phone, the auth there is the same biometric that unlocks the phone day-to-day, and it still has no age gate or co-parent involvement. Marginal improvement, still fail-open under coercion. Rejected as the *primary* path.
4. **One authoritative spec; device-ending authority rooted in the recovery phrase / authenticated flows; kid-initiated decommission gated and positively acknowledged.** Recommended. Details below.

## Decision

Adopt **option 4. There is exactly one authoritative spec for ending the device, and it is rooted in the recovery phrase, not the lock screen.**

There are two — and only two — paths that can lead to a self-decommission / wipe:

**A. Parent-initiated decommission / graduation (the canonical path).** Per [`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md) §9: parent types the **recovery phrase**, a signed decommission command is issued, a **7-day delay** runs with a kid-visible banner, and **either parent can cancel** during the window. This is the "dad got hit by a bus" backstop too: when the parent is incapacitated, the surviving adult uses the *printed recovery phrase* (the whole point of [`docs/RECOVERY.md`](../RECOVERY.md) §1-§3) to authenticate the decommission. The backstop does not require a lock-screen chord — it requires the artifact every recovery flow already depends on.

**B. Kid-initiated decommission (if shipped at all — Tier 2, not v1).** Permitted **only** when **all** of the following hold:
- **(a) Age ≥ threshold** read from the **signed policy bundle** (`kid.birthday` / stage; default age 14 per PARENT_AS_ADVERSARY.md:111). The age comes from signed canon, not from device-local state the kid can edit.
- **(b) Authentication beyond the lock-screen PIN.** The trigger lives on the **Kid Transparency Screen** behind a step distinct from device unlock (kid-account credential / re-auth), never a hardware chord reachable from the lock screen by anyone holding the phone.
- **(c) A POSITIVE co-parent acknowledgement** — a parent must affirmatively *acknowledge*, not merely fail to cancel. **Silence does not advance the wipe.** If no parent positively acknowledges within the window, the countdown **does not complete** (fail-closed). Coercive-control protection from PARENT_AS_ADVERSARY.md is preserved: a parent who *refuses to ever acknowledge* an of-age teen's request is the abuse the doc warns about, so an of-age (≥14) teen's request escalates to a hard-floor parent decision rather than silently proceeding *or* silently dying — see Consequences.
- **(d) Escalating, retried parent alerts** across the countdown (not the single push of RECOVERY.md:141): repeated, escalating notifications to all paired parent devices over the 7 days, so the cancel/acknowledge decision is an *informed* one, never lost to a single missed notification.

**Removed:** the unauthenticated **power + volume-down-60s lock-screen chord** (RECOVERY.md:136) is **deleted**. No device-ending action is reachable from the lock screen without recovery-phrase authentication. If any lock-screen affordance survives at all, it is **parent-only and recovery-phrase-gated**, never a kid path.

**Decommission leaves the emergency-bypass set.** The 24h-delay / co-parent-cancel brakes (RECOVERY.md §10) are the *point* for a device-ending command. R1 self-rotation and R4 escalation prep may remain emergency (they restore parent authority, they don't destroy the device); **R2/decommission moves out of the immediate-execute set** so it always runs under the 7-day window with positive acknowledgement.

**Why this ruling:** it ties both non-negotiables together. *Recovery phrase = root authority* — only the phrase (path A) or a signed, age-gated, positively-acknowledged flow (path B) can end the device; the lock-screen PIN never can. *Fail-closed* — every ambiguity resolves toward "device stays enrolled": no auth ⇒ no wipe; no positive acknowledgement ⇒ no wipe; under-age per signed bundle ⇒ no wipe; missed/lost alerts ⇒ escalate, never silently proceed. Ambiguity must not be the trigger.

## Consequences

**Good:**
- Closes R1: a PIN-knowing kid can no longer start an irreversible wipe. Device-ending authority is rooted in the recovery phrase, matching the non-negotiable.
- One spec, three docs reconciled — no more "most permissive reading wins."
- Fail-closed end to end: silence, lost alerts, missing auth, and under-age all resolve to *device stays enrolled*.
- Anti-stalkerware stance preserved: the of-age teen's decommission right (PARENT_AS_ADVERSARY.md §10) survives as a real, gated capability rather than a deleted feature.
- Escalating alerts replace the single fragile push — the "dad got hit by a bus" case still works because the *recovery phrase* (path A) is the designed backstop, not an alert nobody saw.

**Bad:**
- The genuinely-orphaned-child case (the original §9 motivation) now depends on an adult having the **printed recovery phrase**, not a chord the kid can trigger. If *no* adult has the phrase, the device cannot self-decommission — this is the fail-closed cost, and it pushes harder on recovery-phrase OPSEC (RECOVERY.md §3-§4) and on the R4 Google-FRP escalation. This must be stated plainly in onboarding.
- Positive-acknowledgement for kid-initiated (path B) introduces a tension: a coercive parent who *never* acknowledges could indefinitely block an of-age teen. Resolution must be specified in the doc edits — proposal: an of-age (≥14) teen request that receives **no acknowledgement and no cancel** within an extended window converts to a hard floor (e.g. transparency-screen-visible escalation + a longer, still-cancelable secondary timer), so neither "silent wipe" nor "silent indefinite block" is the outcome. **This sub-policy is the one open question this ADR hands to the maintainer.**
- Removing the chord deletes a feature some users may have read about; the backstop framing in README must be updated so it no longer implies a kid-triggerable hardware combo.
- Kid-initiated decommission is explicitly **Tier 2 / not-v1**; v1 ships path A (parent + recovery phrase) only.

## Doc changes required

- **[`docs/RECOVERY.md`](../RECOVERY.md) §9 (lines 134-146):** Remove the power+volume-down-60s lock-screen chord (line 136) as a kid/unauthenticated trigger. Re-title §9 to frame the backstop as **recovery-phrase-initiated** (path A above). Replace the single immediate push alert (line 141) with **escalating, retried parent alerts** across the 7 days. Keep the visible countdown banner, monotonic-clock, reboot-survival, and FRP-release properties (lines 140, 142-144). Add a sentence: ambiguity / no authentication ⇒ no wipe (fail-closed).
- **[`docs/RECOVERY.md`](../RECOVERY.md) §10 (lines 155-156):** Move **R2 / decommission out of the emergency immediate-execute set**. Emergency set keeps R1 self-rotation and R4 escalation prep only. Decommission always runs under the 7-day window + positive co-parent acknowledgement.
- **[`docs/RECOVERY.md`](../RECOVERY.md) §14 (Test plan, lines 201-202):** Update the two 7-day countdown tests so the trigger is the authenticated path, not the chord. Add a **negative test**: lock-screen chord (if any vestige exists) does **not** initiate a wipe without recovery-phrase auth; and a **positive-acknowledgement** test: countdown does not complete on parent *silence*.
- **[`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md):111-117 and :279-281:** Keep the age-14+ kid-initiated capability but reconcile its mechanics with the single spec: (a) age read from the **signed policy bundle**, (b) auth **beyond the lock-screen PIN**, (c) **positive** co-parent acknowledgement (change "cancelable by either party" / "either party can cancel" so that absence of action does **not** complete the wipe), (d) escalating alerts. Add the of-age-teen "never-acknowledged" resolution sub-policy (the open question above). Mark the capability **Tier 2 / not v1**.
- **[`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md) §9 (lines 482-506):** This is the closest to the authoritative path A — keep it as canon. Add one cross-reference line noting it is the single authoritative parent-initiated decommission flow and that the lock-screen chord is removed per this ADR. Confirm "either parent can cancel" remains correct for *parent-initiated* (path A); the positive-acknowledgement requirement applies to *kid-initiated* (path B).
- **[`README.md`](../../README.md):** Update the "7-day time-locked self-decommission ('dad got hit by a bus' backstop)" feature line so it no longer implies a kid-triggerable hardware chord — frame it as the recovery-phrase-rooted backstop.

## Cross-refs

- [`docs/RECOVERY.md`](../RECOVERY.md) §9 (134-146), §10 (150-157), §14 (201-202)
- [`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md) §3 (111-117), §10 (279-281), §6/§13 (anti-stalkerware floor)
- [`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md) §9 (482-506)
- [`CLAUDE.md`](../../CLAUDE.md) — non-negotiables: fail-closed; recovery phrase = root authority
- [`README.md`](../../README.md) — backstop feature line
- [`docs/DEFENSES.md`](../DEFENSES.md) #15 (BIP39 phrase + 7-day time-lock), #20 (co-parent feed)
- [`docs/ATTACKS.md`](../ATTACKS.md) — K1 (kid social-engineers parent), K2 (phrase social-engineering)
- [`docs/adr/006-privacy-no-server.md`](006-privacy-no-server.md) — house format reference
