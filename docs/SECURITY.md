# Security model

## Threat model

**Adversaries:**
1. **The child** — knows the device, motivated to bypass, may have basic technical skill and search the internet for bypass guides
2. **A coercer** — wants to extract child's location / contacts (e.g., custody dispute, stalker, malicious adult)
3. **A device thief** — wants to extract data or wipe + resell

**Defenders' goals:**
- Maintain app/usage restrictions against the child
- Protect child's data against the coercer and thief
- Preserve parent's remote control even when adversaries have temporary physical access

## What OpenWarden defends

| Attack | Defense |
|---|---|
| Sideload via ADB | `DISALLOW_DEBUGGING_FEATURES`; ADB blocked by DO from boot |
| Factory reset via Settings | `DISALLOW_FACTORY_RESET` |
| Factory reset via fastboot | FRP locked to parent's Google account; `setFactoryResetProtectionPolicy(...)` |
| Recovery mode wipe | FRP blocks setup; phone is brick without recovery phrase |
| VPN bypass for DNS filter | `DISALLOW_CONFIG_VPN`; Always-On VPN locked to OpenWarden's loopback DNS |
| Time rollback | DO controls system time settings; `DISALLOW_CONFIG_DATE_TIME` |
| Secondary user accounts | `DISALLOW_ADD_USER` |
| Reinstall removed app via Play Store | DO whitelist gates Play installs |
| Web-based bypass (browser games, embedded webviews) | DNS filter (private DNS) + per-app allowlist; Discord/etc. blocked if they embed webviews you can't filter |
| Disabling OpenWarden | DPC is Device Owner — uninstallation requires DO surrender, gated by recovery phrase |
| Screen-record / mirror to leak data | `setScreenCaptureDisabled(true)` for sensitive screens; parent app default-disabled for casting |
| Coercer demanding unlock | Plausible deniability not in scope for v1 (acknowledge: this is a tradeoff vs. parental control simplicity) |

## What OpenWarden does NOT defend
- Physical theft + bootloader unlock + custom firmware flash (requires fastboot OEM unlock + carrier permissions). Pixel locks this by default; if you unlocked the bootloader for /e/OS earlier, **re-lock before deploying**: `fastboot flashing lock`.
- A child borrowing someone else's phone
- A child using a friend's PC + their own Google account to access services OpenWarden blocks locally
- Manipulation of the parent (social engineering by child to extract recovery phrase or PIN)

## Cryptographic design

- **Parent root key:** Ed25519, generated at first parent-app launch
- **Storage:** OS keystore (Android Keystore, iOS Keychain, macOS Keychain, Windows DPAPI)
- **Recovery:** 24-word BIP39 mnemonic (256-bit entropy). Used to re-derive root key if parent device lost.
- **Child trust:** Pins parent pubkey at pairing. Rejects bundles signed by any other key.
- **Policy bundle signing:** Ed25519 over canonicalized JSON (RFC 8785 JCS)
- **Command authentication:** HMAC-SHA256 with derived session key (post-pairing) on every Tailscale RPC, replay-protected with monotonic counter

## Tailscale trust boundary
Tailscale's coordinator can theoretically inject a malicious node into your tailnet. Mitigation: **Tailnet Lock** (enable in Tailscale admin) — coordinator can't add nodes without your signature. This makes Tailscale = transport only, not trust root.

DERP relays (used when direct NAT traversal fails) carry encrypted WireGuard packets — Tailscale can see source/dest IPs and packet sizes, but not contents. Acceptable for control-plane traffic.

## Privacy
- No keystrokes logged
- No message contents logged
- No screenshots taken
- Event log = `{timestamp, app_pkg, event: launched|blocked|geofence_enter|geofence_exit}`, 30-day retention, local only
- Location data = on-demand from parent; not continuously logged
- Stats from `UsageStatsManager` = duration-per-app, aggregated daily

## Decommissioning
Two paths:
1. **Parent-initiated:** parent app → "Decommission" → enter recovery phrase → signed command → DPC releases DO, factory resets, phone back to fresh state
2. **Time-locked self-service** (for "dad got hit by a bus"): holding power+vol-down at lock screen for 60s prompts a 7-day countdown banner. If the parent doesn't cancel within 7 days, DPC releases DO. Visible the entire time so a kid can't sneak it past.

## Auditing
Open source. Pin commit hashes in deployment notes. Reproducible builds are a stretch goal.
