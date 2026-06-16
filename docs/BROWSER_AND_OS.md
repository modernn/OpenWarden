# OpenWarden: Browser & OS-Level Strategy

> **Two big architectural moves under evaluation:**
> (1) Build or bundle a "OpenWarden Browser" to close in-app-WebView and DoH-in-Chrome bypasses.
> (2) Fork GrapheneOS (or partner with it) for system-level integration.
>
> **TL;DR.** Don't build a browser fork. Don't fork an OS. For v1, force Chrome with enterprise policy + DNS filter (covers ~85% of the threat). For v2, wrap Vanadium or Cromite as the bundled "OpenWarden Browser" *only if* telemetry shows DNS gaps. For OS-level, document GrapheneOS as a supported alternative target, contribute upstream where useful, and explore a CalyxOS "OpenWarden edition" partnership at v3. Anything beyond that is project-suicide-by-scope per [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md) §1.

Companion to [`ATTACKS.md`](openwarden/docs/ATTACKS.md), [`DEFENSES.md`](openwarden/docs/DEFENSES.md), [`DNS_FILTER.md`](openwarden/docs/DNS_FILTER.md), [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md).

---

# Part 1: Custom Browser Strategy

## 1. Why a browser solves real problems

Attacks the DNS filter (defense #17) does not fully close, but a browser-layer defense would:

| Problem | DNS filter coverage | Browser-layer coverage |
|---|---|---|
| **D1** — In-app WebView (Discord, Roblox, Snap) | Partial: blocks blocklisted hosts only; doesn't catch new domains, doesn't see URL paths | n/a — these are not browsers |
| **C2** — DoH-in-Chrome | Closed via Chrome managed-config `DnsOverHttpsMode=off` | Closed by construction (we own the stack) |
| **P9** — Play Services Help WebView | DNS-block `support.google.com/help/*` and `gds.google.com` paths — fragile | Cannot close; Play Services is system app |
| **D9** — Chrome PWA installs | Chrome enterprise policy | Closed by construction |
| Kid login workarounds (incognito, cookies, IndexedDB) | None | Owned: no incognito, parent-signed cookie wipe |
| URL allowlisting (category filters, paths) | Hostname-only | Full URL + path |
| Bookmarks pinned by parent | None | Owned |

The browser layer is strictly more powerful than the DNS layer for URL control. The question is whether the maintenance cost is survivable.

## 2. Existing OSS Chromium/Firefox forks — comparison

Knowledge as of late 2025 / early 2026. Treat as a snapshot; verify before committing.

| Browser | Engine | License | Last release | Active maintainers | Patch lag vs upstream | APK size | Kid-relevant features | Notes |
|---|---|---|---|---|---|---|---|---|
| **Vanadium** | Chromium | MIT | Continuous (tied to GrapheneOS) | ~3 core (Daniel Micay + 2) | ~0–2 days behind Chrome stable | ~95MB | Strict site isolation, no DRM by default, isolated app, content filter API, JIT-off mode | Tightest patch SLA of any OSS Chromium fork. Ships on GrapheneOS; sideloadable on stock Android. No managed-config equivalent. |
| **Bromite** | Chromium | GPL-3.0 + Apache-2.0 | Archived April 2023 | 0 | Dead | n/a | Historical: ad/tracker blocking, SafeBrowsing-replaceable | **Don't use.** Repo archived; security-dead. |
| **Cromite** | Chromium (fork of Bromite) | GPL-3.0 | Every ~2 weeks | ~1 active (uazo) | ~1–3 weeks behind Chrome stable | ~115MB | Bromite-style adblock, per-site settings, fingerprint protection | Single maintainer, but disciplined release cadence. Bus-factor 1. |
| **Mull** | GeckoView (Firefox) | MPL-2.0 | Continuous via DivestOS | ~1 (Tad Wolfshohl), shared w/ DivestOS | ~3–14 days behind Firefox release | ~85MB | uBlock-friendly, ArkenFox-style prefs, Tor-tier privacy patches | **Discontinued late 2024**, replaced by IronFox. Different engine — fewer Chromium WebView interactions. |
| **IronFox** | GeckoView (Firefox) | MPL-2.0 | Every ~2 weeks | ~2 (Mull's spiritual successor) | ~1–7 days behind Firefox release | ~90MB | Inherits Mull's preset, active uBlock Origin support | Most credible Firefox-on-Android fork in 2026. Younger project; less proven. |
| **DuckDuckGo Browser (Android)** | Android WebView (System) | Apache-2.0 | ~Monthly | DDG private browsing team (~5) | Bound to system WebView updates | ~25MB | App tracking protection (system-wide VPN), tracker block | Uses system WebView → no engine maintenance — but also no engine control. Same DoH-bypass surface as Chrome. |
| **Brave (Android)** | Chromium | MPL-2.0 | Weekly | Brave Inc (paid, large team) | ~3–10 days | ~140MB | Built-in adblock, Tor-tab, fingerprinting protection | Crypto/rewards UX, ignores Android enterprise policy, ships its own DoH. **Reference only**; not deployable for kids. |
| **Tor Browser for Android** | GeckoView | BSD-3-Clause | ~Monthly | Tor Project (paid, ~3 on Android) | ~7–21 days behind Firefox ESR | ~75MB | Onion routing, fingerprint resistance | Overkill; Tor exit policy unsuited to kid use; deanonymization for parent visibility is the wrong direction. |

**Winners worth tracking:** Vanadium (best patch SLA, narrow scope), Cromite (best bypass-defense OOTB but bus factor 1), IronFox (engine diversity, good momentum).

## 3. Maintenance burden — Chromium reality

Chromium ships a stable release every ~4 weeks (Milestone N every 28 days) with mid-cycle security patches roughly weekly. A meaningful security CVE typically lands in Chrome stable within 24–72 hours of the embargo lift. A fork that lags two weeks averages ~7–10 days of in-the-wild CVE exposure per cycle. For an adversary that is a 10-year-old, this is fine. For an adversary that is the kid's older friend, a school adversary, or a stalkerware vendor, it is not.

**Vanadium** achieves ~0–2 day patch lag with three people because it carries a *narrow* patch set (mostly the Chromium build config plus GrapheneOS-specific isolation primitives). It does not reimplement features; it deletes them.

**Cromite** runs ~1–3 weeks behind with one maintainer because the patch set is larger (ad/tracker block, fingerprint, per-site settings). The maintainer publicly states this is the upper limit of one-person bandwidth.

**Inference for OpenWarden:** any fork that adds *new* features (parent-signed allowlist, managed-config injection, audit-log integration) inflates the patch set and the rebase burden. A two-weekend-per-cycle rebase obligation kills the [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md) 5-year test on day one.

## 4. Option A — Full "OpenWarden Browser" fork

| Dimension | Estimate |
|---|---|
| Engine | Chromium upstream |
| Time-to-MVP | 3–6 months FTE |
| Ongoing maintenance | 1 FTE forever, plus 1 part-time for CVE rebases |
| Build infrastructure | ~$1.5k/mo CI (Chromium compile is ~6h on 32-core; needs reproducible builds for F-Droid) |
| Security patch SLA target | < 7 days to track Chrome stable |
| 5-year survivability | **No.** Solo or grant-funded project cannot fund 1 FTE forever on a browser engine. |

**Verdict: hard no.** Even if grant funding doubled, this is the wrong place to spend it. Chromium is a multi-billion-dollar engineering effort; competing with its security cadence is futile.

## 5. Option B — Thin wrapper around Vanadium or Cromite

The idea: don't fork the engine. Ship a OpenWarden-branded build (or a config overlay) on top of an existing well-maintained fork.

| Dimension | Estimate |
|---|---|
| Engine | Vanadium (preferred) or Cromite (backup) |
| Time-to-MVP | 4–8 weekends |
| Ongoing maintenance | ~1 weekend per upstream release (~monthly) |
| Patch SLA | Inherited from upstream (Vanadium ~0–2 days; Cromite ~1–3 weeks) |
| What OpenWarden ships | (a) Parent-signed allowlist bundle reader (b) Mandatory parent bookmarks (c) No incognito switch (d) No extension surface (e) Audit-log hook into the existing sealed-box pipeline (Defense #5) (f) Signed-policy override path |
| What OpenWarden does NOT ship | Engine patches, sandbox tweaks, CVE backports |
| 5-year survivability | **Yes — conditional on upstream survival.** If Vanadium folds, fall back to Cromite or IronFox. |

**Risk:** all three upstreams have a bus factor of ~1–3. Bundling means OpenWarden inherits that risk. Mitigation: write the policy/allowlist layer as a thin shim against a well-defined extension point (e.g., Chromium's `URLBlocklist`/`URLAllowlist` policies, which Vanadium can read via `setApplicationRestrictions`), so we can re-target if needed.

**Verdict: viable for v2.** Defer until v1 ships and telemetry-free signal (parent issue reports) confirms DNS-only is insufficient.

## 6. Option C — Force-Chrome + enterprise policy + DNS filter (v1 plan)

| Dimension | Estimate |
|---|---|
| Engine | Chrome stable (Google) |
| Time-to-MVP | 1–2 weekends (already most of the way per [`DNS_FILTER.md`](openwarden/docs/DNS_FILTER.md) §5) |
| Ongoing maintenance | Minimal — Chrome handles CVEs; we revalidate policies per Chrome major |
| Closes | C2 (DnsOverHttpsMode=off), kid §5.6 incognito, browser-level URL block (URLBlocklist), SafeSites, sync/sign-in |
| Does NOT close at the browser layer | D1 (Discord/Roblox WebView — caught at DNS layer only), D9 (PWA — partial), P9 (Play Services Help) |
| 5-year survivability | **Yes**, with the caveat that we depend on Google maintaining Chrome enterprise policies on Android. Historically stable. |

**Trade-offs of Option C:**

- **Pro:** Chrome's patch SLA is the gold standard. No engine work. No fork bus factor.
- **Pro:** Managed config gives surprisingly strong control: `URLBlocklist`, `URLAllowlist`, `SafeSitesFilterBehavior`, `IncognitoModeAvailability`, `SyncDisabled`, `BrowserSignin=0`, `BookmarkBarEnabled`, `ManagedBookmarks`, `PasswordManagerEnabled=false`.
- **Con:** Chrome is Google. Telemetry to Google by default (mitigable via `MetricsReportingEnabled=false`, `SafeBrowsingExtendedReportingEnabled=false`).
- **Con:** Chrome WebView (system) is shared. D1 attacks via in-app WebView don't hit Chrome's policy surface — they hit the system WebView, which honors zero managed-config knobs. DNS filter must catch these.
- **Con:** Doesn't close D1 at the browser layer — only at the DNS layer. If DNS goes down, D1 is wide open.

**Verdict: best v1.** Possibly enough forever if the DNS filter is good and parent education on D1 is loud.

## 7. The Play Services Help WebView (P9) special case

The bypass: kid opens any contact-website-link surface inside Google Play Services Help (or a Google account-info help link) and the embedded `com.google.android.gms.help.HelpActivity` loads arbitrary URLs in a WebView whose UA, cookies, and storage are independent of Chrome.

**Constraints:**
- Play Services is a system app. Cannot `setApplicationHidden` it. Cannot `setPackagesSuspended` it. Cannot uninstall.
- `com.google.android.gms.help` lives inside the GMS bundle; we cannot kill that activity individually.
- WebView traffic from this surface does respect system DNS (private DNS / DoT) by default — but only because GMS doesn't ship its own DoH for help pages, today.

**Defenses (layered):**

1. **DNS-block the help endpoints.** Block `gds.google.com`, `gds.google.com/gmsdrops/*`, `support.google.com/contacts/*`, `support.google.com/families/*`. Maintain a watch-list updated alongside the DNS filter blocklist. Closes 80%+ today.
2. **Block contact-website-URL editing.** Investigate `DevicePolicyManager.setCrossProfileContactsSearchDisabled` and managed-contact restrictions. Limited reach — this gates which contacts are visible, not whether the help WebView opens.
3. **Accessibility-service hack: detect HelpActivity launch + force-finish.** This is stalkerware-shaped. **Reject.** Violates [`DEFENSES.md`](openwarden/docs/DEFENSES.md) anti-patterns and the no-monitoring pledge.
4. **Document residual risk.** Surface in onboarding: "Google Help screens can sometimes open arbitrary web pages. We block known endpoints; report new ones."

**Recommendation:** DNS-blocklist + watch + parent disclosure. Accept residual risk. No code-side enforcement beyond #1.

## 8. Browser recommendation by version

| Version | Approach | Effort | Maintenance |
|---|---|---|---|
| **v1** | Option C: force Chrome via DPC, Chrome managed-config, DNS filter, browser blocklist for Firefox/Brave/Samsung/Vivaldi/Kiwi/Opera | 1–2 weekends (most done in [`DNS_FILTER.md`](openwarden/docs/DNS_FILTER.md)) | Quarterly policy revalidation |
| **v2** | Option B: thin OpenWarden wrapper around Vanadium (preferred) or Cromite. Ship signed allowlist, no-incognito, parent bookmarks, audit-log hook | 4–8 weekends if D1 telemetry justifies | ~1 weekend per upstream release |
| **v3+** | Option A only if a dedicated browser team materializes (probably never). Otherwise stay on Option B. | — | — |

**Decision rule for v1 → v2 escalation:** if more than 20% of parent issue reports cite D1 (in-app WebView bypass) after v1.x ships, prioritize Option B. Otherwise stay on Option C.

---

# Part 2: OS-Level Integration

## 9. Why OS-level matters

A Device Owner DPC, however thoroughly configured, sits *above* the system services and *below* the hardware. There is a thick slab in the middle — kernel, framework, system apps — that the DPC cannot reach. OS-level integration could:

- Intercept Play Services Help WebView at the framework level.
- Pre-bundle OpenWarden as a signed system app at first boot (no provisioning ceremony).
- Enforce a custom Verified Boot chain (AVB key rooted in the parent's identity).
- Modify kernel App Standby behavior to prevent doze-based watchdog kills.
- Replace Play Services entirely with sandboxed Google Play, gating which apps see GMS at all.
- Strip lock-screen camera, voice assistant, and emergency-info surfaces at the framework level (today these are partially reachable from the lock screen on stock Android).

These are real wins. The cost question is whether they're affordable.

## 10. The cost of forking an OS

| Project | Team size | Patch cadence | Build infra | Hardware support | License |
|---|---|---|---|---|---|
| **AOSP** (Google) | thousands | Monthly security bulletin; quarterly feature drops | Google internal | All Pixel + reference boards | Apache-2.0 |
| **GrapheneOS** | ~15 active contributors, lots of orbital community | Tracks AOSP within ~24–72h of release | ~$10k/mo donated CI | Pixel 6–9 (strict allowlist) | MIT for original code, AOSP licensing for inherited |
| **CalyxOS** | ~6–10 active | ~3–10 days behind AOSP | Calyx Institute funded | Pixel 4a–9, FP4–FP5 | Apache-2.0 / MIT |
| **DivestOS** | 1 (Tad Wolfshohl) | Variable; ~weekly | Personal hardware | LineageOS-supported devices | GPL-3.0 |
| **LineageOS** | ~30 active maintainers across devices | ~2–6 weeks behind AOSP | Community CI | 200+ device-specific maintainers | Apache-2.0 |

**Cost reality for a OpenWarden fork:**

- Build infrastructure: AOSP full build is ~4h on 64-core; CI for 4 device variants × 2 channels × 2 architectures ≈ $5k–10k/mo even with discounted cloud.
- Hardware support: Pixel bootloader signing keys, vendor blob updates, modem firmware updates rotate quarterly. Each rotation = re-validation effort.
- Security patch latency: AOSP monthly bulletin → must rebase, build, validate, release within ~7 days for credibility. ~1 FTE-week per cycle, ~13 cycles/year.
- New SoC support: Tensor G5/G6 require new vendor partitions. Roughly 2 FTE-weeks per new Pixel generation.
- **Total ongoing cost: ~2 FTE forever** for a minimal OpenWarden-AOSP fork.

**Verdict: hard no.** Forking an OS is project-suicide-by-scope. The grant runway dies at month 6. [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md) §1 five-year test fails before v1 even ships.

## 11. Better alternatives — six options

| Option | What it is | Cost | Tier | Recommendation |
|---|---|---|---|---|
| **A. Contribute upstream to GrapheneOS** | Propose Vanadium URL-allowlist policy reader, Device Owner integration hooks | S, occasional | Tier 1 | **Yes** — small targeted PRs only |
| **B. Partner with CalyxOS** | Ship a "OpenWarden edition" CalyxOS image pre-configured with OpenWarden as DO | M build, M maintain | Tier 2, v3+ | **Maybe** — explore at v3 if user base exists |
| **C. Use Vanadium as bundled browser** | Sideload Vanadium on stock Android as OpenWarden's bundled browser | S | Tier 1 / v2 | **Yes** — converges with Browser Option B |
| **D. Run on GrapheneOS but OpenWarden-aware** | Document that GrapheneOS users get extra hardening for free; ensure DPC code-paths work on GrapheneOS | S | Tier 0 / v1 | **Yes** — cheap, documentation only |
| **E. Magisk module for OS-level hooks** | Root-required hooks for App Standby, framework interception | M build, M maintain | Tier 3 / never | **Reject** — defeats security goals (root = no AVB), violates threat model |
| **F. Custom AVB key chain at provisioning** | Re-flash AVB key during provisioning to pin OpenWarden as system app | L, L maintain | Tier 3 / never | **Defer indefinitely** — complexity black hole |

**Recommended posture:** A + C + D. Skip B until there's grant funding and user demand. Reject E and F.

## 12. DPC-vs-OS capability matrix

| Capability | DPC layer (v1) | OS fork (theoretical) | Worth the fork? |
|---|---|---|---|
| Block apps | ✅ `setPackagesSuspended` | ✅ remove at build | No — DPC is enough |
| Block install sources | ✅ `DISALLOW_INSTALL_UNKNOWN_SOURCES` | ✅ | No |
| Block Settings surfaces | Partial (`setApplicationHidden` Settings) | ✅ at framework | Marginal |
| Block VPN bypass | ✅ `DISALLOW_CONFIG_VPN` + always-on | ✅ kernel-level | No |
| Force private DNS | ✅ `setGlobalPrivateDnsMode` | ✅ | No |
| Block Play Services Help WebView | ❌ (P9 stays open) | ✅ at framework | **Maybe** — but DNS-block is cheaper |
| Modify lock-screen surfaces (camera, assistant) | Partial | ✅ at framework | **Maybe** — minor wins |
| Pre-bundle OpenWarden at first boot | ❌ | ✅ | **Maybe** — convenience, not security |
| Kernel-level App Standby control | ❌ | ✅ | Marginal — FGS watchdog suffices |
| Replace Play Services | ❌ | ✅ (sandboxed Play) | **Yes** — but GrapheneOS already does this |
| Custom AVB key chain | ❌ | ✅ | Marginal — Google AVB is fine for v1/v2 |
| FRP behavior modifications | ❌ | ✅ | No — FRP is sufficient |

**Reading:** of the 12 capability gaps, only **Play Services Help WebView block**, **replace Play Services**, and (weakly) **kernel App Standby** are fork-worthy. All three are addressed without forking: (a) DNS block for the first, (b) GrapheneOS-as-target for the second, (c) FGS watchdog + post-OTA self-test (Defense #10) for the third.

**Conclusion: nothing in the capability matrix justifies a fork.**

## 13. Recommended OS strategy

| Audience | Default OS | Why |
|---|---|---|
| Median parent (low-tech) | **Stock Pixel 7/7a/8/8a** | Out-of-box experience, Play Store works, broad app support, hardware-attested StrongBox |
| Tech-savvy parent | **GrapheneOS on Pixel** | Vanadium baseline, hardened malloc, sandboxed Play, kernel hardening, no Google account on device required |
| Activist parent | **CalyxOS on Pixel** | microG option, datura firewall, similar baseline |
| Hardcore parent | **GrapheneOS + OpenWarden locked-down profile** | Maximum hardening; OpenWarden as primary DO |

**Strategic decisions:**

- **Don't fork.** Reaffirmed.
- **Recommend GrapheneOS** as a supported alternative, document in onboarding that GrapheneOS users get extra protections for free (Vanadium, hardened malloc, MTE, sandboxed Play). Update [`ATTACKS.md`](openwarden/docs/ATTACKS.md) restriction-audit to note GrapheneOS-specific deltas.
- **Contribute small upstream PRs** where OpenWarden needs hooks GrapheneOS doesn't expose. Likely targets: Vanadium URL allowlist policy reader, Device Owner-friendly install-approval surface. Keep PRs narrow and self-justifying for the upstream maintainers.
- **Maybe** explore a CalyxOS "OpenWarden Edition" pre-configured image at v3. CalyxOS already ships activist-preconfigured images; a family-preconfigured fork-image of CalyxOS is parallel. v3 conversation; defer.
- **Reject Magisk module path.** Root defeats AVB, defeats the threat model, violates [`DEFENSES.md`](openwarden/docs/DEFENSES.md) anti-patterns.
- **Defer custom AVB key chain** to v3+ if ever.

## 14. The strategic question: require GrapheneOS, or work on stock Android?

| Stance | Pro | Con |
|---|---|---|
| **Require GrapheneOS** | Vanadium baseline, no GMS attack surface, hardened malloc, MTE, kernel hardening, no Play Services Help WebView attack | Tiny user base (~few hundred thousand globally), Pixel-only, parents must reflash phones, onboarding friction kills adoption |
| **Stock Pixel only** | Out-of-box, Play Store available, every kid's phone is a Pixel-or-could-be, no reflashing | GMS attack surface stays (P9), Chrome stays the default browser, in-app WebView surface stays |
| **Both — stock default, GrapheneOS supported** | Median parent gets a working product; tech parent gets max hardening; no codebase forking required | Maintenance: test on both. Bench Pixel and bench GrapheneOS device required. Roughly +20% QA cost. |

**Recommendation: stock Pixel as v1 default, GrapheneOS as supported alternative.** Document both paths in onboarding. Add a single bench-test step per release ("verify on GrapheneOS device"). +20% QA cost is affordable; the alternative (lock out 99% of potential users to gain marginal security) is not.

---

# Consolidated recommendations

| Version | Browser strategy | OS strategy | Key new docs/code |
|---|---|---|---|
| **v1** | Option C: Force Chrome + managed-config + DNS filter + browser blocklist | Stock Pixel default; GrapheneOS supported alternative (documented) | [`DNS_FILTER.md`](openwarden/docs/DNS_FILTER.md) §5 Chrome policies + GrapheneOS support note in onboarding |
| **v2** | Option B *if* D1 telemetry justifies: wrap Vanadium as bundled "OpenWarden Browser" | Begin upstream contributions to Vanadium (URL-allowlist policy) | New: `BROWSER_WRAPPER.md` design doc |
| **v3+** | Option B becomes default. Option A never. | Explore CalyxOS "OpenWarden Edition" partnership if user base exists | ADR for partnership decision |

**Hard rejects (link [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md) §6 polite-no template):**
- Full Chromium fork ("OpenWarden Browser" from scratch)
- AOSP / GrapheneOS / CalyxOS fork
- Magisk-based OS hooks
- Custom AVB key chain at provisioning

**Soft maybes (revisit at v2/v3):**
- CalyxOS "OpenWarden Edition" image partnership
- Upstream Vanadium contributions for parental-control policies
- Per-app DNS via local VPN ([`DNS_FILTER.md`](openwarden/docs/DNS_FILTER.md) §8 already deferred to v2)

**The single most important takeaway:** the existing v1 plan (force Chrome + DNS filter + GrapheneOS-supported documentation) already closes the highest-severity attacks (C2, D9, most of D1) at a small fraction of the cost of either a browser fork or an OS fork. The remaining residual risk (P9 Play Services Help WebView, D1 unfiltered domains) is real but not large enough to justify rebuilding a browser engine or an OS. Document the residual risk in onboarding, ship v1, and let user-reported issue telemetry drive v2 priorities.
