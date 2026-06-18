# ADR-028: Bluetooth as an additive parent↔child transport (under the transport-agnostic app-layer crypto)
Status: Proposed
Date: 2026-06-17
Relates: ADR-025 (pairing handshake — identity is the pinned Ed25519/X25519 keys + SAS, NOT the link); issue #20 (embedded server — "every request passes app-layer crypto verification **regardless of transport**"); ADR-016 (fail-closed DNS floor); docs/PROTOCOL.md §4 (transport/TLS); docs/ROADMAP.md (LAN-only v1 default)

## Context

v1 transport is **LAN-only** — mDNS discovery + REST over HTTPS with the child cert pinned against the peer's pinned pubkey (PROTOCOL §4.1, ROADMAP v0.3). That fails whenever parent and child are **not on the same LAN**: child on cellular, a guest/hostile Wi-Fi that blocks peer-to-peer (client isolation), captive portals, or simply no shared network at home. The owner wants the two devices to be able to talk **over Bluetooth** as well.

The enabling invariant already exists: per issue #20, **every request passes app-layer crypto verification regardless of transport** — signed policy bundles (Ed25519 + JCS), signed commands, signed heartbeats, and sealed-box event payloads are authenticated end-to-end, independent of the pipe. Identity is the keys pinned at §7 pairing + the six-emoji SAS (ADR-025), **never** the link layer. So a new transport is *just another byte pipe*; it does not touch the trust model. LAN, Bluetooth, and (deferred) Tailscale/WireGuard are interchangeable carriers under the same auth.

## Options

- **A. Bluetooth Classic RFCOMM (SPP) — stream socket (chosen as the primary bulk channel).** A reliable byte-stream; carries the >2 KB attestation cert chain (ADR-025 §7.2) and policy bundles without app-level chunking. Costs: higher power than BLE, an Android BT bonding step, and `BLUETOOTH_CONNECT`/`_SCAN` runtime permissions (API 31+).
- **B. Bluetooth Low Energy (BLE GATT).** Lower power, ubiquitous, background-friendly — but small per-notification MTU (~185–512 B) forces app-level fragmentation/reassembly of the cert chain + bundles, and a custom GATT service. Kept as a **fallback/evaluation** option, especially for lightweight `/state`/`/heartbeat` polling where payloads are tiny.
- **C. Wi-Fi Direct / Google Nearby Connections.** Richer + higher throughput, but Nearby pulls a Google Play Services dependency — rejected: it violates the no-SaaS / no-Google-lock-in ethos (ADR-006) and the offline-first posture.

## Decision

Adopt **Bluetooth as an additive transport**, selected behind a **pluggable transport seam** (`LAN | BT`) so the existing request/response + signed-payload layer is reused unchanged. Primary channel = **RFCOMM (Option A)** for the bulk path (pairing cert chain + bundles); **BLE (Option B)** is a documented fallback to evaluate for tiny payloads. **Implement only after the real LAN transport (#20/#21) lands** — LAN is the v1 default; BT is a parallel carrier added once the LAN path is proven, so we harden one transport at a time.

Normative constraints (the trust model does NOT move to Bluetooth):
1. **The BT link is UNTRUSTED**, exactly like LAN pre-pin. OS-level BT bonding is **not** identity — identity remains the pinned Ed25519/X25519 keys + §7.4 SAS. A bonded-but-unpinned peer is rejected.
2. **App-layer crypto is unchanged and mandatory over BT**: signed bundles/commands/heartbeats verify against the pinned parent key; sealed-box events stay sealed to the pinned parent X25519 key. No "trust because it's paired over Bluetooth."
3. **Fail-closed**: an unverified/replayed/mis-addressed message over BT is rejected identically to LAN (same admission pipeline, same replay floors). A transport downgrade or a BT MITM cannot widen access — it can only carry bytes the app layer still verifies.
4. **Discovery ≠ trust**: a BT peer advertising the service is untrusted until §7 pairing + SAS pins it, mirroring mDNS.
5. **Permissions are least-privilege + kid-transparent**: the BT permissions appear on the KID_TRANSPARENCY screen; no always-scanning beyond what sync needs.

## Consequences

**Good:** parent↔child works with **no shared Wi-Fi**; short range is a feature (in-home proximity, not remote control); no infrastructure; reuses the entire signed-payload stack untouched; keeps the offline-first, no-SaaS posture.

**Bad / costs:** a second transport to build, harden, and **red-team** (the adversarial E2E harness, ADR-027, must cover BT: spoofed peer, downgrade LAN→BT, replay across transports, fragmentation abuse); Android BT permission + bonding UX; BLE MTU fragmentation if Option B is used; power draw for RFCOMM; emulator BT testing is limited (needs real devices or HCI bridging — a test-bed gap to note).

**Security note:** because identity + freshness live entirely in the app layer, adding BT does **not** expand the trust boundary — the worst a malicious BT peer/MITM can do is fail verification (fail-closed). This is the whole reason a new transport is ADR-light on the crypto side and heavy only on the engineering/UX/test side.

**Sequencing:** ROADMAP commits the direction now (v0.5+ candidate rung); implementation waits until LAN (#20/#21) is real, then BT is added behind the transport seam and put through the same ADR-027 adversarial E2E loop.
