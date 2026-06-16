# OpenWarden Research: Peer-to-Peer Parental Control on Pixel 7

Research compiled for an OSS, no-SaaS parental-control system: Kotlin Device Owner DPC on child Pixel 7, Flutter parent app on macOS/Windows/Android/iOS, Tailscale P2P transport.

---

## 1. Existing apps: paradigms, what works, what fails

| Product | Control model | Bypass resistance | Where it fails |
|---|---|---|---|
| **Google Family Link** | DPC profile owner on child Google account + supervised supervision API. Not Device Owner. | Medium. Tied to Google account; depends on Play Services. | Date-rollback used to work; kids reinstall via secondary account; "Chronolink" ADB tool removes screen-time if Dev Options ever enabled. Google [reversed the "kids can disable controls" policy](https://www.aol.com/articles/google-reverses-key-parental-control-231601090.html) after backlash. |
| **Apple Screen Time** | OS-integrated MDM-ish; FamilyControls framework. | Medium. Still has the [post-reboot 30-60s grace window](https://www.cloudwards.net/how-to-hack-screen-time/) and date/time tricks open as of late 2025. FaceID has to be disabled to require Screen Time PIN for revocation. | Same-device passcode shoulder-surfing; iCloud unlink; "shared" devices reset. |
| **Bark** (app + Bark Phone hardware) | Accessibility-service text/social monitoring + Samsung KME-locked hardware. | High on Bark Phone (locked carrier + locked Samsung Knox). Low for app-only on stock Android. | Bark monitors but [does not block](https://canopy.us/blog/qustodio-vs-bark-parental-control-comparison/) websites; iOS setup uses encrypted-backup decryption over Wi-Fi (fragile). |
| **Qustodio** | VPN-filter + accessibility service + companion DPC. | Medium. Filters DNS at a local VPN. | A real VPN beats it; kid factory-resets. |
| **Kaspersky Safe Kids** | VPN+accessibility, GPS. | Low. Affordable but trivially uninstalled on Android if not Device Owner. | Battery hog; constant accessibility-permission re-grants. |
| **Pinwheel Phone** | Custom Android launcher + custom OS image, app allowlist. No social media. | High. [Samsung A36 hardware](https://www.bark.us/learn/bark-phone-vs-pinwheel-phones/), modes ("school", "everything", "family time"). | Closed ecosystem; subscription; no monitoring. |
| **Headwind MDM** (OSS) | Real Device Owner via custom launcher. Apache 2.0. Java/Tomcat server. | High when DO-provisioned. | Designed for enterprise fleets; needs server. Worth studying: [h-mdm/hmdm-android](https://github.com/h-mdm/hmdm-android). |
| **Hexnode / Microsoft Family Safety / Aura / OurPact** | Mix of MDM and VPN-filter. | Varies; all SaaS. | None operate offline; all require their cloud. |

**OSS projects worth reading** ([GitHub topic: parental-control](https://github.com/topics/parental-control)):
- [`googlesamples/android-testdpc`](https://github.com/googlesamples/android-testdpc) — Google's reference DPC. Canonical source for every DevicePolicyManager API.
- [`h-mdm/hmdm-android`](https://github.com/h-mdm/hmdm-android) — production-grade DO launcher.
- [`childscreentime/cst`](https://github.com/childscreentime/cst) — focused screen-time tooling.
- `xMansour/KidSafe`, `KidShield` — design patterns to learn from, less rigor.

**Pattern takeaway:** SaaS controls fail when the cloud auth ever lapses or the kid uninstalls the agent. Hardware-locked phones (Bark, Pinwheel) work because the *user can't escape Device Owner.* Your DO approach is the right axis.

---

## 2. Android Device Owner pitfalls (Pixel 7 specifics)

### Provisioning
- DO can **only** be set on a factory-fresh device with **no Google accounts present** ([Android Enterprise FAQ](https://bayton.org/android/android-enterprise-faq/can-i-set-device-owner-without-factory-reset/)). One wrong tap on "Add account" during setup wizard and you must `fastboot -w` and retry.
- For Pixel 7 you'll use `adb shell dpm set-device-owner com.openwarden/.AdminReceiver` over USB during OOBE, or NFC/QR provisioning. Personal-use DO via `dpm` works on stock Pixels — Google hasn't closed this side door.
- **Gotcha:** if `device_provisioned=1` (Pixel auto-flips this after wizard completes), `dpm set-device-owner` fails with `IllegalStateException`. Run it before finishing setup.

### OTAs
- DPC **does survive OTAs**. The admin component is preserved across A/B updates ([system updates docs](https://developer.android.com/work/dpc/system-updates)).
- As DO you can `setSystemUpdatePolicy(AUTOMATIC | WINDOWED | POSTPONE)` — you control when. **Don't `POSTPONE` indefinitely**: 90-day max, security patches are mandatory after that.
- After a Pixel monthly OTA, your foreground service may not auto-restart until next boot; budget for re-binding.

### Play Integrity
- [SafetyNet retired Jan 2025; Play Integrity is now mandatory](https://www.yinkoshield.com/knowledge-center/checkpoint-architectures/safetynet-legacy/).
- Known DO issue: **freshly-enrolled DO devices return empty Integrity verdicts on first attest** (DPC Support Lib 23.9.0+ has the workaround). If you ever ship Play Integrity checks in your DPC, expect false-fails post-provision.
- May 2025: [hardware-backed signals are required](https://securityboulevard.com/2025/11/the-limitations-of-google-play-integrity-api-ex-safetynet-2/) for STRONG integrity. Fine on stock Pixel 7; will fail any "kid unlocked the bootloader" recovery path.

### Suspend vs Hide vs Disable
| API | UX | Bypass |
|---|---|---|
| `setPackagesSuspended` | Icon visible & grayed, admin message on tap, notifications muted, can't start activities | Reboot doesn't break it; uninstall is blocked |
| `setApplicationHidden` | Icon gone from launcher, app data preserved | Settings → Apps still shows it; kid may "Enable" if a non-DO restriction missed |
| `PackageManager.setApplicationEnabledSetting(DISABLED_USER)` | Hard disable | Survives reboot; rougher UX |

For OpenWarden, **`setPackagesSuspended` is the right primary tool** — kid sees *why* the app is blocked, you don't lose app data, and per-package admin messages let you say "Ask dad". Use `setApplicationHidden` for "this app shouldn't even exist for Oliver."

### User restrictions worth setting on day one
`DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`, `DISALLOW_CONFIG_VPN` (critical — kills VPN bypass), `DISALLOW_INSTALL_APPS` (Play store still works if you whitelist), `DISALLOW_USB_FILE_TRANSFER`, `DISALLOW_MODIFY_ACCOUNTS`, `DISALLOW_ADD_USER`, `DISALLOW_REMOVE_USER`, `DISALLOW_OUTGOING_BEAM`. **Do NOT set** `DISALLOW_OUTGOING_CALLS` — it can block 911 routing depending on dialer. Test on your bench Pixel before deploying to Oliver.

### Lock task / kiosk caveats
- `setLockTaskPackages(...)` whitelists; calling `Activity.startLockTask()` enters kiosk. ([Lock task docs](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)).
- **Emergency dialer still works** from the lock screen even in lock task — Android enforces 112/911 routing at the telephony layer. Verify on Pixel 7 with a test on a dev SIM (do not actually dial 911).
- Notifications and quick-settings are gone in lock task. The home button is gone. Means you lose access to the brightness slider unless you whitelist Settings (don't).

### BOOT_COMPLETED and battery
- [Android 14+ restricts FGS start from `BOOT_COMPLETED`](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Allowed FGS types: `dataSync`, `mediaProcessing`, etc. — pick `specialUse` and declare the property.
- DO apps get a partial exemption: `setApplicationsRestricted` + DO whitelist for battery optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is auto-granted to DO).
- Pixel's adaptive battery is *less* aggressive than OEM skins, but App Standby Buckets still apply unless your FGS is sticky. Use a `connectedDevice` FGS type — that's what Tailscale uses.

---

## 3. Common kid bypass techniques (and your counters)

| Technique | Defense in OpenWarden |
|---|---|
| Sideload via ADB, Developer Options re-enabled | `DISALLOW_DEBUGGING_FEATURES` + DO-blocked Settings panel for dev options |
| Browser-based game/social (escapes app blocks) | DNS-level filter inside DPC + content-category blocklist; no Chrome without a filtered profile |
| VPN to bypass DNS | `DISALLOW_CONFIG_VPN`; also set Always-On VPN to your *own* loopback or Tailscale, locking the slot |
| Recovery / fastboot reset | `DISALLOW_FACTORY_RESET` blocks Settings path. Bootloader on Pixel is OEM-unlock-locked by default; **set `setFactoryResetProtectionPolicy(...)` so even a fastboot wipe demands your Google account** (FRP). Critical. |
| SIM in another phone | Out of scope; consider eSIM provisioning. Acceptable risk. |
| Cached web Play Store creds | DO-managed Google account; revoke "Add account" via `DISALLOW_MODIFY_ACCOUNTS`. |
| Screen recording to evade monitoring | `setScreenCaptureDisabled(true)` for sensitive apps |
| Chronolink / ADB screen-time removal | Blocked by `DISALLOW_DEBUGGING_FEATURES` from boot. |
| "Borrowed friend's phone" | Out of scope; you cannot solve this — accept it. |

Reddit-attested kid moves: clock-rollback, secondary user accounts ([Family Link Bypass 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)), reinstall apps from web store, embedded browser games inside whitelisted apps. The last one is the sneakiest — apps like Discord embed full browser views; blocklist Discord or accept the leak.

---

## 4. Transport pitfalls (Tailscale)

- **CGNAT both sides:** [Cellular carriers run symmetric NAT behind CGNAT](https://www.sitepoint.com/tailscale-peer-relays-nat-traversal-derp/) — hole-punch usually fails. You'll fall back to DERP. DERP is HTTPS:443, so it works through any captive portal, but adds 30-80ms and depends on Tailscale's infra (a SaaS hidden in your "no SaaS" plan — acknowledge it).
- **Battery:** [Tailscale on Android is meaningfully worse than vanilla WireGuard](https://tailscale.com/docs/reference/troubleshooting/mobile/battery-drains) because of always-on DERP keepalives + NAT-probe traffic. Realistic cost: 5-15% extra daily drain. Don't use Tailscale as an exit node for the child's traffic — that doubles it. Use it for control-plane RPC only; do DNS filtering locally on-device.
- **Auth keys:** Pre-approved [auth keys](https://tailscale.com/docs/features/access-control/auth-keys) let pairing be one-tap. For OpenWarden, generate a one-time, reusable=false, ephemeral=false, tagged auth key on the parent, render as QR, child DPC scans on first boot. Rotate annually.
- **Tailnet Lock** for end-to-end key trust without trusting Tailscale's coordination server — worth enabling for this use case.
- **ACLs:** restrict child node to only reach the parent node tag, no LAN, no exit-node usage.
- **Offline child device:** all policy must be enforced locally. Treat Tailscale as the *control channel for changes, alerts, and remote unlock requests* — never as the policy itself. Cache last-known policy on disk, signed by the parent's Ed25519 key.

---

## 5. Flutter parent-app considerations

- **iOS background:** very limited. You'll need APNs silent push to wake the app for alerts from the child. No central push server in your model → you must use either (a) a self-hosted ntfy/UnifiedPush relay (still kinda SaaS), or (b) accept that iOS parent only sees alerts when foregrounded. **Recommendation: ship Android-first parent app; iOS in v2 with `flutter_local_notifications` + APNs via a free ntfy.sh-style relay.**
- **macOS App Store sandboxing:** keychain entitlement required, network entitlement required. Tailscale will need a Network Extension — Flutter can't ship one directly; ship outside MAS for v1.
- **Secure storage:** [`flutter_secure_storage`](https://pub.dev/packages/flutter_secure_storage) is fine for all four OSes but parity is rough — Windows uses DPAPI, macOS uses Keychain (needs Keychain Sharing entitlement), Android uses EncryptedSharedPreferences, iOS uses Keychain. Test key rotation on Windows specifically (DPAPI is bound to the Windows user profile — backup/restore breaks it).
- **Native bits you'll keep out of Flutter:** Tailscale daemon (use platform channels to wrap the Go binary), iOS Network Extension (Swift), Android FGS (Kotlin). Flutter is fine for UI, state, policy editor, alert feed. The "parent issues a remote-unlock token" cryptography stays in pure Dart with `cryptography` package (NIST-aligned).

---

## 6. Legal / ethical / safety

- **COPPA** (US): applies to *services collecting data from under-13 users*. Since OpenWarden is OSS, runs locally, and the parent owns the data, COPPA largely doesn't bind you. But: if you ever ship a hosted demo, COPPA applies. Document "no data leaves the tailnet."
- **Stalkerware policy:** [Google Play](https://support.google.com/googleplay/android-developer/answer/14745000) only allows parental-monitoring apps if (a) marketed only for child monitoring, (b) persistent notification while running, (c) clear icon. Since you're not shipping via Play, you avoid this gate — but **emulate the spirit**: when the child taps the DPC icon, it should clearly explain what it does, in kid-readable language. Future-proofs you against ever shipping via Play.
- **Data minimization:** don't log keystrokes, don't log message contents. Log *events* (app launched, time spent, blocked attempt). Cap retention at 30 days local.
- **911 access:** never set `DISALLOW_OUTGOING_CALLS`; never block the system dialer package; never enter `startLockTask()` without telephony emergency intent allowlisted. The platform protects 112/911 routing but app-level blocks can still mask the dialer UI.
- **"Parent loses access" recovery:** mandatory. Three-part:
  1. Parent stores a printed BIP39 recovery phrase = root unlock key.
  2. DPC honors a signed "factory reset to unmanaged" command from that key.
  3. Time-locked unlock: holding power+vol-down at lock screen for 60s prompts a 7-day recovery countdown visible to parent. If unchallenged, child device exits DO mode.

This solves the "dad gets hit by a bus" case without weakening day-to-day control.

---

## 7. Design ideas worth stealing (and inventing)

**Steal:**
- Family Link's **install-time approval flow** — child taps Install, parent gets push, approves/denies with one tap. Build this with Tailscale RPC, no Google needed.
- Pinwheel's **modes** ("school", "everything", "family time") — far better UX than a giant policy matrix.
- Bark's **alert summaries** — daily digest beats a 50-event feed.
- Apple Screen Time's **request-more-time** flow — kid asks for 15 more min on YouTube, parent approves from any device.

**Novel for OpenWarden:**
- **Client-side AI moderation** with a small on-device model (Gemma Nano via [AICore](https://developer.android.com/ai/aicore) on Pixel 7+8). Classify screenshots periodically; flag locally; alert parent over Tailscale. Zero data egress, no SaaS.
- **DNS filter inside the DPC** (no separate VPN slot needed). Use Android's `setGlobalPrivateDnsMode(PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, "your.dns")` — pointed at a NextDNS profile *or* a local resolver.
- **Time bank** rather than time limits: Oliver earns 10 min of YouTube per book chapter logged, redeemed via parent-signed token.
- **Signed policy bundle**: parent edits policy offline → signs with Ed25519 → child applies. Means even if Tailscale's down, *new* policy can transit via a QR code shown on dad's laptop → camera on Pixel.
- **"Why am I blocked?" screen** with the actual rule that fired, kid-readable. Reduces conflict; teaches the system.
- **Build for one kid first** (Oliver). Don't generalize to "multi-child households" until you've lived with v1 for 6 months. The OSS parental apps that ship dead try to be Qustodio.

---

## 8. MVP recommendations

### v1 (ship in 8-12 weeks)
1. **Kotlin DPC, DO-provisioned on Pixel 7** via `adb dpm set-device-owner`. Provide a one-page README for parents to do this.
2. **Core restrictions only:** `DISALLOW_FACTORY_RESET`, `DISALLOW_CONFIG_VPN`, `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`, FRP locked to parent's Google account.
3. **App allowlist** using `setPackagesSuspended` (not hidden). Admin message: "Ask dad — tap to send request."
4. **Time windows** per app (Mon-Fri 4-6pm Minecraft, that kind of thing). Stored as signed bundle; enforced locally; no Tailscale dependency.
5. **Tailscale control channel** (Android parent app first). Parent sees: child device online/offline, app usage today, pending requests. Parent can: extend time, approve install, lock now, unlock now.
6. **Recovery phrase printable PDF.**
7. **Emergency dialer left explicitly untouched.** Document the test you ran.

### v2
- Flutter parent on macOS/Windows.
- DNS filter (private DNS pointed at a self-hosted resolver).
- Install-approval flow.
- Time-bank economy.

### v3
- iOS parent app + ntfy relay for push.
- On-device Gemma classifier.
- Multi-child support.

### Sharp edges to avoid baking in
- **Don't put policy in Tailscale.** Tailscale is transport; policy is local, signed, cached. Otherwise an offline kid = unmanaged kid.
- **Don't depend on Google account for control.** DO survives account changes; design around that.
- **Don't ship without the recovery phrase flow.** You will lock yourself out during dev. Ask me how I know any parental-tool builder learns this.
- **Don't add monitoring features in v1.** Resist scope creep into Bark territory. Control first; visibility later; *content* monitoring maybe never — that's where stalkerware concerns live.
- **Don't auto-update policy without a confirm.** A misclick on the parent app that nukes Oliver's homework app at 7pm is a trust-destroying event. Stage + confirm.
- **Pin your Android target SDK aggressively** (target latest, compile latest). DO APIs change quarterly; staying behind invites surprise breakage at next OTA.

---

## Key references
- [Android Enterprise DPC docs](https://developer.android.com/work/dpc/security)
- [Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)
- [System updates as DO](https://developer.android.com/work/dpc/system-updates)
- [FGS restrictions in Android 14+](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [googlesamples/android-testdpc](https://github.com/googlesamples/android-testdpc) — the canonical reference
- [Headwind MDM source](https://github.com/h-mdm/hmdm-android)
- [Tailscale connection types & DERP](https://tailscale.com/docs/reference/connection-types)
- [Tailscale auth keys](https://tailscale.com/docs/features/access-control/auth-keys)
- [Tailscale mobile battery troubleshooting](https://tailscale.com/docs/reference/troubleshooting/mobile/battery-drains)
- [Play Integrity migration impact](https://securityboulevard.com/2025/11/the-limitations-of-google-play-integrity-api-ex-safetynet-2/)
- [Google Play stalkerware policy](https://support.google.com/googleplay/android-developer/answer/14745000)
- [Family Link bypass methods 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)
- [Screen Time bypass methods](https://www.cloudwards.net/how-to-hack-screen-time/)
- [Bayton: provisioning without factory reset](https://bayton.org/android/android-enterprise-faq/can-i-set-device-owner-without-factory-reset/)
- [flutter_secure_storage](https://pub.dev/packages/flutter_secure_storage)
