# Performance & Bandwidth Budget

> **Status:** Normative. Single source of truth for every per-feature
> battery, bandwidth, memory, storage, and latency target across the
> OpenWarden stack. Where this doc disagrees with a per-feature doc, this
> doc wins; per-feature docs should cite back here.
>
> **Companion docs (each contributes line items to the tables below):**
> [`PROTOCOL.md`](PROTOCOL.md), [`CRYPTO.md`](CRYPTO.md),
> [`DNS_RESOLVER.md`](DNS_RESOLVER.md),
> [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md), [`OTA.md`](OTA.md),
> [`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md),
> [`GEOFENCING.md`](GEOFENCING.md),
> [`../ARCHITECTURE.md`](../ARCHITECTURE.md).
>
> **Reference device:** Pixel 7 (Tensor G2, 8 GB RAM, Android 14),
> 8h foreground / 16h background typical day.
> **Tier-2 device:** Samsung A55 (Exynos 1380, 8 GB RAM, Android 14) —
> +20% latency budget acceptable.

---

## 1. Battery budget (child device)

Per-feature daily drain on the reference Pixel 7. Source citations
link to the per-feature doc the number originated in.

| Feature | Daily drain | Source |
|---|---|---|
| FGS watchdog + DPC | 0.5% | [`ARCHITECTURE.md`](../ARCHITECTURE.md) Failure modes |
| DNS resolver (local DoT, hybrid forward) | 1.0–1.5% | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §9 |
| Heartbeat over LAN (every 5 min) | 0.2% | [`PROTOCOL.md`](PROTOCOL.md) §1.3, §6.4 |
| AI image classifier (Falconsai int8, ~100 inferences) | 0.5% | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §2 |
| Geofencing (Home + School fences, hysteresis) | 1.0–2.0% | [`GEOFENCING.md`](GEOFENCING.md) §3 |
| Bedtime hard lock | 0% | DPC restriction, no runtime cost |
| BootCompleted listener | ~0% | one-shot per boot |
| Sealed-box logging | 0.1% | [`CRYPTO.md`](CRYPTO.md) §4 + cover traffic §4 |
| Gemma Nano text classifier (v3, AICore) | 2.0% | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §4 |
| Behavioral anomaly (UsageStats sampling) | 0.1% | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §5 |

**Roll-ups (OpenWarden-attributable, on top of OS baseline):**

| Release | Total daily | Composition |
|---|---|---|
| v1 | **2–3%** | FGS + DNS + heartbeat + sealed-box logging |
| v2 | **4–5%** | v1 + AI image + geofencing + anomaly |
| v3 | **5–7%** | v2 + Gemma Nano text classifier |

The v1 ceiling matches the [`SIMPLIFY.md`](SIMPLIFY.md) battery
discipline: under 5% OpenWarden-attributable on a Pixel 7 over a normal
school day. v3 with all opt-ins active approaches but does not breach
the parent-acceptable boundary observed in field tests with comparable
DPC products (anything > 8% triggers the "OpenWarden killed my battery"
uninstall reflex).

---

## 2. Bandwidth budget (child device)

Per-day, steady-state, after first-install model download has
completed.

| Stream | Volume / day | Source |
|---|---|---|
| Heartbeats | 5 KB × 288 (every 5 min) = ~1.5 MB | [`PROTOCOL.md`](PROTOCOL.md) §6.4 (2048 B canonical × overhead) |
| Sealed event log | ~10 KB × per-event = 1–2 MB | [`CRYPTO.md`](CRYPTO.md) §4 + [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §8 |
| Constant-rate cover traffic padding | ~3 MB | [`CRYPTO.md`](CRYPTO.md) §4 cover-traffic (12 env/hr × 4 KiB × 24h ≈ 1.1 MB; padding overhead pushes to 3 MB total) |
| Upstream DNS queries (DoT to Cloudflare) | ~50 KB | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10 (cache hit > 70%) |
| Policy bundle (when parent updates) | ~2 KB each | [`PROTOCOL.md`](PROTOCOL.md) §2 |
| Geofence events (sealed, batched) | ~20 KB | [`GEOFENCING.md`](GEOFENCING.md) §10 batched into sync window |

**Roll-ups:**

| Release | Ongoing daily | One-time |
|---|---|---|
| v1 | **5–6 MB** | — |
| v2 (+ AI image + geofencing) | **7–10 MB** | 75–150 MB AI model on install / model OTA |
| v3 (+ text classifier) | **8–11 MB** | Gemma via AICore — system-provided, no OpenWarden download |

Hard cap on a single envelope: 64 KiB ([`PROTOCOL.md`](PROTOCOL.md)
§1.4). SMS transport carries only `Heartbeat` and `AckCommand` because
of fragmentation; the bandwidth numbers assume Wi-Fi or LTE.

---

## 3. Parent device budget

| Posture | Battery | Bandwidth |
|---|---|---|
| Parent app idle (foreground-only model) | 0.2% / day | 0 MB / day |
| Parent app active sync | — | ~1 MB × ~10 syncs/day = 10 MB |
| Push notifications | 0% | 0 MB (no APNs / FCM in v1) |

The parent app does no background work in v1 by design: no foreground
service, no push channel, no polling. Sync is parent-initiated or
opportunistic-on-open. This keeps the parent app out of the
[`SIMPLIFY.md`](SIMPLIFY.md) "background services" budget entirely.

---

## 4. Memory budget (RSS targets)

| Process / module | Target | Notes |
|---|---|---|
| Child OpenWarden total RSS | **< 80 MB** | FGS + DPC + DNS resolver + sealed-box queue |
| DPC service | < 20 MB | mostly KeyStore handles + policy bundle in memory |
| DNS resolver (mini-dns + BouncyCastle TLS) | < 25 MB | + 4096-entry LRU cache ([`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10) |
| AI image classifier during inference | **< 100 MB** | bitmap + ViT-base int8 weights ([`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §2) |
| Parent KMP app total RSS | **< 120 MB** | KMP shared + Compose UI + sync engine |

The classifier RSS spike is bounded to the inference call window;
between inferences the bitmap is freed
([`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §1, "Bitmap is freed
immediately after inference"). Sustained child RSS should stay near
80 MB; transient spikes during classification are accepted.

---

## 5. Storage budget

| Artifact | Target |
|---|---|
| OpenWarden child APK | **< 15 MB** |
| OpenWarden parent Android APK | **< 25 MB** |
| KMP iOS parent bundle | **< 50 MB** |
| Policy bundles + event log (rolling 30d) | **< 50 MB** |
| AI models (optional, side-loaded post-install) | 75–150 MB |

The Falconsai int8 model is ~85 MB and ships out-of-band per
[`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §8 — never bundled in
the APK. NudeNet secondary (~25 MB) adds to this only if licensing
clears. Audit log retention is 90 days
([`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §7); event log is 30
days ([`ARCHITECTURE.md`](../ARCHITECTURE.md) "Data we keep").

---

## 6. Latency targets

p99 unless noted. Hard SLAs that block release if regressed.

| Operation | Target | Source |
|---|---|---|
| DPC restriction apply | < 2 s | [`PROTOCOL.md`](PROTOCOL.md) §2.1 step 9 |
| Policy bundle verify (Ed25519 + JCS) | < 5 ms | [`CRYPTO.md`](CRYPTO.md) §5 |
| Sync handshake (LAN, mDNS) | < 2 s | [`PROTOCOL.md`](PROTOCOL.md) §4.4 |
| Sync handshake (Iroh, v2) | < 5 s | [`PROTOCOL.md`](PROTOCOL.md) §4.4 |
| AI image classifier (GPU delegate) | < 200 ms | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §2 (~80 ms typical) |
| Heartbeat round-trip | < 500 ms | [`PROTOCOL.md`](PROTOCOL.md) §6.4 |
| DNS query, cache hit | < 2 ms | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10 |
| DNS query, forwarded Wi-Fi | < 50 ms | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10 |
| DNS query, forwarded LTE | < 120 ms | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10 |
| DNS query, end-to-end p99 (Pixel 7) | < 150 ms | this doc, §8 |
| Geofence event delivery | < 60 s after crossing | [`GEOFENCING.md`](GEOFENCING.md) §12 (Doze soak test) |

Tier-2 devices (Samsung A55, etc.) carry a +20% allowance on every
row; any miss > 20% over the Pixel 7 target is a regression even on
Tier-2.

---

## 7. Performance benchmarks (device tiers)

- **Tier 1 — Pixel 7 (Tensor G2, 8 GB, Android 14).** Reference device.
  Every target in §6 is achievable here; CI bench Pixel runs all
  perf-gated test suites.
- **Tier 2 — Samsung A55 (Exynos 1380, 8 GB, Android 14).** +20%
  latency allowance per row. Memory targets unchanged. Battery roll-ups
  may run 0.5–1.0 percentage point hotter due to less efficient SoC.
- **Tier 3 — older Pixels (6a, 7a).** Same SoC family as Tier 1;
  treated as Tier 1 for SLA purposes. StrongBox required
  ([`CRYPTO.md`](CRYPTO.md) §3) — Titan M2 is the gate, not Tensor.

GrapheneOS / CalyxOS de-Googled builds use the LocationManager
fallback in [`GEOFENCING.md`](GEOFENCING.md) §2 — battery on geofence
runs ~0.5 pp higher there (no Fused Provider). Documented exception.

---

## 8. Per-feature performance characterization

Measured on Pixel 7 reference unless noted.

| Operation | p50 | p99 | Source |
|---|---|---|---|
| DNS resolver, forwarded query | 40 ms | 150 ms | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10 |
| Sealed-box encrypt (1 event, libsodium) | < 1 ms | < 5 ms | [`CRYPTO.md`](CRYPTO.md) §4 |
| Ed25519 sign (single entry) | < 1 ms | < 2 ms | [`CRYPTO.md`](CRYPTO.md) §5 |
| Ed25519 verify (single bundle) | < 1 ms | < 5 ms | [`PROTOCOL.md`](PROTOCOL.md) §2.1 |
| BIP39 → root key derive (Argon2id) | ~500 ms | ~700 ms | [`CRYPTO.md`](CRYPTO.md) §2 (one-time per recovery) |
| BLAKE3-256 over 2 KiB entry | < 0.5 ms | < 1 ms | [`PROTOCOL.md`](PROTOCOL.md) §1.2 |
| JCS canonicalize a PolicyBundle | < 2 ms | < 5 ms | [`PROTOCOL.md`](PROTOCOL.md) §3 |
| MediaProjection screenshot capture | 30 ms | 80 ms | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §1 |
| Falconsai int8 inference (GPU) | 80 ms | 150 ms | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §2 |
| Foreground-app-change trigger latency | 200 ms | 500 ms | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §12 |

Argon2id at 256 MiB / t=4 / p=2 is intentionally slow — it is the
recovery-phrase cost floor against offline GPU crack. One-shot on
recovery; never on the hot path.

---

## 9. CI performance budget

Per-PR feedback must stay tight or contributors lose flow.

| Stage | Target | Notes |
|---|---|---|
| Total GHA run, PR | **< 20 min** | gate on PR merge |
| Emulator boot (Pixel 7 system image) | 3–5 min | cached AVD where possible |
| Unit test execution | < 5 min | KMP common + Android + iOS |
| E2E integration | < 8 min | bench includes OTA install path ([`OTA.md`](OTA.md) §16) |
| Reproducible build verification | < 5 min | every PR touching `androidApp/` or `child-android/` ([`OTA.md`](OTA.md) §6) |

The reproducible-build gate is non-negotiable: a PR whose output bytes
diverge from the deterministic build script breaks the trust contract
for everyone downstream verifying releases ([`OTA.md`](OTA.md) §3
step 4).

---

## 10. App startup time

| Path | Target |
|---|---|
| Cold start (Compose target) | < 1 s |
| Warm start | < 500 ms |
| Background → foreground | < 300 ms |

Cold start on the child app must include foreground-service rebind +
policy-bundle-in-memory verify (~5 ms per §6) without breaching the
1 s budget. Splash work is forbidden; the policy verify happens
asynchronously after the first frame.

---

## 11. Battery contributors per feature (detail)

Cross-reference of every OpenWarden subsystem to its battery line item
and the doc that owns it.

| Subsystem | Daily | Owning doc | Notes |
|---|---|---|---|
| Foreground service watchdog | 0.5% | [`ARCHITECTURE.md`](../ARCHITECTURE.md) | shared across all features; charged once |
| DoT listener (idle) | < 0.1% | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §9 | bound socket only |
| DoT listener (~2000 q/day load) | 0.3% | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §9 | mostly cache hits |
| Upstream DoT keep-alive | 0.5–1.0% | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §9 | tunable (§13) |
| Heartbeat emission (every 5 min) | 0.2% | [`PROTOCOL.md`](PROTOCOL.md) §6.4 | constant-rate 2048 B |
| Sealed-box AEAD per event | 0.05% | [`CRYPTO.md`](CRYPTO.md) §4 | batched into sync window |
| Cover traffic (12 env/hr) | 0.05% | [`CRYPTO.md`](CRYPTO.md) §4 | tunable (§13) |
| AI image classifier (Falconsai int8) | 0.4–0.5% | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §2 | 0.5% per 100 inferences |
| Behavioral anomaly (UsageStats) | 0.1% | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §5 | pure CPU sampling |
| Geofence triggers (Fused + GeofencingClient) | 1.0–2.0% | [`GEOFENCING.md`](GEOFENCING.md) §3 | Play-Services path |
| Geofence (LocationManager fallback) | 1.5–2.5% | [`GEOFENCING.md`](GEOFENCING.md) §3 | de-Googled path |
| Gemma Nano text classifier (v3) | 2.0% | [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §4 | AICore shared process |
| OTA download (per crisis update) | one-shot 0.1% | [`OTA.md`](OTA.md) §5 | amortized monthly |
| Sync over LAN (per sync) | 0.05% / sync | [`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md) §Reliability | ~10 syncs/day typical |

---

## 12. Bandwidth contributors per feature (detail)

| Subsystem | Daily | Owning doc |
|---|---|---|
| Heartbeats | 1.5 MB | [`PROTOCOL.md`](PROTOCOL.md) §6.4 |
| Sealed event envelopes | 1–2 MB | [`CRYPTO.md`](CRYPTO.md) §4 |
| Cover-traffic padding | ~3 MB | [`CRYPTO.md`](CRYPTO.md) §4 |
| Upstream DNS (DoT) | ~50 KB | [`DNS_RESOLVER.md`](DNS_RESOLVER.md) §10 |
| PolicyBundle pulls (parent-initiated) | ~2 KB each | [`PROTOCOL.md`](PROTOCOL.md) §2 |
| Geofence events | ~20 KB | [`GEOFENCING.md`](GEOFENCING.md) §10 |
| OTA model download (first install or update) | 75–150 MB one-time | [`OTA.md`](OTA.md) §3 + [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §8 |
| OTA APK download (monthly Stable) | 15–25 MB / month | [`OTA.md`](OTA.md) §1 |

Cover traffic is the single largest steady-state line. It is
load-bearing for the [`DEFENSES.md`](DEFENSES.md) #13
traffic-analysis defense and cannot be dropped silently; it can be
tuned (§13).

---

## 13. Optimization opportunities

Levers we hold; tradeoffs explicit.

- **DoT keep-alive interval (DNS resolver).** Closing the upstream
  connection between idle windows saves ~0.5% / day at the cost of an
  extra handshake on the next query ([`DNS_RESOLVER.md`](DNS_RESOLVER.md)
  §9). Default: hold connection 60 s past last query.
- **Heartbeat interval.** 5 min → 10 min saves ~0.1% battery and
  ~750 KB/day bandwidth. Cost: doubles worst-case detection latency for
  "child unreachable" alert ([`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md)).
- **Cover traffic.** Optional per family (privacy / battery tradeoff).
  Disabling drops ~3 MB/day and ~0.05% battery but weakens the
  traffic-analysis defense.
- **AI inference delegate.** GPU where available (default). Hexagon
  NNAPI is opportunistic and inconsistent
  ([`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §9), opt-in via
  build flag.
- **Geofence radius / hysteresis.** Wider radius + longer debounce →
  fewer wakeups → lower battery, at the cost of false-negative leak
  detection. Tunable per fence ([`GEOFENCING.md`](GEOFENCING.md) §5).
- **Negative-cache TTL.** Current 60 s fixed; raising to 300 s would
  cut forwarded queries further but slows unblock-after-policy-change.

---

## 14. Failure-mode performance

What perf looks like when a subsystem degrades.

- **Sync down (all transports unreachable).** Enforcement continues
  at zero incremental cost; the policy bundle is in memory and the
  DPC runs locally. Stale-policy mode increases heartbeat retry to
  every 60 s ([`PROTOCOL.md`](PROTOCOL.md) §5), bumping battery
  ~0.3% over the offline window.
- **AI classifier disabled (parent opt-out).** Zero battery and zero
  memory cost. The MediaProjection token is never acquired; no
  inference path runs.
- **DNS resolver process down.** OS falls back to default network DNS
  ([`DNS_RESOLVER.md`](DNS_RESOLVER.md) §11). OpenWarden filtering
  vanishes during the gap; the watchdog restarts within 2 s. Battery
  during outage drops 1–1.5% to baseline; visibility is lost for the
  gap.
- **Battery-saver mode.** AI classifier suspends
  ([`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md) §12). Heartbeat
  interval doubles per Doze. Sealed-box queue continues; Geofence
  uses Doze-aware GeofencingClient path
  ([`GEOFENCING.md`](GEOFENCING.md) §6).
- **Geofence Play-Services fallback to LocationManager.** +0.5–1.0
  battery point; latency for enter/exit fires increases from < 30 s to
  < 60 s.

---

## 15. Performance regression detection

CI must catch perf drift before users do.

- **Benchmark suite.** KotlinPerf or Jetpack Microbenchmark per-module,
  run on every PR.
- **Per-PR perf check.** Bench Pixel 7 in CI executes a fixed
  workload: 100 DNS queries, 100 sealed-box encrypts, 10 policy
  verifies, 100 image classifications, 24h-compressed battery
  simulation.
- **Regression flag at > 10%.** Any metric in §6 or §8 regressing by
  > 10% fails the PR build. The check produces a diff table in the
  PR comment so reviewers see the delta.
- **Soak detection.** Weekly 7-day soak on a bench Pixel monitors
  memory creep, FD leaks, cert-rotation failures
  ([`DNS_RESOLVER.md`](DNS_RESOLVER.md) §15).
- **Release-gate parity.** No release ships if any §6 row is in the
  red on the bench Pixel smoke test ([`OTA.md`](OTA.md) §16).

---

## 16. References

- Android performance best practices —
  https://developer.android.com/topic/performance
- Jetpack Microbenchmark —
  https://developer.android.com/topic/performance/benchmarking/microbenchmark-overview
- ionspin/kotlin-multiplatform-libsodium —
  https://github.com/ionspin/kotlin-multiplatform-libsodium (sealed-box
  + Ed25519 perf characteristics)
- mini-dns — https://github.com/MiniDNS/minidns (resolver perf
  characteristics)
- BLAKE3 specification — https://github.com/BLAKE3-team/BLAKE3-specs
- MediaPipe Tasks Image Classifier (Android) —
  https://developers.google.com/mediapipe/solutions/vision/image_classifier/android
- DontKillMyApp — https://dontkillmyapp.com/ (background process /
  geofence battery survival across OEMs)
- Pixel 7 reference benchmarks (community, 2024)
- Internal: [`PROTOCOL.md`](PROTOCOL.md), [`CRYPTO.md`](CRYPTO.md),
  [`DNS_RESOLVER.md`](DNS_RESOLVER.md),
  [`AI_IMPLEMENTATION.md`](AI_IMPLEMENTATION.md), [`OTA.md`](OTA.md),
  [`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md),
  [`GEOFENCING.md`](GEOFENCING.md),
  [`../ARCHITECTURE.md`](../ARCHITECTURE.md),
  [`SIMPLIFY.md`](SIMPLIFY.md), [`DEFENSES.md`](DEFENSES.md),
  [`ATTACKS.md`](ATTACKS.md).
