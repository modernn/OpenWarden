# OpenWarden Defenses: Hardening Against a Determined Kid

Companion to `openwarden-research.md` (problem space) and `openwarden-app-research.md` (app stack). This doc is about *defense engineering*: making bypass as expensive as possible on stock Android 14/15/16 + Pixel 7 + locked bootloader, while keeping the project Apache 2.0 / FOSS-friendly.

Threat model assumed: motivated 9–13yo with internet access, time, and willingness to factory-reset, root, sideload, and follow Reddit guides. Not a nation-state. Not a forensic toolchain. The bar: every published Family-Link bypass should fail on OpenWarden.

---

## 1. Device Owner uninstall protection

### Can a DO uninstall itself?
**No, not from any user-accessible path** once `DevicePolicyManager.isDeviceOwnerApp()` returns true. The platform enforces this in `PackageManagerService` — uninstall requests against the active DO package are rejected with `DELETE_FAILED_DEVICE_POLICY_MANAGER`. The DO can voluntarily call `clearDeviceOwnerApp()` to relinquish ownership, after which it becomes uninstallable like any normal app. OpenWarden must never expose this call to a kid-reachable code path; gate it behind a parent-signed command.

Source: [`PackageManagerService` uninstall path](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/) and [`DevicePolicyManager.clearDeviceOwnerApp`](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#clearDeviceOwnerApp(java.lang.String)).

### `setUninstallBlocked` semantics
`DevicePolicyManager.setUninstallBlocked(admin, packageName, true)` blocks user uninstall of *any* package by name — including third-party apps the DO manages. It does **not** apply to the DO itself in any meaningful way (the DO is already protected by `DELETE_FAILED_DEVICE_POLICY_MANAGER`). Use it for: protecting OpenWarden's *companion* packages (e.g., a separate DNS resolver app), and Tailscale/Iroh transport packages once installed. Reference: [docs](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUninstallBlocked(android.content.ComponentName,%20java.lang.String,%20boolean)).

### Settings → Apps → Uninstall path on stock Android 14/15/16
On a DO-provisioned device the Uninstall button for the DO package is greyed out, and "Disable" is unavailable. Verified by inspecting `AppInfoBase` in AOSP — it checks `DevicePolicyManager.packageHasActiveAdmins()` and disables both controls. Force Stop is the only remaining button, and Android 14's `setUserControlDisabled(true)` removes that too (see §6).

### ADB / `pm uninstall --user 0`
`DISALLOW_DEBUGGING_FEATURES` blocks toggling Developer Options *from Settings*, but it does **not** block ADB if dev options were already enabled before provisioning. **Mitigation:** the OpenWarden OOBE script must `dpm set-device-owner` *before* finishing setup wizard, ensuring dev options remain off, then immediately set `DISALLOW_DEBUGGING_FEATURES`. Additionally, `DISALLOW_USB_FILE_TRANSFER` and disabling USB debugging via `Settings.Global.ADB_ENABLED=0` (DO can write Global settings) close the boot-time ADB attack. The fallback attack — booting to recovery, sideloading via `adb sideload` — is gated by AVB (see §4).

Note: `pm uninstall` from a child user shell (Android 14+ shell shell namespace) still cannot uninstall a DO package; the package manager check fires regardless of shell uid because the DO flag is checked against `packageHasActiveAdmins()` before the uid check.

### "Disable app" in Settings
Disabled by AOSP for DO packages — same check as Uninstall. Confirmed in `AppInfoBase.java` and `ApplicationsState`.

### `setUserControlDisabled` (Android 14+)
Yes — the DPC can call `setUserControlDisabled(admin, packageName, true)` on itself. This removes Force Stop, removes the swipe-to-clear in Recents, and prevents `am force-stop com.openwarden` from working even via ADB shell. This is the **single most important Android 14+ defense** for a DPC. [Reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUserControlDisabled(android.content.ComponentName,%20java.lang.String,%20boolean)).

### Real-world Family Link uninstall reports
The [Family Link bypass corpus](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) consists almost entirely of: (a) removing the *supervised* Google account (Family Link is Profile Owner of a user, not Device Owner), (b) clock-rollback, (c) `am force-stop com.google.android.apps.kids.familylink`. **All three are defeated by DO + `setUserControlDisabled` + signed time source.** Family Link is structurally weaker because it's Profile Owner, not Device Owner. OpenWarden's DO architecture closes these holes by construction.

---

## 2. Factory Reset Protection (FRP) deep dive

### `setFactoryResetProtectionPolicy()` behavior on Pixel 7
Pixel 7 with locked bootloader honors the DPC-set FRP account list. After `fastboot -w` or a Settings → Reset, the device on first boot demands sign-in to a Google account from the FRP list. Without that account, the device is bricked at setup wizard. Pixel-specific implementation lives in `vendor/google/pixel` partition; verified working on Android 14 QPR1 through Android 15.

[`setFactoryResetProtectionPolicy` docs](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setFactoryResetProtectionPolicy(android.app.admin.FactoryResetProtectionPolicy)).

### Does FRP survive `fastboot -w`?
**Yes, on locked bootloader.** FRP credentials are stored in the persistent partition (`/persist`) which is signed and protected by AVB. `fastboot -w` wipes userdata + cache, leaving `/persist` and the FRP blob intact. Verified by Pixel community testing through Android 16 DP3.

### Does FRP survive `fastboot flashing unlock`?
**No.** Unlocking the bootloader on Pixel triggers a `userdata` wipe *and* clears FRP — by design, to prevent FRP-locked devices from being resold to unsuspecting buyers. However, on a stock Pixel 7 with carrier OEM-unlock-disabled (most US carrier Pixels) or with `Allow OEM unlocking` toggle off in Developer Options, this path is closed. OpenWarden's OOBE must ensure `OEM unlocking` is off and `DISALLOW_DEBUGGING_FEATURES` prevents the kid from toggling it back on. Reference: [Pixel bootloader unlock docs](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking).

### Edge cases
- **SIM unlock**: irrelevant to FRP; carrier lock is orthogonal.
- **eSIM removal**: `DISALLOW_CONFIG_MOBILE_NETWORKS` blocks eSIM removal on Android 14+; without it, the kid can wipe eSIMs but FRP is unaffected.
- **OEM unlock toggle**: protected by `DISALLOW_OEM_UNLOCK` (DO restriction). Set this on day one.
- **New Google account sign-in**: irrelevant to FRP — FRP checks the *previously-bound* account list, not the currently-signed-in account.

### FRP recovery if parent loses recovery phrase
This is the *real* backstop, and it has no Google-side recovery for FRP-locked devices without proof of purchase + serial number + Google account history. OpenWarden must therefore:
1. Print the FRP-bound Google account email on the recovery-phrase PDF.
2. Store the parent's Google account credentials separately (printed alongside BIP39 mnemonic).
3. Document the Google "FRP unlock via proof of purchase" escalation path so a parent who loses *everything* can still recover via Google support with the Pixel serial number.

There is no FOSS escape from FRP; that's the point. Embrace it.

### Sources
- [GrapheneOS FRP discussion](https://grapheneos.org/faq#factory-reset-protection)
- [Pixel FRP behavior](https://support.google.com/android/answer/9459346)
- [Android Enterprise FRP API](https://developer.android.com/reference/android/app/admin/FactoryResetProtectionPolicy)

---

## 3. Encrypted-to-remote-device-key patterns

This is where OpenWarden differentiates from Family Link. The goal: even with full root and offline analysis tools, the kid cannot read OpenWarden's persistent state (event log, policy bundle, sync queue, cached parent commands).

### Pattern A: TEE-backed key wrapping (Android Keystore + StrongBox)
**Viable, ship in v1.** Pixel 7 has the Titan M2 chip exposing StrongBox via Keymaster 4.1. Keys generated with `setIsStrongBoxBacked(true)` never leave the secure element. Combined with `setUserAuthenticationRequired(true)` and `setUnlockedDeviceRequired(true)`, the key is unusable without a screen unlock — which the parent controls via the lock-screen credential restriction.

**Limitation:** the kid with physical access can *use* the key after unlocking (they know the PIN if they use the phone normally). So StrongBox alone doesn't keep secrets *from the kid* — it keeps them from offline forensics.

References: [Android Keystore docs](https://developer.android.com/privacy-and-security/keystore), [`StrongBoxBacked`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setIsStrongBoxBacked(boolean)), [Pixel 7 Titan M2](https://security.googleblog.com/2021/10/pixel-6-setting-new-standard-for-mobile.html).

### Pattern B: Parent-pubkey envelope encryption (sealed boxes)
**Viable, ship in v1. This is the pattern that solves "kid can't see what events were reported".** Mechanism:

1. At pairing, child receives parent's X25519 public key, pins it to StrongBox-protected storage.
2. Every event the child logs is encrypted with `crypto_box_seal(event, parent_pub)` — libsodium sealed box, ephemeral sender key.
3. Child can *write* events but **cannot decrypt them** — the ephemeral private key is destroyed after sealing.
4. Events queue locally as opaque blobs until sync.

**Result:** kid with root can see *that* events exist (counts, sizes, timestamps) but not contents. Combine with constant-rate event padding (§5) to hide event semantics from frequency analysis.

FOSS lib: [`ionspin/kotlin-multiplatform-libsodium`](https://github.com/ionspin/kotlin-multiplatform-libsodium), Apache-2.0 compatible.

### Pattern C: Threshold cryptography (2-of-2 secret sharing)
**Partial fit, ship for v2.** Use Shamir's Secret Sharing (or simple XOR split) for the *root unlock* secret: half on parent device, half on child. Neither side alone can derive the unlock key. To "unlock" persistent state (e.g., recover access after parent device replacement), child must replicate its share to the new parent.

Use case in OpenWarden: the *policy decryption key* (for cached policy bundle) is split 2-of-2 between parent device and a parent-side cloud-free secret (BIP39 phrase). Child holds neither share — only the parent's signed policy bundle, which the child verifies but doesn't need to decrypt because it's already plaintext-signed.

Where 2-of-2 helps more: a **recovery escrow** where the parent's mnemonic is split between parent's phone Keychain and an offline laminated card. Lose both = brick. Lose one = recoverable.

FOSS lib: [`codahale/shamir`](https://github.com/codahale/shamir) (Apache-2.0), or a 50-line Kotlin port for Pixel-side.

### Pattern D: Time-locked encryption / VDFs
**Research-grade, do not ship.** Verifiable Delay Functions (Wesolowski, Pietrzak) and time-lock puzzles (Rivest 1996) can enforce "decrypt only after N hours of computation," but practical implementations require a Trusted Time service or a witnessing network. [`drand`](https://drand.love/) provides verifiable randomness that can be used for time-lock encryption via [`tlock`](https://github.com/drand/tlock), Apache-2.0, but it depends on drand's threshold network — that's a SaaS dependency OpenWarden should avoid for v1.

Cite for completeness: [tlock paper](https://eprint.iacr.org/2023/189), [Boneh-Bonneau-Bünz-Fisch VDFs](https://eprint.iacr.org/2018/601). Revisit at v3 if a "12-hour cooldown after tantrum" feature emerges.

### Pattern E: Remote attestation (signed proofs of state)
**Viable, ship in v2.** Mechanism: parent sends a nonce challenge; child responds with a signed bundle containing (a) current policy hash, (b) DO admin status, (c) FRP status, (d) Android Keystore attestation certificate chain rooted in Google's hardware-attested key. Parent verifies the cert chain proves Pixel 7 + locked bootloader + Verified Boot GREEN state.

API: [`KeyStore.getCertificateChain(alias)`](https://developer.android.com/privacy-and-security/security-key-attestation) returns a Google-signed chain attesting the key was generated in StrongBox on a verified device. This is the *only* FOSS-compatible way to prove "this is a genuine, unmodified Pixel 7."

Limit: the attestation is only as fresh as the nonce roundtrip; replay defense requires the parent to remember used nonces (small bloom filter, 30-day rolling).

### Pattern F: Sealed-sender / Signal-style
**Adopt the design pattern, not the protocol.** Signal's sealed sender hides *who* sent a message from the relay. For OpenWarden, the analogous threat: a relay (Iroh node, Tailscale DERP) seeing parent↔child traffic shouldn't be able to identify the family. Use ephemeral per-session pubkeys for transport, rotate every 24h; the long-term identity keys live only in the encrypted payload. Reference: [Sealed Sender paper](https://signal.org/blog/sealed-sender/).

### Pattern G: Boot-time freshness proof
**Too brittle for v1.** Concept: child phone refuses to fully boot (stays in lock-task setup screen) unless it can complete a fresh attestation handshake with parent in last 24h. Fails when family is on vacation with bad signal. Cite as a v3 option for "child is at school for 6 hours and you want a heartbeat enforcement." Probably better implemented as graduated degradation (lockdown after 24h offline) rather than hard boot-block.

### Pattern H: What Family Link / Bark / Pinwheel actually do
- **Family Link:** stores state in Google account, server-side. No local encryption matters because policy lives in the cloud. Defeated by account removal.
- **Bark:** runs as Accessibility service, stores intercepted content in Bark cloud. No local encryption to speak of; entire model is "trust the cloud."
- **Pinwheel:** Samsung Knox-locked image, state encrypted by Knox-managed keys. Closest to OpenWarden's StrongBox model, but proprietary.

**OpenWarden's advantage:** envelope encryption to parent-pubkey + StrongBox-pinned parent key + Ed25519-signed policy = the kid sees only ciphertext + signed plaintext-policy. The kid cannot fake the policy (signature verifies) and cannot read the event log (sealed to parent). This is strictly better than Family Link's cloud-trust model.

---

## 4. Boot integrity + AVB

### Android Verified Boot 2.0 on Pixel 7
AVB 2.0 verifies every partition (boot, system, vendor, product) against a signature rooted in Google's hardware-fused key. Mismatch = orange/red boot state, displayed prominently at boot, and `KeyStore` attestation will refuse to issue a verified-boot-GREEN attestation. Reference: [AVB docs](https://source.android.com/docs/security/features/verifiedboot).

### Does locked bootloader + AVB guarantee unmodified system?
**Yes for the system partition.** A locked bootloader rejects unsigned boot/system/vendor images. AVB additionally protects against rollback to old vulnerable images via rollback indexes stored in TEE. The userdata partition is *not* AVB-protected (it's mutable by design), so OpenWarden must store all sensitive state encrypted (see §3).

### Runtime AVB verification from DPC
The DPC cannot directly read boot state, but it can:
1. Generate a StrongBox key with attestation: `KeyGenParameterSpec.Builder.setAttestationChallenge(nonce)`.
2. Retrieve the cert chain: `KeyStore.getCertificateChain(alias)`.
3. Parse the attestation extension (OID 1.3.6.1.4.1.11129.2.1.17) to extract `verifiedBootState`, `verifiedBootKey`, `verifiedBootHash`.
4. Refuse to operate if `verifiedBootState != VERIFIED` (i.e., GREEN).

This is the FOSS equivalent of Play Integrity's hardware verdict. [Reference parser](https://github.com/google/android-key-attestation) (Apache-2.0).

### `isInsideSecureHardware`
`KeyInfo.isInsideSecureHardware()` returns true if the key resides in TEE/StrongBox. Use this as a fast local check; not a substitute for full attestation parsing because a compromised TEE could lie. The attestation cert chain is the authoritative proof.

### Play Integrity API as health check
Play Integrity returns a `deviceIntegrity` verdict with `MEETS_STRONG_INTEGRITY` (StrongBox + locked bootloader + recent security patches), `MEETS_DEVICE_INTEGRITY`, or `MEETS_BASIC_INTEGRITY`. **STRONG is the only acceptable verdict for OpenWarden** on Pixel 7; anything lower implies bootloader tampering, custom ROM, or rooted state.

Drawback: Play Integrity requires Google Play Services, which fights the FOSS narrative. Use it as an *optional* corroborating check; don't make it required. The native Key Attestation flow above is the FOSS-pure equivalent.

References: [Play Integrity docs](https://developer.android.com/google/play/integrity), [STRONG verdict requirements](https://developer.android.com/google/play/integrity/verdicts#device-integrity-field).

### Open-source alternatives
None at parity. Google's hardware root of trust is the only signing authority that the Pixel SoC will verify. You can roll your own attestation *chain* (key attestation from Google → your verifier), but you can't replace Google as the leaf signer.

---

## 5. Tamper-evident replication

### Heartbeat protocol
Child signs and emits a `{seq, timestamp, policy_hash, doStatus}` ping every 5 minutes, encrypted to parent pubkey. Parent's app maintains last-seen timestamp; alerts on >15 min silence during scheduled active hours, >6h silence overall.

Implementation: WorkManager periodic task (15-min minimum interval on Android), supplemented by a foreground service that pings on connectivity changes. Buffer pings during offline windows; flush on reconnect.

### Append-only log with gap detection
Every event has `seq = prior_seq + 1`. Parent verifies monotonicity. If `seq` jumps (factory reset → child restarts at 0, or rolls back), parent's verifier flags: "child sequence regressed — possible reset." The child's seq counter is stored in StrongBox-wrapped form, monotonically incremented via [`KeyStore.getEntry` with a counter key](https://source.android.com/docs/security/features/keystore) — survives clear-data but not full reset.

### Wipe detection via persistent device ID
At provisioning, child generates a hardware-attested identity key and registers its `IMEI || serial || attestation_pub_hash` with parent. On any subsequent pairing attempt with a different attestation hash, parent flags "different physical or reset device, requires re-confirmation." This catches factory-reset → reprovisioning attempts.

### Out-of-band channel
If heartbeat stops:
1. T+15 min: silent local notification on parent app, "child offline."
2. T+1h: noisy notification.
3. T+6h: SMS to parent's secondary number (configured during pairing) via Twilio-free ntfy.sh-style relay.
4. T+24h: assume tamper; require parent intervention to re-establish trust.

### Log fork detection (Signal/SSB inspiration)
Each event signed with `Ed25519(prev_hash || event_data)`. Forks (two events claiming same `seq`) are cryptographically detectable. Reference: [SSB feeds](https://ssbc.github.io/scuttlebutt-protocol-guide/), [Briar BSP](https://code.briarproject.org/briar/briar-spec/-/blob/main/protocols/BSP.md).

### Constant-rate cover traffic
To hide whether the child is reporting "nothing happened" vs. "kid tried to factory reset," pad the heartbeat stream to constant size + constant interval. Parent's sealed-box decryption reveals true vs. cover. Cost: ~5KB/heartbeat × 12/hour = 1.4MB/day. Negligible on WiFi, acceptable on cellular.

---

## 6. Anti-disable defenses

### `setUserControlDisabled(true)` — the killer feature
Android 14+ only. Disables Force Stop on OpenWarden package. Without it, the kid can `am force-stop com.openwarden` (if ADB is on) or use Settings → Apps → Force Stop. **Ship this immediately at provisioning.** [Reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUserControlDisabled(android.content.ComponentName,%20java.lang.String,%20boolean)).

### Foreground service watchdog
- Declare `foregroundServiceType="specialUse|connectedDevice"` and set `setForegroundServiceBehavior(FOREGROUND_SERVICE_DEFAULT)`.
- Use `JobScheduler` with `setRequiresDeviceIdle(false)` + AlarmManager `setExactAndAllowWhileIdle` every 15 min as a wake-up watchdog.
- DO is auto-whitelisted from battery optimization; explicitly call `setApplicationsRestricted(false)` on self for clarity.
- A second guard process (separate `:watchdog` PID) restarts the main process if it dies. Bind via `Service.bindService(BIND_IMPORTANT)`.

### Lock task as a kill-switch
When parent issues "lock now," child enters `startLockTask()` with only OpenWarden's lock screen in the allow list. Settings, Recents, Home are unreachable until parent unlocks.

### `DISALLOW_APPS_CONTROL`
Critical restriction: blocks the user from clearing data, disabling, or force-stopping *any* DO-managed app (including OpenWarden). Pair with `DISALLOW_CONFIG_LOCATION`, `DISALLOW_CONFIG_BLUETOOTH` to lock down adjacent escape vectors.

### Lock Settings behind parent PIN
There's no first-class API to "PIN-lock the Settings app." Workarounds:
1. `setApplicationHidden("com.android.settings", true)` — hides Settings entirely. Aggressive; breaks user need to change wallpaper.
2. Custom launcher (Headwind-style) that intercepts launches of Settings and demands parent-signed unlock token.
3. `setLockTaskFeatures(LOCK_TASK_FEATURE_KEYGUARD | LOCK_TASK_FEATURE_HOME)` to disable Settings access during lock-task.

Recommendation: ship the custom launcher in v2; for v1, use `setApplicationHidden` on Settings with a OpenWarden-mediated "Request Settings access" button that flips it back temporarily after parent approval.

---

## 7. Provisioning hardening

### QR provisioning with attestation challenge
Standard managed-device provisioning passes `PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION` + admin extras. OpenWarden extends:
1. QR payload includes `parent_pub` + 32-byte `provisioning_nonce`.
2. First boot after DO setup, child generates StrongBox-attested keypair using `provisioning_nonce` as attestation challenge.
3. Child sends `(attestation_cert_chain, child_pub)` to parent.
4. Parent verifies: cert chain rooted in Google → device is genuine Pixel 7 → locked bootloader → verified boot GREEN → nonce matches.
5. Only on success does parent pin `child_pub` and accept the pairing.

This defeats: "kid clones the QR, provisions a rooted Pixel as the child, parent talks to attacker phone instead." Without hardware attestation, swap attacks are trivial.

### Hardware-bound parent pubkey on child
Parent's pubkey is wrapped by a StrongBox-resident KEK that requires `setUnlockedDeviceRequired(true)`. The wrapping is rooted in the attestation key issued at provisioning. Kid cannot extract `parent_pub` for use in a forged context because the wrapping key is StrongBox-bound to *this* device's attestation.

### Play Integrity at provisioning (optional)
If shipping the optional Google-services check, call Play Integrity once at provisioning and require `MEETS_STRONG_INTEGRITY`. This catches the "child phone is a Bluestacks emulator" attack that pure key attestation might miss on weird vendor SKUs. Skip for FOSS-pure deployment.

---

## 8. Concrete recommendations: 15 defenses for v1, ranked

| Rank | Defense | Attack prevented | Cost | Requires |
|---|---|---|---|---|
| 1 | DO provisioning + `setUserControlDisabled(self, true)` | Force-stop, uninstall, disable | S | Android 14+ |
| 2 | Core DISALLOW restrictions (FACTORY_RESET, DEBUGGING, CONFIG_VPN, MODIFY_ACCOUNTS, OEM_UNLOCK, APPS_CONTROL, USB_FILE_TRANSFER, INSTALL_UNKNOWN_SOURCES_GLOBALLY) | Most known bypass classes | S | DO |
| 3 | FRP set to parent's Google account at provisioning | Factory reset → unmanaged | S | `setFactoryResetProtectionPolicy` |
| 4 | StrongBox-backed Ed25519 identity key + attestation cert pinning on parent | Device clone, swap attack | M | Pixel 7 / Titan M2 |
| 5 | Sealed-box envelope encryption (libsodium) of event log to parent pubkey | Kid reads what's been reported | M | libsodium KMP binding |
| 6 | Signed policy bundle with Ed25519 + monotonic version + rollback rejection | Replay old permissive policy | S | None |
| 7 | Append-only signed event log with hash-chain | Tamper-evident; fork detect | M | None |
| 8 | Heartbeat over Iroh/Tailscale every 5 min, parent alert on silence | Detect wipe/offline tamper | M | Transport |
| 9 | AVB-state runtime check via Key Attestation cert chain | Refuse to operate on rooted/tampered boot | M | StrongBox + Google attestation |
| 10 | FGS watchdog + secondary :watchdog process + AlarmManager re-arm | Battery saver kills, OOM kills | M | None |
| 11 | Lock-task "lock now" with only OpenWarden activity allowed | Live tantrum mitigation | S | DO `setLockTaskPackages` |
| 12 | `setApplicationHidden("com.android.settings", true)` gated by parent token | Kid digs through Settings looking for any toggle | S | DO |
| 13 | Constant-rate cover traffic padding in heartbeat | Traffic-analysis on event semantics | M | Heartbeat infra |
| 14 | Hardware-attested QR provisioning with nonce challenge | Phone-swap during pairing | M | Key Attestation |
| 15 | BIP39 recovery phrase, printed, FRP-account email noted, 7-day time-locked unlock as last resort | Parent loses phone, kid bricked otherwise | M | Local crypto + UI |

### Defenses that LOOK good but don't actually work
- **Hiding the app icon entirely.** Kid finds it in Settings → Apps; provides no real defense, hurts UX. Use `setUserControlDisabled` + admin message instead.
- **Encrypting policy bundle with kid-pin-derived key.** Kid knows the PIN — it's the unlock PIN. Use signed plaintext policy; encrypt only the event log (which kid shouldn't read).
- **Detecting root via SafetyNet/Play Integrity at every operation.** Battery + UX cost is high; one provisioning-time STRONG check + periodic Key Attestation refresh is enough.
- **Custom kernel modifications to disable ADB at boot.** Requires unlocked bootloader, defeats AVB, brings rooting tooling into the attack surface. Use stock + `DISALLOW_DEBUGGING_FEATURES`.
- **Blocking the emergency dialer.** Don't. Ever. Both ethically (911 access) and practically (Android won't let you). Verified in `openwarden-research.md` §6.
- **Time-locked decryption of events with VDFs.** Research-grade; brittle; battery cost; ship sealed-box instead.
- **Mandatory cloud check-in to operate.** Kills offline-school-bus use case; introduces SaaS dependency; FOSS goal violation.

### Defenses we can't ship due to FOSS constraints
- **Play Integrity STRONG as a hard requirement.** Requires Google Play Services; ship as optional corroborating check, not required.
- **SafetyNet (already deprecated anyway).**
- **Knox-style closed TEE attestation.** Samsung-only proprietary.
- **OEM-signed system overlay** for deeper Settings interception. Requires AOSP fork + vendor partnership.

### Defenses requiring hardware locked to one vendor (Pixel-only acknowledged)
- StrongBox + Titan M2 chip (Pixel 6+, Pixel 7+; some Samsung S-series have similar Knox vault but different API).
- Key Attestation with Google-root cert chain (works on any Android device with hardware keystore, but Pixel's TEE has the cleanest implementation and most aggressive rollback protection).
- Verified Boot GREEN state w/ Google's signing key (Pixel-specific signing chain; AOSP forks have their own root keys, which OpenWarden would need to allowlist explicitly).
- Pixel-specific OEM unlock policy (`DISALLOW_OEM_UNLOCK` works on all DO devices but enforcement details vary by OEM).

Document: "OpenWarden v1 targets Pixel 7. Pixel 6, 8, 9 will probably work. Non-Pixel Android requires per-device attestation root configuration and is out of scope."

---

## Key references

- [DevicePolicyManager API](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [Android Key Attestation](https://developer.android.com/privacy-and-security/security-key-attestation)
- [google/android-key-attestation parser](https://github.com/google/android-key-attestation)
- [Verified Boot 2.0](https://source.android.com/docs/security/features/verifiedboot)
- [StrongBox / Keystore](https://developer.android.com/privacy-and-security/keystore)
- [setUserControlDisabled](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUserControlDisabled(android.content.ComponentName,%20java.lang.String,%20boolean))
- [FactoryResetProtectionPolicy](https://developer.android.com/reference/android/app/admin/FactoryResetProtectionPolicy)
- [Play Integrity API verdicts](https://developer.android.com/google/play/integrity/verdicts)
- [libsodium sealed boxes](https://doc.libsodium.org/public-key_cryptography/sealed_boxes)
- [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium)
- [Briar BSP spec](https://code.briarproject.org/briar/briar-spec)
- [Signal sealed sender](https://signal.org/blog/sealed-sender/)
- [SSB protocol guide](https://ssbc.github.io/scuttlebutt-protocol-guide/)
- [drand tlock](https://github.com/drand/tlock)
- [Pixel bootloader docs](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking)
- [GrapheneOS FRP notes](https://grapheneos.org/faq#factory-reset-protection)
- [Quarkslab Android Keystore deep-dive](https://blog.quarkslab.com/reverse-engineering-android-applications.html)
- [NCC Group attestation analysis](https://research.nccgroup.com/category/android/)
- [Trail of Bits Android security posts](https://blog.trailofbits.com/category/android/)
- [Family Link bypass corpus 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)
- [Headwind MDM DO patterns](https://github.com/h-mdm/hmdm-android)
- [googlesamples/android-testdpc](https://github.com/googlesamples/android-testdpc)
