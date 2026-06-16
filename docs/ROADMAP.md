# Roadmap

## v1 — "Oliver's phone works" (target: 8–12 weekends)

**Goal:** Replace GrapheneOS + manual profile-switching with stock Android + OpenWarden. Oliver self-recovers after reboot. Restrictions hold. Parent (Larson) controls remotely from a phone.

### Child DPC (Kotlin)
- [ ] `AdminReceiver` + manifest + device_admin.xml
- [ ] `PolicyEnforcer` wraps `DevicePolicyManager` calls
- [ ] `PolicyStore` — load/save signed bundles to internal storage
- [ ] `BundleVerifier` — Ed25519 signature check against pinned parent pubkey
- [ ] Day-one restrictions applied at boot:
  - `DISALLOW_FACTORY_RESET`
  - `DISALLOW_CONFIG_VPN`
  - `DISALLOW_DEBUGGING_FEATURES`
  - `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`
  - `DISALLOW_MODIFY_ACCOUNTS`
  - `DISALLOW_ADD_USER`
  - `setFactoryResetProtectionPolicy(parent's google account)`
- [ ] App allowlist via `setPackagesSuspended` (visible + grayed + admin message)
- [ ] `PolicyService` — foreground service, watchdog, applies policy on boot/policy-change
- [ ] Pairing screen: generates Tailscale auth key + pubkey QR
- [ ] HTTP server (Ktor embedded) bound to Tailscale interface only, HMAC auth
- [ ] Endpoints: `GET /state`, `POST /policy`, `POST /lock`, `POST /unlock`, `GET /usage`
- [ ] BIP39 recovery phrase display (printable)
- [ ] "Why am I blocked?" screen for kid

### Parent app (Kotlin Multiplatform — Android v1, iOS later; ADR-011, ADR-002)

- [x] **parent-kmp scaffold** — `:proto` + `:shared` + `:androidApp` build green (Android target); iOS host-gated to macOS, built on a Mac later. See `PARENT_KMP_STRUCTURE.md` and `parent-kmp/docs/BUILD.md`.
- [ ] Pair via QR scan
- [ ] Generate Ed25519 root key, show 24-word recovery phrase, force confirm
- [ ] Dashboard: child online status, today's usage, recent blocks
- [ ] App allowlist editor: pull installed apps from child, toggle allowed
- [ ] Send signed policy bundles
- [ ] "Lock now" / "Unlock now"
- [ ] **Transport: LAN-only is v1 default** (mDNS discovery, no services). Tailscale + WireGuard modes deferred to v2 to keep v1 dep-free.

### Provisioning
- [ ] `docs/PROVISIONING.md` written (already drafted)
- [ ] One-page printable PDF for "the day you set this up"

### v1 exit criteria
- Provision Pixel 7 (bench unit) from factory state in under 30 min
- Oliver-as-owner phone restarts → self-unlocks → restrictions intact
- Block + unblock an app from parent phone, sub-5-sec latency
- Survive 7-day uptime test on bench device

## v2 — Quality of life (4–6 weekends after v1)

### New parent platforms
- [ ] Flutter desktop builds: macOS, Windows
- [ ] Pair child from desktop via QR-display-on-phone → camera-on-laptop
- [ ] macOS Keychain entitlement + Windows DPAPI parity tests

### Transport modes
- [ ] Self-hosted WireGuard mode (parent runs Pi endpoint, both peers connect)
- [ ] Tailscale mode (opt-in, free tier, Tailnet Lock enabled by default)
- [ ] Mode-picker UI; reconfigurable post-pairing

### New features
- [ ] **Store-and-forward log sync** (replaces direct REST as primary comms; REST stays as the LAN transport)
- [ ] **Time windows** per app (Pokémon GO 4–6pm weekdays, etc.)
- [ ] **DNS-based content filter** via `setGlobalPrivateDnsMode` pointed at self-hosted resolver or NextDNS (parent picks)
- [ ] **Install-approval flow**: child taps Install → parent push → approve/deny in one tap
- [ ] **Daily digest** alert to parent: top apps, total screen time, blocked attempts
- [ ] **Geofence** with on-device enforcement; parent sees enter/exit events
- [ ] **Pinwheel-style modes**: "school", "everything", "family time" as policy presets
- [ ] **Local AI v1**: opt-in screenshot NSFW classifier (TensorFlow Lite / MediaPipe), event-only output
- [ ] **First grant application**: NLnet NGI Zero PET, target €20k

## v3 — iOS + smarter (date TBD)

### iOS parent app
- [ ] iOS Keychain integration
- [ ] ntfy.sh-style relay for APNs push (self-hosted)
- [ ] App Store sandboxing considerations (may need to ship enterprise / TestFlight only)

### On-device AI
- [ ] Gemma Nano via AICore on Pixel: classify screenshots, flag inappropriate content locally
- [ ] Zero data egress; alerts to parent only

### Economy
- [ ] **Time bank**: Oliver earns 10min YouTube per book chapter logged, redeemed via parent-signed token

## v4+ candidates (deferred, not committed)

- **Local AI v2**: text classifier via Gemma Nano (opt-in, opted-into apps only)
- **Behavioral anomaly** model + parent dashboard
- **Self-hostable relay node** Helm chart for store-and-forward DERP-style
- **Translations** (i18n)
- **Independent security audit** (grant-funded)
- **F-Droid release** of child + parent apps

## Never (probably)

- **Cloud content analysis.** All AI stays on-device.
- **Continuous audio capture, screen recording, keystroke logging.** Stalkerware features. Not shipping.
- **Multi-tenant SaaS.** Single family is the model.
- **Vendor-paid hosted demo.** COPPA risk vector + violates funding constraints.
- **Subscription tiers, paid features, paid enterprise.** Never. See [`docs/FUNDING.md`](docs/FUNDING.md).

## Open architectural questions

1. **Tailscale daemon shipping**: do we embed the Tailscale Go client into the Flutter parent app, or rely on user installing Tailscale separately? Embedding = better UX, more compliance surface. Defer to v2.
2. **iOS parent backgrounding**: is APNs silent push reliable enough for "child needs more time"? May need to ship a self-hosted relay; that breaks the "no SaaS" story slightly.
3. **What about the parent's data?** If parent loses their phone, the child can't push updates until parent restores from recovery phrase. Acceptable. But: should the *child* also keep an emergency-unlock token signed by an offline backup key?
4. **OTA policy bundle distribution**: should bundles auto-expire? Research says yes — forces re-sync. Default expiry: 30 days. Tunable.

## Build for Oliver, not the world

Resist the urge to generalize. v1 ships for one kid (Oliver, on a Pixel 7) and one parent (Larson). Multi-child, multi-platform, OSS community = future-future problems. Get the one-kid case airtight first.
