# Architecture

OpenWarden is two paired apps + a wire protocol + a set of defense layers. First-principles design: minimum viable to give parent meaningful control of a kid's Android phone, no server, no surveillance.

## First principles

What problem are we solving? Parent controls kid's phone access remotely. That's it.

What minimum primitives close the problem?

1. **Identity for parent** (Ed25519) — sign commands
2. **Identity for child phone** (Ed25519) — bind policy to one device
3. **Pairing** — exchange + pin pubkeys
4. **Recovery** — BIP39 phrase, only escape if device lost
5. **OS-level enforcement** — Device Owner DPC blocks bypasses kids try
6. **Allowlist + blocklist** — what apps are available
7. **Time windows** — bedtime, school-time hard locks
8. **Signed policy bundle** — replay-protected
9. **Communication channel** — parent ↔ child sync
10. **Audit log encrypted to parent** — kid can't read what's reported

Everything else (DNS filter, AI classifier, geofencing, time bank, graduated privileges) is downstream and Tier 2+.

See [`docs/SIMPLIFY.md`](docs/SIMPLIFY.md) for the tier system.

## Three planes

OpenWarden runs as three logical planes per device:

### Policy plane (offline-first)
All enforcement happens on the child device, by the DPC, against a locally cached signed policy bundle.

- Child is fully functional offline. New policy can't arrive, existing policy holds.
- Bundle expiry forces parent re-sync every 30 days; expired bundle = stale-policy mode (stricter baseline).
- Fail-closed: any error (sig parse, missing file, storage fail, clock anomaly) → strict baseline, never "unrestricted."

### Communication plane (LAN-first v1, store-and-forward v2)
Parent and child sync over whatever transport is available.

| Transport | Status |
|---|---|
| **LAN mDNS + signed REST** | v1 default — zero services, same Wi-Fi |
| **Tailscale / WireGuard** | v2 — for remote sync |
| **Iroh (QUIC P2P, Apache 2 / MIT)** | v2 — store-and-forward signed logs |
| **ntfy.sh wake-up doorbell** | v2 iOS push (content-free) |
| **Email / SMS / NFC bump** | v3+, last-resort |

No transport is the source of truth. The signed log is. See [`docs/PROTOCOL.md`](docs/PROTOCOL.md), [`docs/STORE_AND_FORWARD.md`](docs/STORE_AND_FORWARD.md).

### AI plane (opt-in, on-device only) — Tier 2+
Optional local classifiers (NSFW image, behavioral anomaly, text bullying signal).

Content **never leaves the child device**. Only event flags (category + severity + timestamp) egress, sealed-box encrypted.

Off by default. Parent opts in per category. Kid sees which classifiers are active via [`docs/KID_TRANSPARENCY.md`](docs/KID_TRANSPARENCY.md). See [`docs/AI_IMPLEMENTATION.md`](docs/AI_IMPLEMENTATION.md), [`docs/LOCAL_AI.md`](docs/LOCAL_AI.md).

## Cryptography summary

- **Parent identity:** Ed25519 (signing) + X25519 (encryption), derived from BIP39 24-word mnemonic via Argon2id + HKDF
- **Child identity:** Ed25519 + X25519, hardware-attested keypair generated in StrongBox (Pixel) or TEE (others)
- **Bundle sig:** Ed25519 over RFC 8785 JCS canonical form
- **Event log:** `crypto_box_seal(event, parent_x25519_pub)` — libsodium sealed box. Child writes, cannot decrypt own log.
- **Replay protection:** `policy_seq` monotonic + `not_before` / `not_after` freshness windows
- **Cover traffic:** constant-rate sealed envelopes hide event semantics from frequency analysis

Full design: [`docs/CRYPTO.md`](docs/CRYPTO.md).

## Apps + modules

### `child-android/` (Kotlin DPC)
- Device Owner via `dpm set-device-owner` ([`docs/PROVISIONING_V2.md`](docs/PROVISIONING_V2.md))
- 30 hardening defenses ([`docs/DEFENSES.md`](docs/DEFENSES.md))
- Local DNS resolver (DoT on `127.0.0.1:853`, forwards to Cloudflare 1.1.1.3 family) for video-CDN session tracking + per-app caps ([`docs/DNS_RESOLVER.md`](docs/DNS_RESOLVER.md))
- Ktor HTTP server bound to LAN interface
- StrongBox-backed identity + sealed-box event log
- Kid transparency screen + "Ask dad" request flow
- 7-day time-locked decommission

### `parent-kmp/` (Kotlin Multiplatform, Android + iOS)
- `:shared` — protocol, crypto, signed bundle builder, sync logic
- `:androidApp` — Compose UI, foreground rich notifications
- `:iosApp` — SwiftUI via SKIE-generated Kotlin interop
- iOS v1 = open-the-app model (no APNs); BGAppRefreshTask opportunistic. v2 = ntfy doorbell.
- Distribution: GitHub Releases signed APK (Android), TestFlight (iOS)
- libsodium via ionspin KMP binding

Structure: [`docs/PARENT_KMP_STRUCTURE.md`](docs/PARENT_KMP_STRUCTURE.md).

### `proto/`
Shared schemas + canonicalization (JCS). Imported by `child-android` + `parent-kmp/:shared`. KMP module, JVM-target + Android-target + iOS-target.

## Family + device model

- One family = N parents (1-3 typical) + N kids (1-5)
- v1 UI: solo parent + 1 kid. Schema supports more (ADR-004).
- Per-kid: independent identity, PolicyDoc, event log, trust level, birthday
- Per-parent: identity derived from family BIP39 mnemonic (one phrase, all parents)

[`docs/FAMILY_MODEL.md`](docs/FAMILY_MODEL.md), [`docs/GRADUATED_PRIVILEGES.md`](docs/GRADUATED_PRIVILEGES.md).

## Provisioning summary

1. Factory-fresh Pixel 7 (or other Tier 1/2 device — [`docs/ANDROID_COMPAT.md`](docs/ANDROID_COMPAT.md))
2. Skip OOBE Google account step
3. Enable USB debugging (will be locked after step 5)
4. Install OpenWarden child APK
5. `adb shell dpm set-device-owner com.openwarden.child/.AdminReceiver`
6. Child app generates StrongBox-attested keypair, shows pairing QR
7. Parent app scans QR, verifies attestation cert chain (Google root for Pixel)
8. Both screens show 6-emoji SAS confirmation; parent confirms match
9. Parent pushes initial signed policy bundle
10. Child locks down per policy + FRP bind to parent's Google account

Detailed state machine + exact ADB commands: [`docs/PROVISIONING_V2.md`](docs/PROVISIONING_V2.md).

## What we keep + what we don't

**Stored on child (encrypted at rest via EncryptedSharedPreferences + StrongBox-wrapped master key):**
- Active + previous policy bundles
- Event log (30d rolling, sealed to parent pubkey)
- Usage stats from `UsageStatsManager`
- DNS query log (sealed)

**Stored on parent (OS keystore + EncryptedSharedPreferences / Keychain):**
- Parent identity keys (or derive on demand from phrase)
- Pinned child pubkeys + attestation roots
- Policy history
- Audit log of own actions

**Never stored anywhere:**
- Keystrokes
- Message contents (SMS, in-app chat, anything)
- Photos (only NSFW classifier flags, image discarded)
- Voice call audio (physically can't capture via AOSP API)
- Search query strings
- Web page contents

## Failure modes

| Failure | Behavior |
|---|---|
| LAN down (away from home) | Last policy still enforced. Cellular doesn't help v1; v2 Iroh covers. |
| Policy bundle expired, no parent contact 30d | Stale-policy mode: stricter baseline + kid banner "Sync w/ dad" |
| Parent device lost | Recovery phrase → new device → re-derive keys → re-pair |
| Child Android OTA | DPC survives (verified in [`docs/PROVISIONING_V2.md`](docs/PROVISIONING_V2.md)); FGS rebinds on first launch |
| Battery-saver kills FGS | AlarmManager watchdog re-arms every 15min |
| Storage full | Bundle write fails → strict baseline + tamper event |
| Sig parse fails on bundle | Reject + strict baseline (fail-closed) |
| Heartbeat silent >15min/1h/6h/24h | Escalating parent alerts |
| Parent loses recovery phrase | Phone bricked. FRP blocks factory reset. Last resort: Google FRP-unlock via proof-of-purchase + Pixel serial. |

## Failure modes (intentional)

- **Kid finds recovery phrase in drawer** → uses it → bypasses everything. Physical OPSEC concern, can't be defended in software.
- **Friend's unrestricted phone** → out of scope, different device.
- **School Chromebook / smart TV / game console** → out of scope, different OS.

Full attack catalog: [`docs/ATTACKS.md`](docs/ATTACKS.md). Defenses: [`docs/DEFENSES.md`](docs/DEFENSES.md). Parent strategy for what software can't fix: [`docs/ONBOARDING.md`](docs/ONBOARDING.md).

## ADRs

Major architectural decisions recorded in [`docs/adr/`](docs/adr/). Read these before proposing direction changes.

- [001](docs/adr/001-one-device-tier.md) — One device tier, not one device family
- [002](docs/adr/002-ios-parent-v1.md) — iOS parent app in v1
- [003](docs/adr/003-dns-video-tracking.md) — DNS-based video tracking, no titles
- [004](docs/adr/004-multi-child-schema-v1.md) — Multi-child data model v1, UI v2
- [005](docs/adr/005-generalize-under-18.md) — Generalize from "Oliver" to "kids under 18"
- [006](docs/adr/006-privacy-no-server.md) — Privacy via no-server architecture
