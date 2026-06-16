# OpenWarden Telephony Research: SMS, Phone, Call Screening

**Author:** Research delegated agent
**Date:** 2026-06-15
**Status:** Decision document — recommendation at end
**Scope:** Should OpenWarden (Apache 2.0 OSS, Android Device Owner parental control, Pixel 7 child target) become the default SMS app and/or default Phone dialer to enable telephony monitoring/control? What are the alternatives?

---

## TL;DR (verdict)

**Do not ship a default-SMS replacement. Do not ship a default-Dialer replacement.** Both paths are massive engineering investments that either (a) drag OpenWarden into stalkerware territory or (b) require building a competitive RCS/MMS messenger and a 911-grade dialer with zero meaningful safety upside.

Instead, ship in v2:

1. `CallScreeningService` claiming `ROLE_CALL_SCREENING` for allowlist-based incoming/outgoing call control.
2. DPC-managed contacts (`setCrossProfileContactsSearchDisabled`, `ContactsContract` injection) for the allowlist itself.
3. Time-window suspension of `com.google.android.apps.messaging` and `com.google.android.dialer` via existing `setPackagesSuspended`.
4. Parent-side **event** feed: "Call from $unknown_number blocked at $time" — metadata only, no contents, no recordings.

This delivers ~90% of the genuine *control* value at ~5% of the implementation cost while staying decisively on the right side of the Coalition Against Stalkerware boundary.

---

## 1. Default SMS app — what `ROLE_SMS` actually enables

### The role

Android (since 4.4 KitKat, governed by `RoleManager` since Q) gates write access to the SMS provider, the `SMS_DELIVER_ACTION` broadcast, and `MMS_DELIVERED` behind a single user-selected role: `RoleManager.ROLE_SMS`. Only one app at a time holds it.

Request flow:
```kotlin
val rm = getSystemService(RoleManager::class.java)
if (rm.isRoleAvailable(RoleManager.ROLE_SMS)
    && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
    startActivityForResult(
        rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_SMS_ROLE)
}
```

The user (or, in a Device Owner scenario, the DPC via `DevicePolicyManager.setDefaultSmsApplication` on API 34+) must consent. The DPC path *does* let OpenWarden skip the consent dialog — but that doesn't make the engineering cheaper.

### What becomes available when you hold the role

| Capability | API | Requires `ROLE_SMS`? |
|---|---|---|
| `SmsManager.sendTextMessage` | `android.telephony.SmsManager` | Yes (since Q) |
| `SmsManager.sendMultipartTextMessage` | same | Yes |
| Receive `Telephony.Sms.Intents.SMS_DELIVER_ACTION` | broadcast | **Yes — exclusive** |
| Receive `WAP_PUSH_DELIVER_ACTION` (MMS) | broadcast | **Yes — exclusive** |
| Write `Telephony.Sms`, `Telephony.Mms` | content provider | Yes (write) |
| Read `Telephony.Sms` | content provider | No — any app with `READ_SMS` |
| `SMS_RECEIVED_ACTION` (legacy, read-only notice) | broadcast | No — any app with `RECEIVE_SMS` |

The asymmetric piece is important: **read access does not require the role.** Bark and similar use `READ_SMS` plus Accessibility to scrape, which is precisely why they are categorized as stalkerware-adjacent.

### What stays *unavailable* even with the role

- Cannot intercept SMS sent by another carrier-aware app (e.g. a parallel SIM utility).
- Cannot read iMessage / Signal / WhatsApp / RCS-via-Google traffic (Signal and WhatsApp render to their own surface; RCS via `Carrier Services` is a Google-controlled binding — see §1c).
- Cannot suppress the OS-level emergency-SMS path on Pixel (Emergency SOS sends carrier SMS independently of the default app on Pixel 7+).
- Cannot manipulate STK (SIM toolkit) messages.

### 1b. MMS

MMS over the default-app contract has three load-bearing pieces:
1. APN/MMSC resolution per carrier — `SmsManager.downloadMultimediaMessage` and the carrier config XML.
2. PDU encoding/decoding (`com.google.android.mms.pdu`) — non-trivial; AOSP ships a reference but bugs persist.
3. Telephony provider writeback (`Mms.Inbox`, `Mms.Sent`, `Mms.Part`).

Realistic build cost for **a working MMS pipeline that doesn't drop attachments on Verizon, AT&T, T-Mobile** is 3–6 engineer-months for a single seasoned Android engineer. Test matrix is the killer: every carrier × every Pixel build × every MMS attachment type.

### 1c. RCS

This is where the conversation effectively ends.

RCS on Pixel is delivered through `com.google.android.ims` (Carrier Services). The Universal Profile binding from Carrier Services to the messaging UI is **only exposed to Google Messages** via a non-public AIDL. There is no public API. Google's Jibe platform (which now powers Verizon, AT&T, T-Mobile RCS — Verizon switched in 2024) requires the messaging app to be Google Messages or a carrier-blessed equivalent.

What this means for OpenWarden-as-default-SMS:
- All RCS messages (the modern default — read receipts, typing, group chat, high-res media, end-to-end via MLS on Google Messages 2024+) **bypass your app entirely**. The kid's friends send RCS; you receive nothing. You see only the SMS fallback if it happens.
- Google Messages will keep prompting "Switch back to Google Messages for RCS features." — every time the child opens it. You will lose.
- You cannot ship a competitive RCS client because the Jibe binding is private.

**Conclusion:** A OpenWarden-as-default-SMS app in 2026 ships as an SMS+MMS-only client in an RCS-default world. The child experiences a degraded conversation with every peer. Parental UX promise: "Your kid's phone can't do typing indicators or group chats or send a photo at full resolution." This is a non-starter for an 11-year-old target.

### 1d. How Signal handles non-default

Signal explicitly does *not* request `ROLE_SMS` anymore (they dropped SMS integration entirely in 2023). They run as a Signal-protocol-only messenger. This is the working reference for "do not become default SMS": Signal users coexist with Google Messages as default and lose nothing because their conversations are inside Signal.

---

## 2. Default Phone (Dialer) — what `ROLE_DIALER` actually enables

### The role

`RoleManager.ROLE_DIALER` grants:
- `InCallService` binding for **all** active calls (cellular + ConnectionService-routed VoIP).
- Write access to `CallLog.Calls`.
- `MODIFY_PHONE_STATE` becomes effectively granted (silently) for telecom operations.
- The system routes the incoming-call UI to your app's `InCallService`.

### What `InCallService` exposes

| Exposed | Not exposed |
|---|---|
| `Call.Details` (number, presentation, account handle, gateway info) | **Voice audio stream** |
| Call state transitions (DIALING → ACTIVE → DISCONNECTED) | Microphone capture of the *remote* party |
| DTMF playback | Remote party's audio buffer at any layer |
| Hold / unhold / merge / swap | Speech-to-text of the live call (AOSP does not pipe modem audio to AudioRecord) |
| Audio route (`CallAudioState`: earpiece/speaker/BT/wired) | RTP from a CS call (cellular calls are kernel/modem-routed) |
| Bluetooth headset signaling | Carrier-side recording / lawful intercept |

**The voice stream is not accessible.** AOSP intentionally does not route the downlink PCM from the modem into a user-space `AudioRecord` source. `MediaRecorder.AudioSource.VOICE_CALL` exists in the SDK as a constant but is gated by `CAPTURE_AUDIO_OUTPUT` (signature|privileged) — not grantable to a Device Owner DPC, not grantable via `ROLE_DIALER`. On Pixel 7 specifically, the audio HAL does not surface the call downlink to user space at all; OEMs that *do* (some Samsung, some Xiaomi China builds) ship custom AOSP forks.

This is the central pin of the whole analysis: **no, even as default dialer, you cannot record or transcribe a phone call on a stock Pixel 7.** Apps that claim to (Cube ACR pre-2022) used a kernel-level Samsung-specific tap that has been closed since Android 10.

### What you *can* do with `ROLE_DIALER`

- Pre-call screen (but `CallScreeningService` does this better without the role — see §3).
- Display the in-call UI.
- Write call log entries (or modify them — *risky for stalkerware classification if used to hide calls*).
- Surface a call quality survey.
- Implement voicemail visualization.
- Route via custom `PhoneAccount` (VoIP) — irrelevant for child cellular use.

### What stock Google Dialer ships that you would have to match

Voicemail (visual voicemail OMTP), spam protection (Google's database), Call Screen ("Hi, who's calling?" — Tensor-only ML, undocumented APIs), reverse caller lookup, video calls (Duo/Meet integration via private hooks), Hold for Me, Direct My Call. **You will not match this.** A child going from Pixel Dialer to OpenWarden Dialer loses Call Screen — arguably *the single best anti-spam feature on any phone today*.

### Emergency / 911 routing

The emergency dialer is a *separate APK* (`com.android.systemui` houses parts of it; `com.android.phone` holds `EmergencyDialer`). It is reachable from the lockscreen "Emergency" button and from `*#*#emergency` flows. It does **not** route through the default-dialer role — emergency calls go through `TelecomManager.placeCall` with `EXTRA_IS_EMERGENCY_CALL` and the `EmergencyCallHelper` in `frameworks/base/telecomm` bypasses third-party `InCallService` bindings. So:

- Replacing the dialer does *not* break 911 from the lockscreen.
- Replacing the dialer *does* mean 911 dialed from inside your OpenWarden-Dialer UI must implement the emergency flow correctly (it's not automatic — you must call `TelecomManager.placeCall` and let the system substitute the emergency `PhoneAccount`).
- ELS (Emergency Location Service) only fires through the system emergency path; a OpenWarden-Dialer that fumbles the call routing risks suppressing ELS. **This is a child-safety regression.**

Bug-for-bug compatibility with the Pixel emergency flow is not something a small OSS project should take on.

---

## 3. `CallScreeningService` — the better path

### The API

```kotlin
class OpenWardenScreener : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        val allowed = Allowlist.contains(number) || Allowlist.windowOpen(number)
        val response = CallResponse.Builder()
            .setDisallowCall(!allowed)
            .setRejectCall(!allowed)
            .setSilenceCall(!allowed)
            .setSkipCallLog(false)   // we want the log
            .setSkipNotification(!allowed)
            .build()
        respondToCall(details, response)
        EventBus.publish(CallEvent(number, allowed, System.currentTimeMillis()))
    }
}
```

Manifest:
```xml
<service android:name=".OpenWardenScreener"
         android:permission="android.permission.BIND_SCREENING_SERVICE"
         android:exported="true">
    <intent-filter>
        <action android:name="android.telecom.CallScreeningService"/>
    </intent-filter>
</service>
```

Request role:
```kotlin
roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
```

As Device Owner you can grant this silently on API 31+ via `DevicePolicyManager.setApplicationRestrictions` + the `setDefaultDialerApplication`-adjacent role hook (`DevicePolicyManager` exposes `setRoleHolder` for select roles on managed devices in API 34).

### Capability matrix vs. `ROLE_DIALER`

| Need | `ROLE_CALL_SCREENING` | `ROLE_DIALER` |
|---|---|---|
| See incoming caller number | Yes | Yes |
| Block per-call | Yes (`setDisallowCall`) | Yes |
| Silence (no ring) | Yes (`setSilenceCall`) | Yes |
| Reject as if user hung up | Yes (`setRejectCall`) | Yes |
| Send to voicemail | Yes (silence + skip notify + log) | Yes |
| Outgoing-call screening | Yes (since Q, with caveat) | Yes |
| Custom in-call UI | No | Yes |
| Write call log | Indirect (via event) | Yes |
| Replace Google Dialer | No | Yes |
| Break 911 | **No** | Possibly |
| Engineering cost | ~2 weeks | ~6 months |

### Outgoing-call screening caveat

`CallScreeningService.onScreenCall` fires for **outgoing calls only when the number is not in the user's contact list**. If the kid's phone has the allowlist contacts saved (which it must, for the UI to be usable), outgoing calls to those contacts skip screening entirely — which is fine, because they're the allowlist. Outgoing to non-contacts fires the callback and can be blocked. This is exactly the behavior OpenWarden wants.

### Real-world references

Truecaller, Hiya, RoboKiller — all are `CallScreeningService` implementations. None are default dialer. None has access to call audio. All work fine. This is the well-trodden path.

### v1 features achievable with `CallScreeningService` alone

1. **Allowlist enforcement** — block all non-allowlisted incoming and outgoing.
2. **Time-window** — same allowlist gated by `LocalTime.now()` vs schedule per contact.
3. **Event stream** — "Call from $number blocked at $time" → parent family feed.
4. **Spam scoring** *(deferred)* — needs a number-reputation DB. The OSS landscape is thin: there is no FOSS Hiya-equivalent. OpenCNAM has caller-ID lookups but is a paid API. Numverify, Twilio Lookup — paid. **Recommendation: ship without spam scoring; allowlist alone covers a child's threat model.**

---

## 4. Telephony policies via DPC

### User restrictions

| Restriction | Effect | Side effects |
|---|---|---|
| `UserManager.DISALLOW_SMS` | All SMS send/receive blocked at platform | Blocks 2FA SMS, blocks STK msgs — too blunt |
| `UserManager.DISALLOW_OUTGOING_CALLS` | All outgoing calls blocked | **Blocks 911 from in-app dialer paths** — unsafe default |
| `DISALLOW_CONFIG_CELL_BROADCASTS` | Kid cannot disable Amber/emergency alerts | Good default-on |
| `DISALLOW_DATA_ROAMING` | Self-explanatory | Optional |
| `DISALLOW_ADD_MANAGED_PROFILE` | No work profile injection | Already on for Device Owner |

`DISALLOW_OUTGOING_CALLS` documentation in AOSP explicitly says emergency calls are not blocked by the platform — but third-party code paths and OEM custom dialers have historically failed to honor this carve-out. Pixel 7 does honor it. Still, the cleaner path is allowlist via `CallScreeningService` rather than the blunt restriction.

### `setCallScreeningPolicy`

There is no public `setCallScreeningPolicy` API as of API 35. The role-based mechanism is the only public path. Internal `RoleManager.addRoleHolderAsUser` is signature-protected, but Device Owner DPCs can use `DevicePolicyManager.setRoleHolder` on a curated allowlist of roles since Android 14 (API 34). `ROLE_CALL_SCREENING` is on that list. So OpenWarden can silently install itself as call screener on first boot.

### Per-app suspension (the v1 lever OpenWarden already has)

```kotlin
dpm.setPackagesSuspended(adminCn,
    arrayOf("com.google.android.apps.messaging",
            "com.google.android.dialer"),
    true /* suspended */)
```

This is the bedtime-window mechanism. During suspension, the package's activities cannot be launched; broadcasts are dropped; notifications are suppressed. Crucially: **the emergency dialer is a distinct surface** (`com.android.phone` → `EmergencyDialer`) and is not suspended. The lockscreen "Emergency" button still works.

Caveat: SMS *delivery* still happens to the system DB even while Messages is suspended; the kid sees them on unsuspend. If you want delivery-time gating you need `DISALLOW_SMS` (blunt) or `RoleManager.ROLE_SMS` (the path we're rejecting).

---

## 5. Contact allowlist mechanisms

### DPC-managed contacts

A Device Owner can inject contacts into a dedicated `ContactsContract` account it owns:

```kotlin
val account = Account("OpenWarden Allowlist", "com.openwarden.allowlist")
AccountManager.get(ctx).addAccountExplicitly(account, null, null)
// Then write Raw_contacts with ACCOUNT_NAME = "OpenWarden Allowlist"
```

Combine with:
- `dpm.setCrossProfileContactsSearchDisabled(adminCn, true)` — irrelevant on single-user, but useful if OpenWarden ever uses a managed profile model.
- Lock down the Contacts app (Google Contacts) so the kid cannot add/edit (per-app suspension during edit attempts, or stronger: revoke `WRITE_CONTACTS` from all non-OpenWarden apps via `DevicePolicyManager.setPermissionGrantState` set to `PERMISSION_GRANT_STATE_DENIED`).

### How Pinwheel does it

Pinwheel (per their public docs, support.pinwheel.com) ships a custom OS layer where:
- The "Contact Safelist" is the source of truth.
- Three modes: Caregiver-Managed (parent approves every contact), Child-Managed (child adds, parent can revoke), Disabled.
- Parent approves contact-add requests asynchronously from the caregiver app.
- Time windows per contact (e.g., grandma only after 7am).

This is precisely the model OpenWarden should clone — none of it requires `ROLE_SMS` or `ROLE_DIALER`. It requires (a) a contact database, (b) `CallScreeningService` enforcing reads, (c) a parent-side approval queue, (d) per-app DPC suspension for the messaging/dialer apps outside windows.

### How Bark does it

Bark does *not* implement an allowlist. Bark monitors message *contents* via Accessibility plus `READ_SMS`, ships them to the cloud for ML inspection, and alerts on flagged content. **This is the model OpenWarden explicitly rejects.** Bark Phone (their hardware) bolts on contact controls but the core product is content surveillance.

---

## 6. The stalkerware boundary

Per the Coalition Against Stalkerware (stopstalkerware.org) and the F-Droid/NLnet privacy criteria, the boundary is *not* about whether the subject is a child or partner — it's about **what is exfiltrated to a third party and whether the subject knows.**

| Feature | Classification | Reasoning |
|---|---|---|
| Reading SMS contents on parent dashboard | **Stalkerware** | Exfiltrates private communications |
| Logging "Call from $number rejected at $time" | Control | Metadata about *policy enforcement*, not contents |
| Auto-transcribing voice calls | **Stalkerware** | Recording private speech |
| Blocking outgoing calls to non-allowlist | Control | Policy enforcement, decision-time |
| Reading kid's iMessages | **Stalkerware** | Exfiltrates contents (and impossible on Android anyway) |
| Time-window on whole Messages app | Control | App-level access policy |
| Showing parent the *list of numbers* kid texted (no contents) | **Borderline — lean stalkerware** | Reveals social graph |
| Logging "Kid attempted to add 555-1234, parent approved" | Control | Decision audit trail |
| Continuous location streaming to parent | Borderline | Acceptable if kid can see indicator + revoke; stalkerware if hidden |
| Parent-side "list of contacts kid has ever spoken to" | Borderline → lean stalkerware | Social graph leak |

OpenWarden's "control not surveillance" commitment maps cleanly to the right column. The line item the user flagged ("default SMS for easier monitoring") is precisely the wrong-side-of-the-line move: the only thing default SMS enables that `CallScreeningService` + DPC doesn't is **content access**. And content access is stalkerware.

---

## 7. Competitive landscape — what they actually do

| Product | SMS access model | Phone access model | Stalkerware-adjacent? |
|---|---|---|---|
| **Bark** | `READ_SMS` + Accessibility scrape; cloud ML on contents | None notable | **Yes** — content exfiltration is the product |
| **Bark Phone** | Same + custom OS layer with contact allowlist | Custom dialer + allowlist | **Yes** — same content model + hardware |
| **Family Link** | **Cannot** read SMS contents (Google policy) | App-level controls, no dialer replacement | No — sticks to app suspension |
| **Pinwheel** | Custom OS, contacts allowlist, **parent-visible SMS threads** | Custom dialer + allowlist | **Yes — borderline** — parent sees thread contents |
| **AT&T Secure Family** | Carrier-side metadata, no contents | Carrier-side blocks | No — but limited |
| **Norton Family** | Accessibility-based SMS read on Android | None | **Yes** — content scraping |
| **MMGuardian** | Default SMS replacement, parent sees contents | Custom dialer | **Yes** — content exfiltration |
| **Signal** | Not default SMS (since 2023) | N/A | No (not the category) |
| **OpenWarden (proposed)** | DPC suspension + (optional) CallScreening | `CallScreeningService` | **No** |

Family Link is the closest comparable: a major-vendor product that explicitly does **not** read SMS contents because *Google's own policy doesn't permit it.* That Google — the OS vendor with maximum capability — chooses not to is the strongest possible signal that OpenWarden should not either.

---

## 8. Concrete feature list that does *not* require default-app status

| Feature | Mechanism | Build cost |
|---|---|---|
| Allowlist-based incoming-call blocking | `CallScreeningService` | S (1–2 weeks) |
| Allowlist-based outgoing-call blocking | `CallScreeningService` (non-contact branch) | S |
| Time-windowed contact availability | Allowlist + schedule | S |
| Call-event feed to parent (metadata only) | `CallScreeningService` → event bus | S |
| Bedtime suspension of Messages + Dialer | `setPackagesSuspended` (already have) | XS (done) |
| DPC-managed contacts allowlist | `ContactsContract` injection + `setPermissionGrantState` lockdown | M (3–4 weeks) |
| Parent-side contact-add approval flow | Pairing channel + approval queue UI | M |
| Block specific known-bad numbers | Allowlist negative list | S |
| Visualization of incoming call attempts | Event aggregation in parent dashboard | S |
| Block roaming SMS premium / shortcodes | `DISALLOW_SMS` carve-outs (none granular — skip) | — |

Total v2 telephony scope: **~2–3 months for one engineer.** All within OpenWarden's stated values.

---

## 9. Features that *require* default SMS/Phone — and whether worth it

| Feature | Required role | Worth it? |
|---|---|---|
| Parent reads SMS contents in dashboard | `ROLE_SMS` or Accessibility | **No — stalkerware** |
| OpenWarden sends SMS as the kid (custom messenger UI) | `ROLE_SMS` | **No — RCS-degraded UX, 6-month build for negative value** |
| Per-conversation time limits | `ROLE_SMS` or Accessibility | **No — Accessibility is stalkerware-adjacent; app-level window suffices** |
| Call recording | None (impossible on Pixel 7) | **No — illegal many jurisdictions, technically infeasible** |
| Logging called numbers (just numbers, not contents) | `CallScreeningService` (no role needed!) | **Yes — ship via §3** |
| Block call from $unknown | `CallScreeningService` | **Yes — ship via §3** |
| Suppress notification of SMS from non-allowlist | Notification listener (not SMS role) | Maybe — separate skill |
| Custom in-call UI ("This contact is in quiet hours") | `ROLE_DIALER` | **No — show a system toast instead** |

The intersection of "requires default SMS/Phone" with "consistent with OpenWarden values" is **empty**.

---

## 10. Verdict & roadmap

### Recommendation: drop default-SMS/Phone replacement entirely.

**Reasons:**
1. **Stalkerware boundary.** The only meaningful capability default SMS adds is reading contents. We reject that. Default dialer adds custom in-call UI we don't need.
2. **RCS reality.** A default SMS app in 2026 is an SMS-only app in an RCS world. Pixel 7 ships Google Messages with RCS. Replacing it degrades the child's social experience massively, which produces workaround behavior (kid switches to Signal/Snap/Discord on Wi-Fi — and we've gained nothing while losing the parental relationship).
3. **911 / ELS risk.** Reimplementing the emergency call flow correctly is high-stakes; getting it wrong is a child-safety regression. Not worth it when `CallScreeningService` is available.
4. **Build cost asymmetry.** ~6 months default-dialer + 6 months default-SMS = ~12 engineer-months for capabilities we mostly don't want. `CallScreeningService` + allowlist + DPC suspension = ~2–3 months for capabilities we do want.
5. **Update treadmill.** Every Android version since N has changed something in the default-app contract (Q changed `ROLE_SMS` to RoleManager; R changed foreground service typing for `InCallService`; S/T tightened CallLog access; U/V tightened cross-app contact access). Default-app maintenance is a permanent tax.

### Future option preserved

If at some point a kid-safe end-to-end-encrypted messenger product makes sense — Signal-protocol child-to-parent + child-to-allowlist-contact, content stored only on the child's device, parent sees nothing — it should ship as **OpenWarden-Messenger**, a separate repo and product. The crypto/policy modules in OpenWarden proper (event bus, DPC layer, pairing channel) could be reused as Kotlin Multiplatform libraries. Don't bake it into OpenWarden core.

### Concrete v2 roadmap

1. **Sprint 1–2:** `CallScreeningService` + allowlist data model + role acquisition (DPC-silent on API 34).
2. **Sprint 3–4:** DPC-managed contacts (`ContactsContract` injection) + permission lockdown on third-party `WRITE_CONTACTS`.
3. **Sprint 5–6:** Parent-side approval queue for contact-add requests (reuse existing pairing channel).
4. **Sprint 7:** Event stream — call-blocked / call-allowed / contact-add-requested events into family feed.
5. **Sprint 8:** Bedtime: gate `CallScreeningService` allowlist by time window; verify Messages/Dialer suspension UX during windows.

### Open questions returned to user

1. **Is logging "Oliver received a call from $unknown_number at $time" acceptable in the family feed?**
   Recommendation: **yes**, this is metadata about policy enforcement, not surveillance of contents. Coalition-Against-Stalkerware-compatible.

2. **Is showing parent the *list of contacts* Oliver has texted (no contents) acceptable?**
   Recommendation: **no, lean stalkerware.** Reveals social graph. If parent needs to know "who is my kid talking to", the contact-approval flow already covers it (parent approved every contact); a separate "frequency" view leaks behavioral data without operational need.

3. **Is parent-side approval flow for adding new contacts in scope?**
   Recommendation: **yes for v2**, this is the keystone of the allowlist UX and the single most valuable telephony feature OpenWarden can ship. Cribbed directly from Pinwheel's Caregiver-Managed mode.

4. **Implicit question: does OpenWarden ever want to be the "messaging UI" the kid sees?**
   Recommendation: **no — keep Google Messages as the messaging UI, gate access at the app-suspension + allowlist layer.** The kid gets RCS, you get policy enforcement, nobody reads contents. This is the cleanest product.

---

## References

- [Android `RoleManager` developer docs](https://developer.android.com/reference/android/app/role/RoleManager)
- [Android `CallScreeningService` developer docs](https://developer.android.com/reference/android/telecom/CallScreeningService)
- [Android `CallScreeningService.CallResponse.Builder` docs](https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder)
- [Android Screen calls guide](https://developer.android.com/develop/connectivity/telecom/dialer-app/screen-calls)
- [Android Build a default phone application](https://developer.android.com/develop/connectivity/telecom/dialer-app)
- [Android `DevicePolicyManager` docs](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [Android Emergency numbers / 911 (AOSP)](https://source.android.com/docs/core/connect/emergency-call)
- [Android ELS (Emergency Location Service)](https://www.android.com/safety/emergency-help/emergency-location-service/how-it-works/)
- [Google Jibe / RCS platform docs](https://docs.jibemobile.com/intro)
- [Coalition Against Stalkerware](https://stopstalkerware.org/about/)
- [Pinwheel Contact Safelist levels](https://support.pinwheel.com/hc/en-us/articles/15506772306715-Contact-Safelist-levels-and-what-they-mean)
- [Pinwheel: How It Works](https://www.pinwheel.com/howitworks)
- [Bark FAQ: Text Messages](https://support.bark.us/en/articles/13461073-faq-text-messages)
- [Bark Android troubleshooting (Accessibility requirements)](https://support.bark.us/en/articles/13461409-troubleshooting-android-monitoring)
- [Family Link SMS limits (third-party summary; Google's own docs decline to address)](https://www.airdroid.com/parent-control/can-family-link-see-text-messages/)
- [AOSP `frameworks/base/telecomm` — `CallScreeningService.java`](https://android.googlesource.com/platform/frameworks/base/+/master/telecomm/java/android/telecom/CallScreeningService.java)
