# Defenses & V1 Hardening Plan

Companion to [`ATTACKS.md`](ATTACKS.md). Source: [`C:\src\openwarden-defenses.md`](../../openwarden-defenses.md) for full technical depth (~3500 words).

## Three defense tiers

| Tier | Mechanism | Examples |
|---|---|---|
| **Technical** | Code + DPC API + crypto | DISALLOW_*, sealed-box, StrongBox, FRP, FGS watchdog |
| **Procedural** | UX nudges + parent playbook | Cooldown timers, co-parent feed, audit log, 24h delay on phrase use |
| **Cannot defend** | Out of scope by design | Friend's phone, school Chromebook, smart TV, tantrum economics |

Be honest about which tier each attack lives in. Pretending a "cannot defend" is "technical" leads to security theater.

---

## V1 ship list — 15 defenses ranked

| # | Defense | Attack(s) prevented | Cost | Requires |
|---|---|---|---|---|
| 1 | DO provisioning + `setUserControlDisabled(self, true)` | A5, A6, A7, F4, G1, G3 (force-stop/uninstall/disable) | S | Android 14+ |
| 2 | Core DISALLOW_* (FACTORY_RESET, SAFE_BOOT, DEBUGGING_FEATURES, CONFIG_VPN, MODIFY_ACCOUNTS, OEM_UNLOCK, APPS_CONTROL, USB_FILE_TRANSFER, INSTALL_UNKNOWN_SOURCES, USER_SWITCH, ADD_USER, REMOVE_USER, CONFIG_DATE_TIME, MOUNT_PHYSICAL_MEDIA, CONFIG_TETHERING, CONFIG_MOBILE_NETWORKS, OUTGOING_BEAM) | Most A/B/C/E/F | S | DO |
| 3 | FRP via `setFactoryResetProtectionPolicy(parent_google)` | A2, A3, A4, G4 | S | API 30+ |
| 4 | StrongBox-backed Ed25519 identity + attestation cert pinning on parent | H3, H4, I3, swap attacks | M | Pixel 7 Titan M2 |
| 5 | **Sealed-box envelope (libsodium) on event log to parent pubkey** | Kid w/ root reads logs about themself | M | ionspin KMP libsodium |
| 6 | Signed policy bundle: Ed25519 + `policy_seq` monotonic + `not_before/not_after` + reject regressions | C8, H1 (replay) | S | Ed25519 |
| 7 | Append-only signed event log w/ hash chain (`prev_hash || event_data`) | Tamper-evident, fork detect | M | None |
| 8 | Heartbeat 5min over LAN (v1) / Iroh (v2); parent escalating silence alarms (15min/1h/6h/24h) | J1, N1, factory-reset detect | M | Transport |
| 9 | AVB runtime check via Key Attestation cert chain; refuse to operate if `verifiedBootState != VERIFIED` | rooted/tampered boot | M | StrongBox |
| 10 | FGS watchdog + secondary `:watchdog` process + AlarmManager `setExactAndAllowWhileIdle` | OOM kills, battery saver, N1 | M | None |
| 11 | Lock-task "lock now" w/ only OpenWarden activity allowlisted | K-class tantrum mitigation | S | DO `setLockTaskPackages` |
| 12 | `setApplicationHidden("com.android.settings", true)` gated by parent-signed token to flip back | Pure persistence + Settings spelunking | S | DO |
| 13 | Constant-rate cover traffic padding in heartbeat | Traffic analysis on event semantics | M | Heartbeat infra |
| 14 | Hardware-attested QR provisioning w/ nonce challenge | Phone swap during pairing | M | Key Attestation |
| 15 | BIP39 recovery phrase printed + FRP-account email noted + 7-day time-locked unlock | Parent loses phone | M | Local crypto + UI |

**Beyond these 15, pulled from v2 to v1 per ATTACKS.md analysis:**

| # | Defense | Attack | Cost |
|---|---|---|---|
| 16 | **Install-approval flow** (intercept `PackageInstaller` callback) | D8 (Play web install) | M |
| 17 | **Pin Private DNS to a *public filtering* resolver** (`DnsFloor`, ADR-016) — `setGlobalPrivateDnsModeSpecifiedHost(family.cloudflare-dns.com/parent-selected)`, never localhost/OFF/OPPORTUNISTIC; `DISALLOW_CONFIG_PRIVATE_DNS` locked; re-asserted on boot/connectivity/timer | C2 (DoH in browser), **K3** (induced resolver failure → unfiltered) | M |
| 18 | **Pin `setAlwaysOnVpnPackage(OpenWarden)`** | C4 (Always-On hijack) | S |
| 19 | Monotonic clock for window enforcement (`SystemClock.elapsedRealtime`) + signed parent timestamps | F3 (NTP spoof even if DATE_TIME blocked fails) | S |
| 20 | **Co-parent visibility feed** — any rule change pushed to all parent devices | K1, K2, divide-and-conquer | M |

20 defenses. Roughly **3-4 weekends extra** vs the original v1 plan but defeats the top behavioral + technical attacks.

---

## Mapping: attack → defense

| Attack ID | Attack | Defense # | Tier |
|---|---|---|---|
| A1 | Safe Mode | 2 (DISALLOW_SAFE_BOOT) | Tech |
| A2 | Factory reset Settings | 2 + 3 (FRP) | Tech |
| A3 | Recovery wipe | 3 (FRP) | Tech |
| A4 | Fastboot -w | 3 (FRP) + bootloader-locked | Tech |
| A5 | adb dpm clear | 2 (DEBUGGING_FEATURES) + ADB_ENABLED=0 at provisioning | Tech |
| A6 | Chronolink | 2 (DEBUGGING_FEATURES) | Tech |
| A7 | Boot-window race | persistent DO config (not re-applied in app code) | Tech (design) |
| B1 | OEM unlock | 2 (OEM_UNLOCK) + bootloader-locked | Tech |
| C2 | DoH in browser | 17 (force private DNS) | Tech |
| C4 | VPN slot hijack | 18 (setAlwaysOnVpnPackage) | Tech |
| C5 | Hotspot to escape parent LAN | local enforcement already independent | Tech (design) |
| C6 | Block parent sync | 8 (heartbeat silence alarms) + policy expiry → strict baseline | Tech |
| C7 | mDNS spoof | 6 (Ed25519 sig verify) | Tech |
| C8 | Bundle replay | 6 (policy_seq + not_before/not_after) | Tech |
| D1 | In-app WebView (Discord/Roblox) | 17 (DNS filter) + parent blocklist guidance | Tech + Procedural |
| D5 | Google Cast | n/a | **Cannot defend** |
| D8 | Play web install | 16 (install-approval) | Tech |
| D9 | PWA from Chrome | enterprise policy block | Tech |
| E3 | Guest user | 2 (USER_SWITCH) | Tech |
| E4 | Parent account takeover | parent strategy + biometric default | Procedural |
| F1/F2 | Clock rollback | 2 (CONFIG_DATE_TIME) + 19 (monotonic clock) | Tech |
| F3 | NTP spoof | 19 (monotonic clock + signed parent ts) | Tech |
| F4 | Force-stop OpenWarden | 1 (setUserControlDisabled) | Tech |
| G2 | Storage fill → fail-open | fail-closed posture mandatory | Tech (design) |
| K3-dns (research/07) | Induced resolver failure → unfiltered DNS | 17 (public filtering floor — never localhost/OFF/OPPORTUNISTIC; `DnsFloor`, ADR-016) | Tech (design) |
| G4 | Backup restore | 3 (FRP) | Tech |
| H1 | Replay old bundle | 6 (policy_seq) | Tech |
| H2 | Sig stripping / downgrade | fail-closed on verify error | Tech (design) |
| H3 | Pubkey substitution | 4 (StrongBox pin) + recovery-phrase-gated rotation | Tech |
| H4 | Extract parent privkey | hardware-backed Keystore + parent lock-screen | Tech |
| H6 | Recovery phrase shoulder-surf | 15 (24h delay non-emergency) + physical OPSEC | Procedural |
| I3 | OOBE reprovision window | provisioning script completes atomically | Tech (design) |
| J1 | LAN jam | 8 (heartbeat silence alarms) | Tech |
| K1 | "Homework needs Discord" | 20 (co-parent feed) + cooldown on new grants | Procedural |
| K2 | "Phone broken, factory-reset" | 15 (24h delay) + audit log | Procedural |
| K3 | Phrase shoulder-surf | physical OPSEC | **Cannot defend** (parent strategy) |
| K-divide | Divide-and-conquer parents | 20 (co-parent feed) | Procedural |
| K-persistence | Ask 10 times | cooldown timer on repeated requests | Procedural |
| L1 | YouTube PiP | accept, document | Procedural |
| L3 | Google Cast | accept, document | **Cannot defend** |
| M1-3 | Chromebook / console / library | accept, document | **Cannot defend** |
| N1 | OTA breaks FGS | 10 (watchdog) + post-OTA self-test + 8 (silence alarm) | Tech |
| O2 | Roblox social/WebView | 17 (DNS filter) + parent education | Tech + Procedural |
| O3 | Discord WebView | 17 (DNS filter) + parent education | Tech + Procedural |
| Kid §1.7 | PIN shoulder-surf | biometric default + randomized keypad | Tech |
| Kid §2 | Friend's phone | n/a | **Cannot defend** |
| Kid §3.3 | YouTube Shorts as TikTok | force-YouTube-Kids mode (v2) | Tech |
| Kid §3.4 | Renamed app icon | block third-party launchers + enforce stock launcher | Tech |
| Kid §3.5 | Calculator vault apps | vault-app blocklist | Tech |
| Kid §4.2 | Sneak phone after bedtime | hard bedtime lock w/o PIN unlock | Tech |
| Kid §5.6 | Browser incognito | block via Chrome enterprise policy as DO | Tech |
| Kid §7.4 | Find unsuspended app via search | allowlist deny-by-default (`setPackagesSuspended` → escalate to `setApplicationHidden`), fail-closed verify-or-lock + block Settings → Apps launches *(#12, ADR-022)* | Tech |
| B1 (research/07) | Blocklist beaten by clone / dual-app / repackaged APK / **Private Space** | allowlist-only launch (deny-by-default, fail-closed) + `DISALLOW_ADD_MANAGED_PROFILE` (always) + `DISALLOW_ADD_PRIVATE_PROFILE` (API 35+) + watchdog profile-count detection → `lockNow()` containment *(#12, ADR-022)* | Tech |

---

## Lock-screen / QuickSettings / camera / ambient-leak surfaces (#21-28)

These defenses close the web-leak and privacy-leak surfaces exposed on the Pixel lock
screen and ambient UI. They are implemented in `LockScreenLockdown.kt`
(`child-android/app/src/main/kotlin/com/openwarden/child/`). Items marked
**Config/playbook** have no DPM API and must be handled by the parent at setup time.
Integration into the enforcement sequence is tracked in issue #8.

| # | Surface | Attack | Mitigation | Mechanism |
|---|---|---|---|---|
| 21 | Lock-screen camera shortcut | Tap camera icon on keyguard → Camera app → Lens icon → image web-search, no unlock required | Disable via `KEYGUARD_DISABLE_SECURE_CAMERA` | `LockScreenLockdown.apply()` / `disableCameraOnKeyguardOnly()` |
| 22 | Lock-screen widgets | Widgets (calendar, weather, notification summaries) expose schedule / message data without unlock | Disable via `KEYGUARD_DISABLE_WIDGETS_ALL` | `LockScreenLockdown.apply()` |
| 23 | Smart Lock / trust agents | On-body detection, trusted-Bluetooth, or trusted-place keeps the lock screen bypassed indefinitely | Disable via `KEYGUARD_DISABLE_TRUST_AGENTS` | `LockScreenLockdown.apply()` |
| 24 | Notification content on lock screen | Message previews, OTP codes, and chat names visible to a casual observer without PIN | Disable via `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` | `LockScreenLockdown.apply()` |
| 25 | Quick Settings tiles on lock screen | QS tiles (Hotspot, Airplane, Cast, Screen Record) actionable without unlock on some Pixel builds | Remove sensitive tiles via Settings → Display → Quick Settings (parent playbook); no DPM API for per-tile removal | **Config/playbook** |
| 26 | Picture-in-Picture overlay persistence | App minimised to PiP (e.g. YouTube) floats over every screen including bedtime lock; kid can continue watching | Disable PiP permission per-app: Settings → Apps → [app] → Advanced → Picture-in-picture → Don't allow | **Config/playbook** |
| 27 | Google Assistant from lock screen | "Hey Google" or Assistant gesture from keyguard can answer questions, read notifications, or open URLs without unlock | Disable Assistant: Settings → Apps → Default apps → Digital assistant → set to None; no DPM API for ambient Assistant disable | **Config/playbook** |
| 28 | Quick Tap (Pixel back-tap gesture) | Double-tap rear of device triggers configured action (screenshot, Assistant, app launch) from lock screen without unlock | Disable: Settings → System → Gestures → Quick Tap → toggle off; no DPM API | **Config/playbook** |

**Note on `KEYGUARD_DISABLE_FEATURES_ALL`:** This catch-all constant is intentionally NOT
used. It disables undocumented flags and can suppress the PIN/pattern prompt itself.
`LockScreenLockdown` uses the four explicit flags above — each mapped to a specific
attack surface — and no others.

**Note on global camera disable:** `DevicePolicyManager.setCameraDisabled(admin, true)`
globally disables the camera for all apps (not just the keyguard shortcut). This is
a heavier parent-configurable policy, tracked separately from the keyguard flag.
`disableCameraOnKeyguardOnly()` uses only `KEYGUARD_DISABLE_SECURE_CAMERA`.

---

## Encryption-to-parent-device design (the user's vision, technical answer)

Combination of 3 patterns from defenses report:

**Pattern A (StrongBox key wrapping):** Child generates Ed25519 + X25519 keypairs in StrongBox (Titan M2). Keys never leave hardware. `setIsStrongBoxBacked(true)` + `setUnlockedDeviceRequired(true)`.

**Pattern B (sealed-box envelope) — the answer to "kid w/ root can't read logs":**
1. At pairing, child receives parent's X25519 pubkey. Pinned in StrongBox-wrapped storage.
2. Every event child logs is encrypted via `crypto_box_seal(event, parent_pub)` — libsodium sealed box, ephemeral sender key.
3. Child writes events; **cannot decrypt them**. Ephemeral sender privkey destroyed after sealing.
4. Events queue locally as opaque ciphertext blobs until sync to parent.
5. Combined with constant-rate cover traffic (#13) → kid w/ root sees fixed-size opaque blobs at fixed intervals. No content, no frequency-analysis leak.

**Pattern E (remote attestation):** At pairing + periodically, parent sends nonce challenge. Child generates StrongBox-attested keypair using nonce as challenge. Returns `(attestation_cert_chain, child_pub)`. Parent verifies chain rooted in Google → genuine Pixel + locked bootloader + verified boot GREEN. Defeats device-swap attacks.

**What this gives:**
- Kid w/ root sees only ciphertext for event log
- Kid w/ root cannot forge events (no parent privkey)
- Kid cannot replace parent pubkey (StrongBox-pinned + recovery-phrase-gated rotation)
- Parent can confirm child phone is genuine + unrooted via attestation
- Tamper attempts surface as `seq` jumps + heartbeat silence

**What this does NOT give:**
- Cannot prevent kid from physically using phone w/ legit PIN (kid knows PIN; that's normal use)
- Cannot prevent factory reset (FRP makes the reset useless, not impossible)
- Cannot encrypt policy bundle itself — child needs plaintext policy to enforce. Bundle is plaintext-signed, never encrypted.

---

## Anti-patterns (look defensive, aren't)

- **Hiding the app icon.** Kid finds it in Settings → Apps. Use `setUserControlDisabled` + admin message instead.
- **Encrypting policy bundle to kid-PIN-derived key.** Kid knows PIN. Plaintext-signed policy correct; encrypt event log instead.
- **Detecting root via Play Integrity at every op.** Battery cost + FOSS violation. One provisioning STRONG check + periodic Key Attestation refresh sufficient.
- **Custom kernel to disable ADB at boot.** Requires unlocked bootloader, defeats AVB, broadens attack surface. Stay stock.
- **Blocking emergency dialer.** Never. Ethical + Android-blocks-this-anyway.
- **Time-locked decryption via VDFs.** Research-grade; brittle; battery cost. Use sealed-box.
- **Mandatory cloud check-in to operate.** Kills offline use; SaaS dep; violates pledge.

## Defenses we can't ship (FOSS constraints)

- **Play Integrity STRONG as a hard requirement** — requires Google Play Services. Ship as *optional* corroborating check.
- **SafetyNet** — already retired Jan 2025.
- **Knox-style closed TEE** — Samsung-only proprietary.
- **OEM-signed system overlay** for deeper Settings interception — requires AOSP fork + vendor partnership.

## Defenses requiring Pixel hardware (acknowledged)

- StrongBox + Titan M2 (Pixel 6+, Pixel 7+)
- Key Attestation w/ Google-root cert chain
- Verified Boot GREEN state w/ Google signing key
- Pixel-specific `DISALLOW_OEM_UNLOCK` enforcement details

**V1 target: Pixel 7 only.** Pixel 6/8/9 likely work. Non-Pixel Android = per-device attestation root config = v3+ scope.
