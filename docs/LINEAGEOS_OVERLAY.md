# OpenWarden: LineageOS Overlay / Patchset Feasibility

> **Companion to** [`openwarden-browser-and-os.md`](openwarden-browser-and-os.md). That doc rejected forking AOSP/GrapheneOS on cost grounds and recommended GrapheneOS-on-Pixel as the "tech-savvy parent" path. **This doc answers a different question:** can OpenWarden ship an OS-level integration that works on *broad device support* — not just Pixel — by riding LineageOS instead of forking AOSP?
>
> **TL;DR.** A LineageOS overlay is technically credible, dramatically cheaper than an AOSP fork, and the right vehicle if OpenWarden ever needs broad-device OS-level integration. But it's still a v3+ experiment, not a v1/v2 commitment. The recommended distribution model is a **flashable zip overlay** (Option C below) applied on top of stock LineageOS, gated to a documented allowlist of bench-tested devices. Adoption ceiling is realistically <5% of OpenWarden's user base; build it only if grant money funds a dedicated maintainer. Until then: document the path, don't promise it.

---

## 1. What is a LineageOS overlay

LineageOS is the largest community Android distribution, with ~60 officially-maintained device builds in mid-2026 across Pixel, Samsung A/S, OnePlus, Fairphone, Sony, Motorola, Xiaomi/POCO, and Nothing hardware. It's Apache-2.0-licensed AOSP with device trees contributed by per-device maintainers.

An "overlay" in the LineageOS ecosystem can mean three different things; they matter for OpenWarden differently:

| Overlay type | What it is | Scope | OpenWarden fit |
|---|---|---|---|
| **RRO (Runtime Resource Overlay)** | Resource-only XML overrides for theming / config | Per-device or generic | Trivial; ships strings, colors, defaults |
| **Vendor module overlay** | A folder placed under `vendor/lineage/` in the build tree, compiled into the image | Per-build | Where OpenWarden would inject pre-installed priv-apps, AVB key allowlist additions, default-app overrides |
| **Flashable zip "addon"** | A signed update.zip that copies files into `/system` or `/product` post-flash | Cross-device, applied on top of any LineageOS build | Where OpenWarden ships to end users |

The flashable-zip pattern is well-established: OpenGApps, MicroG installer, NikGapps, and DivestOS's "addon" packages all use it. It works because LineageOS's `/product` and `/system` partitions accept signed overlay zips through recovery (LineageOS Recovery, TWRP, OrangeFox) without requiring a custom build.

References: <https://wiki.lineageos.org/extending.html>, <https://github.com/LineageOS>, AOSP RRO docs at <https://source.android.com/docs/core/runtime/rros>.

## 2. What a OpenWarden overlay could deliver

Realistically achievable from an overlay (no AOSP fork, no kernel patches):

- **Pre-installed OpenWarden as `priv-app`.** A priv-app at first boot can self-elevate to Device Owner without the dance of `adb shell dpm set-device-owner` *or* QR provisioning, because it's already system-signed and trusted. This is the single biggest UX win.
- **AVB / signing-key allowlist additions.** Add OpenWarden's parent-team public key to the LineageOS root-of-trust allowlist so a OpenWarden-signed update.zip flashes cleanly. Verified Boot still works in YELLOW (custom key) state, not RED.
- **Default-app overrides.** Replace LineageOS's default Jelly browser with Vanadium (or Cromite). Set OpenWarden as default launcher / default DPC at first boot via `overlay/frameworks/base/core/res/res/values/config.xml`.
- **Lock-screen surface trimming.** RRO can disable the lock-screen camera shortcut, the voice-assistant shortcut, and emergency-info edit by toggling `config_keyguardShowCameraAffordance` and friends. This is real and doesn't require a fork.
- **Settings app pruning at the framework level.** Hide Developer Options entry-point, hide "Reset" sub-tree, hide "Apps with usage access" — done via Settings app `EXCLUDED_PREFS` overlay, which is more durable than DPC `setApplicationHidden(Settings)`. The DPC hides the app; the overlay hides individual surfaces while keeping Settings usable.
- **Default Private DNS preset.** Ship `config_default_private_dns_mode` and `config_default_private_dns_specifier` pointing at the OpenWarden DoT/DoH endpoint at first boot.
- **Play Services Help intercept (partial).** A framework-level URL handler override can route `support.google.com/help/*` and `gds.google.com/*` through a OpenWarden allowlist check before the WebView opens. **This is the one capability genuinely unreachable from a stock-Android DPC** and the only fork-worthy capability per `openwarden-browser-and-os.md` §12.

What an overlay **cannot** deliver (still requires a fork or kernel work): kernel App Standby tuning, hardened malloc / MTE, sandboxed Play Services, SELinux policy tightening at boot. These stay GrapheneOS-only.

## 3. Distribution models

| Option | What it is | Per-release cost | Devices supported | Maintenance model | Verdict |
|---|---|---|---|---|---|
| **A. Patchset repo** | GitHub repo of `.patch` files; user clones LineageOS device tree, applies, builds | Trivial to publish | All LineageOS devices (theoretically) | Patchset rebases per LineageOS release | Niche; <100 users globally. Useful for documentation, not for distribution |
| **B. Prebuilt images** | OpenWarden team builds & signs full LineageOS+OpenWarden images for top N devices | ~$3–5k/mo CI for 8 devices; ~16 FTE-hrs/release | Whatever OpenWarden benchtests | Per-device QA per release | Heavy. Closest to /e/OS model. Defer to v4+ |
| **C. Flashable zip overlay** | One `openwarden-overlay.zip` flashable on top of any stock LineageOS build via recovery | ~4–8 FTE-hrs/release | All LineageOS devices that pass a smoketest checklist | Single artifact rebases per LineageOS release | **Recommended.** Lowest cost, broadest reach |

**Recommended: Option C (flashable zip).** The overlay zip ships: (1) OpenWarden priv-app APK in `/product/priv-app/`, (2) Vanadium APK in `/product/app/`, (3) OpenWarden's parent pubkey added to the AVB allowlist via OTA-cert injection, (4) RRO APKs for lock-screen / Settings / Private DNS defaults, (5) an `updater-script` that survives Lineage's monthly OTA dance. Users flash stock LineageOS first (their existing path), then flash `openwarden-overlay.zip` second. Both verifiable; both reversible.

## 4. Per-device burden

The critical claim to validate: *does a generic overlay actually work across LineageOS's ~60 device builds, or does it fragment?*

- **Framework-level changes** (lock-screen, Settings, Private DNS defaults) live in AOSP code that LineageOS inherits unchanged across all devices. Overlay applies cleanly to all.
- **Vendor/HAL** changes do not appear in the OpenWarden overlay; this is intentional.
- **Partition layout** differs across Treble/non-Treble, A/B vs A-only, dynamic vs static partitions. The overlay zip's `updater-script` must detect partition layout and place files in `/product` vs `/system` accordingly. ~80 lines of edify script, well-trodden territory.
- **Signing-key allowlist** is per-LineageOS-build but identical across LineageOS devices for any given release. One allowlist update per LineageOS release covers all devices.

**Realistic posture:** target a generic overlay, document a *tested-on* list (start with 5–8 devices: Pixel 7/8/8a, Samsung A54, OnePlus 9/10, Fairphone 4, Sony Xperia 1 IV), and accept "should work on other LineageOS devices — report issues" for the long tail. This matches LineageOS's own posture toward device-tree maintainers.

## 5. Maintenance cost

| Workstream | Cadence | FTE-hours/cycle |
|---|---|---|
| Rebase overlay against new LineageOS monthly release | Monthly | 4–6 |
| Re-sign overlay zip + ship | Monthly | 1 |
| Re-bench on 5–8 reference devices | Monthly | 4–8 |
| Vanadium / browser update | Vanadium-cadence (~weekly) | 1 per update; auto-pipeline most |
| Per-LineageOS-major regression sweep | Annually | 16 |

**Total: ~12–20 FTE-hours/month** in steady state. Doable by one grant-funded part-time maintainer (~0.15 FTE) once tooling is built. The *upfront* cost to build the tooling (CI, signing pipeline, bench-test rig, updater-script for partition variance) is the real expense: ~4–6 FTE-months.

Compare to forking AOSP from `openwarden-browser-and-os.md` §10: **~2 FTE forever**. The overlay is ~10× cheaper. That's the entire reason this path is worth investigating.

## 6. Browser inclusion

LineageOS ships Jelly, a thin WebView wrapper that exists mostly as a fallback. It is not security-credible against the C2 / D1 attack surface in `openwarden-browser-and-os.md` §1. Replace it.

**Recommended bundle:** Vanadium as the default browser, replaced via priv-app placement and `default_browser` config override. Cromite or IronFox as a fallback for devices Vanadium doesn't build for (Vanadium is Pixel-tuned but does build for generic AOSP arm64 — confirm at bench time). The OpenWarden allowlist policy is injected via Chromium managed-config (`URLBlocklist`/`URLAllowlist`), which Vanadium reads. This converges with `openwarden-browser-and-os.md` Browser Option B; no extra browser work specific to the overlay path.

## 7. What overlay can't fix

Honestly enumerating limits:

- **Bootloader unlock requirement.** Installing LineageOS at all requires `fastboot flashing unlock` on most devices. This wipes the device and is irreversible on some Samsung/Xiaomi models (Knox tripped). The "broad device support" claim has a footnote: *broad device support among devices whose owners are willing and able to unlock the bootloader*. That's a much smaller pool than "all Android devices."
- **Verified Boot color.** Bootloader unlock → AVB cannot be GREEN. With OpenWarden's pubkey in the allowlist, the device boots YELLOW (custom key) — the orange warning at boot stays. Parents must trust the OpenWarden build pipeline visually as well as cryptographically.
- **Carrier services.** eSIM provisioning, RCS, VoLTE — LineageOS handles these unevenly per device. Overlay does not improve this; carrier QA stays a LineageOS problem.
- **Modem firmware.** Closed-source per-device. Overlay cannot touch.
- **OEM-locked devices.** Many Samsung US carrier devices, some Xiaomi regions, most newer Huawei devices cannot install LineageOS at all. Excluded from the overlay's reach by definition.
- **OTA security patches.** LineageOS targets AOSP monthly bulletin but lags ~2–6 weeks behind Google. Overlay inherits that lag. Parents who want zero-lag patching need stock Pixel or GrapheneOS, not LineageOS+OpenWarden.

## 8. Prior art

| Project | Model | Status mid-2026 | Lesson for OpenWarden |
|---|---|---|---|
| **/e/OS (Murena)** | Full LineageOS fork with privacy mods, microG, Murena cloud | ~80 devices, paid SaaS model, ~$10M raised | Closest production analog. Demonstrates LineageOS-fork-with-opinions is viable at scale **if** there's a paid revenue stream funding ~5 FTE |
| **iode OS** | LineageOS fork, France-based privacy focus | ~30 devices, paid hardware sales | Smaller, similar shape, hardware-bundle revenue |
| **DivestOS** | LineageOS + hardening patches, single maintainer | **Retired late 2024.** Final builds shipped; project archived | Cautionary tale: solo-maintainer LineageOS work is unsustainable past ~5 years |
| **CalyxOS** | AOSP fork (not LineageOS-based), Pixel-only | ~6–10 maintainers, foundation-funded | Different architecture (AOSP fork, not overlay), but proves foundation-funded model |
| **LineageOS for microG** | Overlay-style microG bundling | Active, community-maintained | Closest *technical* analog: a generic overlay/fork that adds an opinionated component across all LineageOS devices |
| **Kid-focused LineageOS fork** | n/a — none exists as of 2026 search | — | Greenfield. No incumbent to compete with; also no incumbent to inherit user base from |

The DivestOS retirement is the most relevant data point: it shows that even a *hardened* LineageOS variant maintained by one passionate person cannot survive without funding. The /e/OS counterexample shows the model works *with* funding. OpenWarden falls between these.

## 9. Recommendation for OpenWarden

Stage-gated:

- **v1.** No overlay. App-only on stock Android. Force-Chrome + DNS filter per `openwarden-browser-and-os.md` §6 covers the headline threats. Document LineageOS as "possible future path"; do not promise.
- **v2.** Still no overlay. Browser Option B (Vanadium wrapper on stock) if D1 telemetry justifies. The overlay remains a future v3+ item.
- **v3.** Overlay PoC for a single device (Pixel 7 — easiest because LineageOS's Pixel 7 build is mature and Vanadium builds there natively). Bench-only, not shipped. Validate (a) priv-app self-elevation to DO, (b) Vanadium replacement, (c) Private DNS override survives OTA, (d) AVB key allowlist works, (e) overlay survives LineageOS monthly OTA without hand-holding. ~2 FTE-months.
- **v4.** If v3 PoC succeeds *and* grant funding lands for a part-time maintainer, expand bench to 5–8 devices and ship the overlay zip as an opt-in alternative distribution. If grant funding does not materialize, archive the PoC and document the path for future contributors.

Timeline summary:

| Version | OS posture | New investment |
|---|---|---|
| v1 (2026) | App-only on stock Android (Pixel default, GrapheneOS docs) | 0 |
| v2 (2026 H2) | App-only; Vanadium wrapper conditional | 0 toward overlay |
| v3 (2027) | LineageOS overlay PoC, Pixel 7 only, bench-only | ~2 FTE-months |
| v4 (2027 H2+) | Overlay shipped as opt-in, IF funded | ~4–6 FTE-months upfront + ~0.15 FTE ongoing |

## 10. Distribution chicken-and-egg

The overlay's *technical* maturity is the easy part. The hard part is adoption:

- Parent must own (or buy) a device LineageOS supports.
- Parent must unlock bootloader. Many won't or can't.
- Parent must flash LineageOS, then flash the OpenWarden overlay. This is a 30-minute fastboot session on Linux/macOS, an hour on Windows. Non-tech parents cannot complete it.
- Parent must visually trust the YELLOW boot warning and disregard it forever.
- Kid must not later wipe the device and re-flash stock firmware — overlay does nothing to prevent this beyond what stock LineageOS does (relock-bootloader-with-custom-key path is theoretically possible but device-specific and brittle).

**Realistic audience ceiling: 2–5% of OpenWarden's eventual user base.** Tech-leaning parents who already know what LineageOS is, who already own an unlocked device, who specifically want OS-level integration. Everyone else stays on the app-only path on stock Android.

This is consistent with /e/OS's reported numbers: a few hundred thousand active devices globally across ~80 supported models, after ~6 years of marketing and pre-installed hardware sales. OpenWarden without hardware sales would do less.

## 11. Why research this now

User asked. The /e/OS-shaped path needed an explicit yes/no/defer answer rather than being lumped under the AOSP-fork rejection in `openwarden-browser-and-os.md`. Locking the decision now prevents the question from being reopened every release cycle by contributors who notice that LineageOS exists and assume nobody on the team has thought about it.

**Decision recorded: yes-possible-at-v3, no-not-for-v1-or-v2, conditional-yes-at-v4-if-funded.** Document the architecture sketch in §2–§7 of this file so a future maintainer (OpenWarden's or a downstream community fork's) can pick it up without re-deriving the analysis.

## 12. References & related paths

- LineageOS extending guide: <https://wiki.lineageos.org/extending.html>
- LineageOS GitHub org: <https://github.com/LineageOS>
- AOSP RRO docs: <https://source.android.com/docs/core/runtime/rros>
- AOSP A/B + dynamic partitions: <https://source.android.com/docs/core/ota/ab>
- /e/OS (Murena): <https://e.foundation>
- iode OS: <https://iode.tech>
- DivestOS archive: <https://divestos.org>
- LineageOS for microG: <https://lineage.microg.org>
- AVB / Verified Boot: <https://source.android.com/docs/security/features/verifiedboot>
- F-Droid: distributes APKs but not flashable zips natively; OpenWarden overlay would ship from openwarden.org/download with detached PGP signature, not F-Droid.
- **Magisk vs overlay:** Magisk is a root-required module loader; overlay is a baked-in (no-root) system modification. `openwarden-browser-and-os.md` §11 Option E rejected Magisk on threat-model grounds (root defeats AVB). Overlay does not require root and does not defeat AVB; this is the principal reason it's worth pursuing where Magisk is not.

---

**Bottom line:** The LineageOS overlay path is the right architectural answer to "broad-device OS-level integration" — much cheaper than an AOSP fork, much more capable than a DPC-only app. But the adoption ceiling is low and the upfront cost is real. Build the v3 PoC; ship the v4 product only if a maintainer is funded. Until then, OpenWarden stays an app on stock Android, with this document standing as the receipt that the OS-overlay question was answered, not ignored.
