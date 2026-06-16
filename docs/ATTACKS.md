# Threats & Attack Catalog

Adversary: motivated 9–13yo kid. Semi-technical (can follow YouTube + run adb), socially clever, time-rich. Not a nation-state. Not a forensic toolchain.

Two source catalogs (not duplicated here):
- **Technical:** [`C:\src\openwarden-redteam-adversary.md`](../../openwarden-redteam-adversary.md) — 60+ attacks across 15 categories, severity-rated.
- **Behavioral:** [`C:\src\openwarden-redteam-kids.md`](../../openwarden-redteam-kids.md) — social/persistence vectors.

This doc = synthesis: what wins, what doesn't, what v1 must defend.

---

## Top-priority attacker playbook

### Attacks a real kid tries FIRST
1. **Safe Mode reboot** — `DISALLOW_SAFE_BOOT` blocks; verify on bench Pixel.
2. **In-app WebView in Discord / Roblox / YouTube (D1, O2, O3)** — *not blocked by any current restriction.* See §"Wins" below.
3. **Factory reset via Settings** — `DISALLOW_FACTORY_RESET` blocks.
4. **Clock rollback / timezone trick** — `DISALLOW_CONFIG_DATE_TIME` blocks; design must use monotonic clock + signed parent timestamps anyway.
5. **Hand parent the phone with fake bug claim (K1, K2)** — social, unbounded.

### Attacks that ACTUALLY succeed on v1 OpenWarden
1. **In-app browser inside an allowlisted social/game app (D1, O2, O3).** Daily-driver bypass. Roblox/Discord/Snap embedded WebViews = full unfiltered web. No v1 plan defense.
2. **Social engineering the parent (K1).** Over months, a parent grants an exception under false pretense. Permanent baseline drift.
3. **Google Cast to TV (D5).** Out-of-scope but worth a parent note.
4. **Friend's phone / school Chromebook (kid §2).** Out-of-scope by definition.

### Behavioral priors for Oliver (age 10)
| Rank | Bypass | Confidence |
|---|---|---|
| 1 | Roblox-as-social-network | ~95% |
| 2 | Friend's unrestricted phone at school | ~90% |
| 3 | School Chromebook for YouTube/Discord-via-browser | ~85% |
| 4 | YouTube Shorts as TikTok replacement | ~85% |
| 5 | Asks dad (not mom) for one-time exception that becomes permanent | ~80% |
| 6 | Shoulder-surfs parent PIN within 60 days | ~70% |
| 7 | Sneaks parent's phone after bedtime at least once | ~65% |
| 8 | Smart TV YouTube binge after school | ~65% |
| 9 | Secondary Google account for unmanaged YouTube | ~55% |
| 10 | 5am-before-parents-wake use | ~50% |

---

## Categorical attack severity matrix

| Category | # attacks cataloged | Highest sev | Notes |
|---|---|---|---|
| A. Software (Settings/ADB/recovery/fastboot) | 10 | CRITICAL | Most blocked by DISALLOW_* but verify each restriction on bench |
| B. Hardware (bootloader/JTAG/SIM/SD) | 8 | LOW–HIGH | OEM unlock = real risk; rest infeasible for kid |
| C. Network (VPN/DoH/Tor/hotspot) | 9 | CRITICAL | DoH-in-browser (C2) defeats v2 DNS filter; replay (C8) without `policy_seq` is catastrophic |
| D. App-layer (WebView/file-picker/Play web/PWA) | 10 | CRITICAL | **D1 is the daily-driver bypass.** D8 (Play web install) needs install-approval in v1 |
| E. Account-layer | 5 | CRITICAL | E3 Guest user needs `DISALLOW_USER_SWITCH`; E4 parent-account takeover is social |
| F. Time/clock | 4 | HIGH | NTP spoof (F3) → design must use monotonic clock |
| G. Storage/state | 5 | HIGH | G2 storage-fill needs fail-closed posture |
| H. Crypto/protocol | 6 | CRITICAL | H1 replay, H2 sig-strip, H3 pubkey swap, H6 recovery-phrase shoulder-surf |
| I. Provisioning | 3 | MEDIUM | I3 OOBE window during reprovision |
| J. Communications | 4 | MEDIUM–HIGH | J2 BLE impersonation is v3 concern; v1 mDNS catches gap via heartbeat-silence alert |
| K. Social engineering | 4 | CRITICAL | **K1 most-successful long-term vector.** No code-only fix. |
| L. Side channels (PiP, Cast, mirror) | 4 | MEDIUM | Cast = out-of-scope |
| M. Out-of-band (Chromebook, console) | 3 | n/a | OUT OF SCOPE |
| N. Long-term / CVE | 4 | HIGH | N1 OTA-breaks-FGS needs post-OTA self-test |
| O. App-specific deep-dive | 9 apps | CRITICAL | Roblox + Discord = primary leak channels |

Behavioral (kid red team):
| Category | Largest gap | Defense type |
|---|---|---|
| Social-engineer parent | Co-parent invisibility | Technical: cross-device co-parent feed |
| Other-device workarounds | School Chromebook, friend's phone, TV/console | **Cannot defend** (parent strategy) |
| Trojan-horse apps | Roblox/Discord as social networks | Technical: app categorization + DNS filter |
| Time manipulation | Sneak after bedtime | Technical: hard bedtime lock w/o PIN unlock |
| Lying / second account | School Google account on YouTube | Technical: pin managed account |
| Privacy invasion for leverage | Parent PIN shoulder-surf | Technical: biometric default + randomized keypad |
| Pure persistence | "Ask 10 times" | Procedural: cooldown timer on repeated requests |

---

## What V1 MUST defend against (derived from "actually succeeds" + behavioral top-5)

1. **In-app WebView leaks (D1).** Either blocklist Discord/Roblox/Snap by default OR ship DNS filter in v1 (originally v2) OR document loudly that "allowing X allows the whole web."
2. **Play Store web install (D8).** Install-approval flow MUST move from v2 → v1. Bitdefender-documented bypass otherwise.
3. **Signed-bundle replay (H1, C8).** Bundle must carry `policy_seq` + `not_before` + `not_after`. Child rejects regressions. **Not yet in scaffold.**
4. **Fail-closed everywhere (G2, H2, F3).** Sig parse error, missing policy file, clock anomaly, storage fail → strict baseline, never "unrestricted".
5. **Parent pubkey pinning at provisioning (H3).** Rotation requires recovery phrase. Already in scaffold; verify enforcement.
6. **Monotonic clock + signed parent timestamps (F3, F1, F2).** Don't trust wall clock even if `DISALLOW_CONFIG_DATE_TIME` works.
7. **Recovery-phrase use logged + delayed (K2, K3).** Any phrase use → audit-log entry; non-emergency requires 24h delay.
8. **Heartbeat + silence alarms (J1, N1).** Parent gets alerts at 15min / 1h / 6h / 24h offline.
9. **Restrictions audit on bench Pixel.** Each DISALLOW_* call: verify takes effect after boot, survives OTA. Trust no docs.
10. **Sealed-box envelope on event log (kid w/ root can't read).** Kid sees that events exist, not contents. See [`DEFENSES.md`](DEFENSES.md) Pattern B.

## What V1 CANNOT defend against (document, don't code)

1. **Friend's phone / school Chromebook / smart TV / game console** — different OS / different device. Parent strategy + family-conversation problem.
2. **Tantrum economics** — no code stops a parent giving in under social pressure.
3. **Sneaking parent's phone** — parent device hardening is parent's job; OpenWarden can only warn at onboarding.
4. **K1 (homework-needs-Discord lie)** — partial defense via cooldown + audit feed, but kid wins eventually.
5. **Kid uses phone at age 13+ socially defeating supervision** — outside age window; teen mode = v3 feature.

## New restrictions to ADD (not in current scaffold)

| Restriction | Closes |
|---|---|
| `DISALLOW_OEM_UNLOCK` | B1 defense-in-depth on Pixel 7 |
| `DISALLOW_USER_SWITCH` | E3 Guest user escape |
| `DISALLOW_REMOVE_USER` | belt-and-suspenders w/ ADD_USER |
| `DISALLOW_AIRPLANE_MODE` | J1 parent-sync isolation (harms genuine plane UX — flag) |
| `DISALLOW_MOUNT_PHYSICAL_MEDIA` | B6 SD-card paths (n/a on Pixel 7 but safe) |
| `DISALLOW_CONFIG_TETHERING` | lateral move via kid's hotspot |
| `DISALLOW_CONFIG_MOBILE_NETWORKS` | eSIM removal |
| `DISALLOW_APPS_CONTROL` | clear-data / force-stop / disable any DO-managed app |
| `DISALLOW_CONFIG_LOCATION` + `DISALLOW_CONFIG_BLUETOOTH` | adjacent escapes |
| Force `setGlobalPrivateDnsMode(OpenWarden-resolver)` | C2 DoH-in-browser (pull DNS filter to v1) |

## Restriction audit — what's blocked by what

| Hypothetical bypass | Blocked by |
|---|---|
| Settings → Reset → Erase all data | `DISALLOW_FACTORY_RESET` + FRP |
| Safe mode → disable DPC → uninstall | `DISALLOW_SAFE_BOOT` |
| Dev Opts → USB debug → `dpm remove-active-admin` | `DISALLOW_DEBUGGING_FEATURES` + `ADB_ENABLED=0` write at provisioning |
| Sideload free VPN APK | `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` |
| Configure custom VPN to escape DNS filter | `DISALLOW_CONFIG_VPN` + pinned `setAlwaysOnVpnPackage(OpenWarden)` |
| Add secondary Google account | `DISALLOW_MODIFY_ACCOUNTS` |
| Create second user profile | `DISALLOW_ADD_USER` + `DISALLOW_USER_SWITCH` |
| Pull APKs via MTP / push tools | `DISALLOW_USB_FILE_TRANSFER` |
| Clock rollback / timezone trick | `DISALLOW_CONFIG_DATE_TIME` + monotonic-clock policy eval |
| NFC-bump new DPC config | `DISALLOW_OUTGOING_BEAM` + `device_provisioned=1` |
| `fastboot flashing unlock` | bootloader locked + `DISALLOW_OEM_UNLOCK` |
| Fastboot wipe → fresh setup w/ new account | FRP locked to parent's Google account |
| Force-stop OpenWarden app | DO + `setUserControlDisabled(self, true)` (Android 14+) |
| Clear OpenWarden app data | DO + `DISALLOW_APPS_CONTROL` |

See [`DEFENSES.md`](DEFENSES.md) for the mapping attack → defense and the 15-defense v1 ship list.
