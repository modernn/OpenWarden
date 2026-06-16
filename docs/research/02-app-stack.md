# OpenWarden App Research — Deep Dive on Apps (Android child DPC + Android/iOS parent)

Companion to `openwarden-research.md`. That doc covered the *problem space* (DPC pitfalls, bypass techniques, Tailscale realities). This one covers the *app stack itself*: existing OSS projects worth borrowing from, the cross-platform parent-app decision, the iOS-without-server reality, store-and-forward libraries, on-device AI, and concrete v1 feature lists. Where the prior doc said "X is a thing," this one says "use Y because Z."

---

## 1. Existing FOSS pair-app projects — what's actually shippable

The honest answer: there is no production OSS parental-control project with a Device-Owner DPC child agent and a polished cross-platform parent app. There are reference DPCs, half-baked clones of Family Link, and adjacent OSS (Headwind MDM for enterprise, Open TimeLimit for screen-time-only). OpenWarden fills a real gap.

Triaged list, only ones worth a borrow:

| Project | License | Stack | Last meaningful commit | Verdict |
|---|---|---|---|---|
| [googlesamples/android-testdpc](https://github.com/googlesamples/android-testdpc) | Apache 2.0 | Kotlin/Java DPC reference | active, Google-maintained | **Borrow heavily.** Canonical DevicePolicyManager call patterns. Lift `PolicyComplianceActivity`, `EnrollmentTokensProvider`, NFC provisioning code. License-compatible. |
| [h-mdm/hmdm-android](https://github.com/h-mdm/hmdm-android) | Apache 2.0 | Java DO launcher | active | **Borrow surgically.** Production-tested launcher + admin-receiver + COSU patterns. Skip server hooks. |
| [Open TimeLimit](https://codeberg.org/timelimit/opentimelimit-android) | GPL-3.0 (problem) | Kotlin DA (not DO) | quiet 2024–2025 | **Study, don't link.** GPL-3 contaminates Apache 2.0; can't lift code, can read for design. Uses `UsageStatsManager` polling + overlay window — the *Accessibility-service-free* time-limit pattern is worth copying conceptually. Their core insight: "DeviceAdmin + draw-over-other-apps + UsageStats" gets you 80% of screen-time without DO. ([F-Droid listing](https://f-droid.org/en/packages/io.timelimit.android.open/)) |
| [TimeLimit.io aosp_direct](https://f-droid.org/en/packages/io.timelimit.android.aosp.direct/) | GPL-3.0 | Kotlin + server | abandoned | Same code base; networking re-added. Skip. |
| [childscreentime/cst](https://github.com/childscreentime/cst) | MIT | Kotlin, single-device, FGS + DA | active 2025–2026 | **Study UX, don't link.** No DO, no parent device — it's a *self-control* app for a kid's own phone. Useful for the FGS+WorkManager survival patterns. MIT compatible if you do borrow. |
| [xMansour/KidSafe](https://github.com/xMansour/KidSafe) | MIT | Java/Kotlin + Firebase | quiet | Design reference only. Firebase-coupled. Solo-dev demo grade. |
| [w3ggy/ParentControl](https://github.com/w3ggy/ParentControl) | MIT | Java | stalled, ~2022 | Skip. |
| [gihankarunarathne/Parental-Controller-Android](https://github.com/gihankarunarathne/Parental-Controller-Android) | none stated | Java, server-coupled | stalled | Skip. |
| [ettet1000/protectchild-important](https://github.com/ettet1000/protectchild-important) | none stated | Java, sends data to server | dead | Skip — license unclear, server-dependent, not the model. |
| [ScreenGuard-ML](https://github.com/ViratSrivastava/ScreenGuard-ML) | MIT | Python+ML demo | demo-grade | Skip. |
| [ScreenBreak](https://github.com/christianp-622/ScreenBreak) | unclear | Swift, iOS 16 Screen Time API | demo | **iOS reference only** — shows FamilyControls + DeviceActivity + ManagedSettings patterns. Not for kid-device (OpenWarden targets Android kid) but useful if a v3 iOS kid app ever happens. |
| **Linux** Timekpr-nExT | GPL-3 | desktop | active | Wrong platform; same problem domain. Their per-day/per-week budget UX is well-thought-out — read [their docs](https://launchpad.net/timekpr-next) and copy the *user model*. |
| **TimeAway** | — | — | — | No actual project found; "TimeAway" is a commercial product name. Skip. |
| **Cosmos** | — | — | — | No specific OSS project. Skip. |
| **Pinwheel-clone** | — | — | — | No OSS clone of Pinwheel exists. The product is closed by design. |
| **Catima-style kid launcher** | — | — | — | [Catima](https://github.com/CatimaLoyalty/Android) is loyalty cards — different domain. There's no notable OSS kid-safe launcher beyond Headwind MDM's launcher. |
| **Guardian Project / CalyxOS** | — | — | — | Guardian Project ships [Orbot, ObscuraCam, Haven](https://guardianproject.info/apps/) — none are parental controls. CalyxOS has no parental-control story. Don't expect upstream help. |
| **Tor Project** | — | — | — | No parental tooling. Wrong scope. |

**Net take:** lift TestDPC for the DPC plumbing; lift Headwind for the launcher patterns; treat Open TimeLimit as architectural inspiration but not as code source (GPL contagion). The cross-platform parent app has no real OSS prior art — OpenWarden is the first.

**GitHub Topic to watch:** [`parental-control`](https://github.com/topics/parental-control), [`screen-time`](https://github.com/topics/screen-time), [`device-owner`](https://github.com/topics/device-owner).

---

## 2. Cross-platform parent app — pick KMP, not Flutter

The existing roadmap assumes Flutter. Challenge that. Summary of evaluation:

| Stack | Crypto (libsodium / NaCl box) | iOS bg sync | BLE / NFC | Native feel | OSS purity | F-Droid build | TestFlight friction |
|---|---|---|---|---|---|---|---|
| **Flutter** | [`flutter_sodium`](https://github.com/firstfloorsoftware/flutter_sodium) abandoned 2022; [`cryptography_flutter`](https://pub.dev/packages/cryptography_flutter) is fine for ChaCha20+X25519 but not full libsodium parity | Possible via `workmanager` + BGTaskScheduler bridge; finicky | `flutter_blue_plus`, `flutter_nfc_kit` — fine | Material 3 acceptable, iOS feel is slightly uncanny-valley | OK; Dart toolchain is open, but Skia/Impeller is Google-controlled | Yes, with `flutter build apk` reproducibility | Standard |
| **React Native** | `react-native-sodium` exists but moves slowly; Hermes JS adds risk surface | Headless JS on Android works; iOS BGTaskScheduler bridge via third-party | Fine | Good with Expo & native modules | Decent | Possible but harder than Flutter | Standard |
| **Kotlin Multiplatform (KMP) + Compose Multiplatform for Android / SwiftUI for iOS** | [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium) — first-class, actively maintained | Best in class: shared `SyncManager` interface, platform-specific `WorkManager` on Android, `BGTaskScheduler` on iOS, both calling shared Kotlin logic ([guide](https://medium.com/@ignatiah.x/background-sync-in-kotlin-multiplatform-workmanager-android-background-tasks-iosx-1f92ad56d84b)) | Native APIs directly | **Native on each platform** — best feel | Pure OSS toolchain | Yes, Kotlin/JVM build is trivially reproducible | Standard |
| Native twice (Kotlin + Swift) | Best, but duplicated | Best | Native | Best | Pure | Yes | Standard |
| PWA / Capacitor | Web Crypto only, no NaCl box parity | Awful on iOS (Safari background = none) | Web Bluetooth limited, no NFC on iOS web | Web feel | OK | Probably not | N/A |
| Tauri Mobile | Crypto via Rust — great in principle | Immature; iOS bg sync barely documented | Plugin ecosystem thin | Mid | Pure | Maybe | Painful |

**Recommendation: switch to Kotlin Multiplatform (KMP) with Compose Multiplatform on Android and SwiftUI on iOS.** Rationale:

1. **The hard parts are native.** APNs, BGProcessingTask, BLE pairing, NFC, Keychain — all are 1:1 platform APIs. Flutter's "platform channels" reimpose the boundary you escaped Flutter to cross.
2. **The shared parts are pure logic.** Log replication, signed-bundle verification, policy CRDT, X25519 box, Ed25519 sig — all are protocol code with no UI. KMP shares 60–80% of that as actual Kotlin.
3. **libsodium parity is solved.** `kotlin-multiplatform-libsodium` is a JNI/Native binding that actually works on both platforms with bit-identical output. Flutter's libsodium story has been stagnant for years.
4. **F-Droid friendly.** Android target is plain Kotlin/Gradle → reproducible builds are straightforward. Compose Multiplatform's Android side is just Compose. No Skia engine dependency to grief over.
5. **TestFlight / sideload story.** iOS target is real SwiftUI/Xcode — submit to TestFlight normally. Same Xcode signing as a native app.
6. **DPC reuse.** The child DPC is already Kotlin. Sharing protocol code with the parent is free in KMP — `:proto` becomes a Kotlin module imported by both.

**Cost of switching from Flutter (current scaffolding):** the `parent-flutter/` directory has `pubspec.yaml` and a `lib/` skeleton — minimal sunk cost. Replace with `parent-kmp/` containing `:shared` (Kotlin), `:androidApp` (Compose), `:iosApp` (Xcode + SwiftUI). Reference: [JetBrains' 2026 KMP default structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/).

**If you stay with Flutter** (acceptable second choice): use `cryptography_flutter` for X25519+Ed25519, accept that you'll write Swift/Kotlin for BLE, NFC, and BGTaskScheduler anyway, and budget for the iOS UI feeling "off." Time-to-MVP is similar between KMP and Flutter; KMP wins on the 6-month maintenance horizon.

**Reject** React Native, PWA, Tauri Mobile for this project.

---

## 3. iOS parent app — what's possible with no server

This is where naive "no SaaS" hits Apple reality. Facts as of 2026:

- **Silent push (`content-available`) requires an APNs sender.** APNs requires p8 key + your sender backend to reach Apple's gateway. There is *no* way to publish to APNs from peer-to-peer code. Apple's design intent.
- **UnifiedPush / ntfy do not solve iOS.** ntfy has an iOS client, but it works by — guess — proxying to APNs via ntfy.sh's hosted infra ([ntfy docs](https://docs.ntfy.sh/subscribe/phone/#ios)). Self-hosting ntfy gets you Android UnifiedPush; iOS subs still travel via ntfy.sh's APNs cert. So "self-hosted push for iOS" is a contradiction at the Apple layer.
- **BGAppRefreshTask:** 30 sec budget, system-scheduled, fires only when iOS feels like it. In practice: once every few hours when the app sees regular use; days when it doesn't. ([Apple BackgroundTasks](https://developer.apple.com/documentation/backgroundtasks))
- **BGProcessingTask:** longer budget but requires AC power + WiFi typically. Useful for nightly log compaction. Useless for "Oliver needs 15 more min RIGHT NOW."
- **NSURLSession background:** survives termination but only for HTTP transfers you initiated — not a polling mechanism.

**Three viable iOS parent paths, ranked:**

### Path A (recommended): Hybrid — direct sync when foregrounded, ntfy.sh push for wake-up alerts

- Use ntfy.sh's free tier as a *wake-up doorbell only*. The push payload contains no policy content — it's an opaque "ping at $timestamp" instructing the parent app to open and check the store-and-forward log via WireGuard/Tailscale/LAN.
- Self-host the *content*. ntfy.sh sees only "wake up" pings. Documented in your privacy doc.
- ntfy.sh is OSS, free, run by Philipp Heckel, and you can later self-host the wake-up tier with a $5 VPS once the project is past v3.
- Parent app reads the store-and-forward log on wake-up, surfaces local notification via `UNUserNotificationCenter`.
- Acknowledge in `FUNDING.md` and `SECURITY.md`: "iOS push uses ntfy.sh as a wake-up relay; no content traverses it; verifiable in source."

### Path B: Foreground-only iOS

- No push. Parent must open the app to see Oliver's pending requests.
- For a single dad who checks his phone hourly, this is honestly fine. v1.
- Use BGAppRefreshTask opportunistically — when it fires, sync the log, post local notifications for any new alerts.
- Document the latency: "iOS alerts may take up to 6 hours to surface unless you open OpenWarden."
- Pro: zero external dependency. Con: not great UX.

### Path C: Self-host the relay properly

- Stand up your own APNs sender (requires Apple Developer Program p8 key — $99/yr, single SaaS-ish thing you can't escape).
- Run a tiny "doorbell" relay on a VPS or home Pi.
- Parent's iPhone registers device token with your relay during pairing.
- Best UX, most setup burden, conflicts mildly with "no SaaS" — but the relay is *yours* and *OpenWarden-specific*.
- Optional for users who want the full experience; not a default.

**Final recommendation:** Ship **Path B for v1** (iOS opens-to-see), document **Path A** as the v2 path (ntfy.sh as wake-up doorbell, content over your own transport), leave **Path C** as a "power-user self-hosted" v3 option.

**Apple Family Controls framework:** irrelevant. Family Controls authorizes apps to provide parental controls *for the device they run on*. OpenWarden's iOS app is a *parent's* device controlling a *separate Android kid device* — Family Controls has zero role. ([Family Controls docs](https://developer.apple.com/documentation/familycontrols))

**App Store policy:** parental control apps are explicitly allowed when marketed for child safety with parental oversight. The risk vector is stalkerware accusations; OpenWarden's design avoids that (control, not surveillance; kid sees what's monitored). **TestFlight is the right v1 distribution channel** for the iOS parent app — 90-day external testing windows, 10k user cap, no App Store review pain for a 1-user beta. Move to App Store for v2 once polished.

**Sources:** [Silent push reliability](https://mohsinkhan845.medium.com/silent-push-notifications-in-ios-opportunities-not-guarantees-2f18f645b5d5), [BG execution limits](https://www.appsonair.com/blogs/background-execution-limits-in-ios-what-every-developer-must-know), [UnifiedPush iOS reality](https://unifiedpush.org/users/distributors/).

---

## 4. Pair-app UX patterns to copy

Specific moves from production apps:

- **Briar (QR pair):** mutual QR scan, both screens show "scan the other phone's code." Lesson: [user research](https://code.briarproject.org/goapunk/briar-repeater/-/issues/2) found *only 3 of 11* completed without help. **Fix:** scanner viewfinder must overlay a clear "point at your kid's phone" instruction; show a confirmation-with-photo screen ("This is Oliver's phone? [Yes / No]"). Pre-launch usability test with a non-dev friend.
- **Signal safety numbers:** [Signal moved from two-scan to one-scan](https://signal.org/blog/safety-number-updates/). Copy: one QR contains both parties' pubkeys via [Diffie–Hellman-during-pairing](https://signal.org/docs/specifications/x3dh/); render as a single image. *Verification* (post-pair) uses a separate "compare these 12 emojis" screen — copy that, too.
- **Element/Matrix verification:** [cross-signing emoji compare](https://element.io/blog/verifying-your-devices-is-becoming-mandatory-2/) (turtle, cake, etc.). For OpenWarden: after first pair, show six emojis on both screens — parent confirms they match. Defense against active-MITM during pairing.
- **Tailscale tagged tailnet UX:** parent sees child as `oliver-pixel7` (tag-named, not IP); transport details hidden behind one screen. Copy: surface "child online / offline / last seen 4 min ago" and only expose IP / transport mode in a debug panel.
- **Jami account creation:** painless because no email; identity = key fingerprint. Copy: no email, no phone number. Account = generated keypair + the local "Larson" / "Oliver" labels.
- **Obtainium F-Droid app management:** import-by-URL paradigm. Useful nothing for pairing, but for child app *installation* — parent picks "add new app" → enters Play Store URL or APK URL → request goes to child for install approval (v2 install-flow feature).
- **Anytype local-first sync UX:** shows sync state per device with a small badge ("syncing 3 items"). Copy directly into OpenWarden parent app's child-status pill.

**The pairing flow OpenWarden should ship for v1:**
1. Both apps generate identity keys on first launch.
2. Parent opens "Add child" → shows QR containing parent pubkey + chosen transport mode.
3. Child app on Pixel scans QR.
4. Child app generates *its* QR back, shows on screen.
5. Parent scans child's QR.
6. Both screens display six emojis from `HKDF(parentPub || childPub)`. Parent confirms they match.
7. First signed policy bundle flows; child applies it.

Total taps: ~6. Total time: ~90 seconds. Matches Briar's actual time, beats Family Link's 15-minute Google-account dance.

---

## 5. Store-and-forward library landscape

You're writing the protocol (per `STORE_AND_FORWARD.md`). The question: which existing library to embed for transport / replication primitives?

| Library | License | Mobile fit | Verdict |
|---|---|---|---|
| **[Iroh](https://github.com/n0-computer/iroh)** v1.0+ | Apache-2.0 / MIT | First-class Kotlin + Swift FFI as of 2026 ([roadmap](https://github.com/n0-computer/iroh/discussions/517)); [BLE transport in development](https://github.com/mcginty/iroh-ble-transport) | **Top pick.** Iroh gives you QUIC + hole-punching + relay-fallback + content-addressed sync (`iroh-blobs`) without you reinventing it. License-compatible. Use as the "anywhere" transport layer beneath your log abstraction. |
| **Hypercore / Holepunch / Keet** | Apache 2.0 (mostly) | Node-only; React Native bridges exist but are heavy | Skip. Wrong runtime for KMP/native. |
| **[Earthstar](https://github.com/earthstar-project/earthstar)** | LGPL-3.0 / BSD-3 | TypeScript-only | Skip on KMP. Read the spec; the share/replica/document model is exactly what you want, but the impl is in the wrong language. |
| **Automerge / Yjs (CRDTs)** | MIT / various | Yjs has Kotlin port; Automerge has Rust core | Skip — you don't want CRDT semantics for *policy bundles*. Append-only signed log is correct. Reserve CRDTs for a future "shared notes" feature if ever. |
| **[libp2p](https://github.com/libp2p)** (Go reference, or [jvm-libp2p](https://github.com/libp2p/jvm-libp2p)) | Apache-2.0 / MIT | jvm-libp2p compiles on Android but is unproven in production; Go-libp2p via gomobile is heavy (~10MB binary) | Skip for v1. Iroh covers the same niche cleaner. If Iroh's relay infra ever feels too centralized, revisit. |
| **Briar SDK** | GPL-3.0 | Briar is GPL; embedding kills your Apache 2.0 license | Skip — license-incompatible. Read the Bramble protocol [paper](https://code.briarproject.org/briar/briar-spec) for *design ideas only*. |
| **[Veilid](https://veilid.com/)** | MPL-2.0 | Android + iOS supported | **Watch, not embed yet.** Veilid's DHT + private routing is exactly the right primitive for *anonymous* relay, but the project is still under-tested in production. MPL-2.0 is compatible-by-file with Apache 2.0 (file-level copyleft), works for embedding as a separate module. Re-evaluate at v3. |
| **delta-chat-core** | MPL-2.0 | C library with Android/iOS bindings | Worth considering as one *specific transport* (the IMAP/SMTP transport in your matrix), not the whole comms layer. Skip for v1; useful for "Mom and Dad both have Gmail" v3 fallback transport. |
| **nostr** | various | Trivial protocol, easy to embed | **Avoid for kid context.** Nostr is relay-public-by-default. Any relay sees all events to any pubkey it's asked about. Even with NIP-44 encryption, traffic-analysis on a kid's pubkey is a child-safety risk. Skip. |

**Recommendation:** for v2 store-and-forward, **embed Iroh** as the "internet transport" (replaces direct REST). Keep `mDNS+REST` as the LAN transport. Build your *own* thin BLE transport for v3. The signed-log layer lives above transport, in your own Kotlin code in `:shared`.

---

## 6. On-device AI — Android child

Building on `LOCAL_AI.md`. Concrete model table for 2026:

| Model | License | Size | Use | Pixel 7 perf | Verdict |
|---|---|---|---|---|---|
| **Gemma Nano (via [AICore](https://developer.android.com/ai/gemini-nano))** | Gemma Terms (permissive, OSS-friendly) | ~1.5–3GB managed by AICore | Text classification, summary | <500ms / 200tok via NPU on Pixel 7+ | **v3 primary** for text. Free, native, battery-friendly. Requires Pixel 7+ or AICore-supported device. |
| **Gemma 4 E2B/E4B** ([AICore Developer Preview](https://android-developers.googleblog.com/2026/04/AI-Core-Developer-Preview.html)) | Gemma Terms | 2–4B params | Multimodal text+image+audio | Newer, faster | **Watch — v4 candidate** once stable. Multimodal solves both "is this image NSFW" and "is this message bullying" in one model. |
| **[Falconsai/nsfw_image_detection](https://huggingface.co/Falconsai/nsfw_image_detection)** (ViT-based) | Apache 2.0 | ~340MB | Image NSFW classify | ~150ms on Pixel 7 via TFLite-converted | **v2 primary** for image NSFW. Apache, accurate, embeddable. |
| **[NudeNet v3](https://github.com/notAI-tech/NudeNet)** (ONNX) | GPL-3.0 → check | 320n: ~7MB, 640m: ~25MB | Granular body-part detect | <100ms (320n) | License is murky (NudeNet author moved to ONNX, [version 3](https://github.com/notAI-tech/NudeNet/blob/v3/LICENSE) — verify before shipping). Lighter than Falconsai. Use as *secondary confirm*. |
| **GantMan/nsfw_model** | MIT (model), code GPL-2.0 | ~30MB MobileNetV2 | NSFW classify | <80ms | MIT model weights are usable; reference impl is GPL. Re-train or use ONNX export. |
| **Yahoo OpenNSFW2** | BSD-3 | ~25MB | NSFW classify | <80ms | Old, less accurate than modern ViT. Fallback only. |
| **llama.cpp on Android** (Phi-3 mini, Qwen 0.5B) | MIT / Apache | 0.5B: ~1GB, 3.8B: ~5GB | General LLM | Qwen 0.5B ~5–8 tok/s on Pixel 7 CPU | Skip — AICore Gemma Nano is better integrated, lower battery. Use llama.cpp only on non-Pixel devices in v3. |
| **MediaPipe Tasks Image Classifier** ([docs](https://ai.google.dev/edge/mediapipe/solutions/vision/image_classifier)) | Apache 2.0 | tiny wrapper | Plumbing | n/a | Use as the *runtime* for whatever NSFW model you pick. |
| **ML Kit** | Closed but free | n/a | Plumbing | n/a | Skip — depends on Google Play Services. Your DPC may run on AOSP-only devices eventually. Use TFLite/MediaPipe directly. |
| **Speech-to-text** (Whisper-tiny on-device) | MIT (Whisper) | ~75MB | Future v3+ call-screen | Not needed for v1/v2 | Defer indefinitely. |

**Stack recommendation:**
- **v2 image classifier:** MediaPipe Tasks Image Classifier as runtime + Falconsai ViT (Apache 2.0) as primary model + NudeNet 320n as secondary if license clears.
- **v3 text classifier:** AICore Gemma Nano via [ML Kit GenAI Prompt API](https://developer.android.com/ai/ml-kit/genai-overview). Pixel 7+ only — acceptable since Pixel 7 is the target hardware.
- **v3 anomaly:** plain statistics on `UsageStatsManager` data; no ML library.
- **All on-device.** No content egress, ever. Event flags only.

---

## 7. OSS-parental-control pitfalls

- **F-Droid + DPC is awkward.** F-Droid prefers reproducible builds; that means dropping JNI binary blobs (libsodium native libs come from the [ionspin maven artifact](https://mvnrepository.com/artifact/com.ionspin.kotlin/multiplatform-crypto-libsodium-bindings-android)). Solution: compile libsodium from source in your build pipeline and commit the build script, not the .so. Likely two weekends of work; defer past v1.
- **Google's [Sept 2026 developer-verification policy](https://groundy.com/articles/keep-android-open-f-droid-s-fight-against-locked-down/)** changes sideload economics. After Sept 2026, sideloaded APKs on certified Android require a signed-developer registration; F-Droid is the OSS umbrella registering. **Plan to ship via F-Droid** by then or carry that registration yourself.
- **Sideload-by-parent friendliness:** `adb dpm set-device-owner` is one shell command. Ship a desktop GUI wrapper (one PyQt or Tauri-Mobile-Desktop tool, "OpenWarden Provisioner"): plug in Pixel via USB, click "Provision," done. Or stretch goal: NFC-bump provisioning via [QR-code-driven setup](https://developer.android.com/work/dpc/provisioning) using `PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME_EXTRA` payload.
- **Update strategy:** ship a `policy_bundle_format_version` field. Newer DPCs accept older bundles back to N–2. Parent app warns "child needs update" if it tries to send a too-new format. **Auto-update child via Play Store, not F-Droid**, for security latency reasons — F-Droid build lag is days to weeks; Play Store updates fast even for sideloaded apps if signed by the same key.
- **Audits:** Briar's audit cadence is ~3 years (independent, grant-funded). For OpenWarden v1 don't even try; for v2+, budget for one if grants land. Adopt [Signal's incident-disclosure pattern](https://signal.org/blog/) early — public CVE process, security@openwarden.dev email.
- **i18n:** *defer*. v1 is English-only. Build with `strings.xml` discipline so future translation is mechanical. Don't write copy in code.
- **Accessibility for young kids:** the kid-side of the DPC needs minimal text. Pictograms + colors for "Why am I blocked?": red lock for "time's up," yellow clock for "wait until 4pm," blue chat-bubble for "ask dad — tap to send." [UX-for-kids principles](https://www.ramotion.com/blog/ux-design-for-kids/): big targets, high contrast, no nested menus. Voice-readback is overkill for v1.
- **Age range:** the existing system aims at "Oliver's first phone" — empirically that's 8–13. **Pre-8** kids don't have phones (they share parent's). **Post-13** kids resent supervision and will socially defeat any technical control. **Design v1 for age 9–12.** That window matches the Pixel 7 ergonomics, the cognitive load of the kid-side UI, and the bypass-tech they'll attempt (Family Link bypasses [target this demographic](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)). Age 13+ deserves a different mode ("teen mode" — looser, more dialog-driven) as a v3 feature.

---

## 8. The Oliver reality check

Single-dad, single-kid, MVP-weekend constraint. The 10,000-family generalizations to *defer*: multi-tenant policies, role hierarchies (mom + dad + grandma + teacher), policy templates, marketplace of presets, enterprise SSO, GDPR data-export endpoints.

The leverage features for Oliver specifically:
1. **Phone reboots and just works.** No re-pairing, no permission re-grants. (Solved by DO from day one.)
2. **"Add 15 min" from your pocket without fumbling.** One screen, one button, signs+sends. Latency tolerance: 5–30 sec.
3. **Bedtime auto-lock.** Time-window enforcement local on child. Already in the plan.
4. **A `lock now` button** for when you walk into the room and Oliver's clearly on hour 3 of Roblox.
5. **A `recovery phrase printed and laminated`** in a drawer. The day Larson's phone falls in the lake, this saves Oliver's whole device from a wipe-and-restart.

Everything else (digests, anomaly detection, AI classifiers, geofence, install approval) is *nice but not load-bearing* for v1. Build these five and ship to Oliver. Iterate on what he actually breaks.

---

## 9. Concrete v1 child Android DPC features

| # | Feature | Why | Build cost | Maintain | Risk if omitted |
|---|---|---|---|---|---|
| 1 | DO provisioning + AdminReceiver + core 8 user restrictions | Foundation; without it everything else is bypassable | M | S | Total — no product |
| 2 | App suspend allowlist (`setPackagesSuspended`) with admin message | Primary control surface | S | S | Critical |
| 3 | Time-window enforcement per app, local + signed policy | The "bedtime/school-time" feature parents actually want | M | S | High |
| 4 | Boot-survival foreground service + `BOOT_COMPLETED` re-apply | Otherwise reboot = open device | M | S | Critical — reboot defeats everything |
| 5 | Signed policy bundle verifier (Ed25519 against pinned parent pubkey) | Trust root for all other features | S | S | Critical |
| 6 | LAN mDNS + REST sync (replace later with store-and-forward) | Lets parent push policy and see status | M | S | High; v1 is unusable without parent control |
| 7 | "Why am I blocked?" kid screen w/ pictograms | Reduces tantrums and teaches Oliver | S | S | Low (cosmetic) but high-leverage for adoption |
| 8 | "Ask dad" request button that sends signed event | Closes the loop without nagging in person | S | S | Medium — kids feel trapped without it |
| 9 | BIP39 recovery phrase generator + printable PDF | Recovery from parent-device-loss | S | S | Catastrophic if omitted — bricked phone |
| 10 | Lock-task mode for full-screen lock when parent says "now" | Beyond app-suspend, a full lockdown | M | S | Medium |
| 11 | FRP set to parent's Google account | Defeats factory-reset attack | S | S | High |
| 12 | Watchdog (AlarmManager every 15 min) re-launching FGS | Battery-saver kills | S | S | High (silent failure) |

**Cut from v1:** DNS filter, geofence, AI classifiers, time-bank, install-approval, daily digest. All v2+.

---

## 10. Concrete v1 parent app features (KMP, Android primary + iOS secondary)

| # | Feature | Why | Build | Maintain | Risk if omitted |
|---|---|---|---|---|---|
| 1 | Identity keygen + 24-word BIP39 mnemonic + forced confirm | Recovery + crypto root | S | S | Catastrophic |
| 2 | Pair-via-QR (two-scan + emoji confirm) | The only setup flow | M | S | Critical |
| 3 | Dashboard: child online status, today's usage, pending requests | The home screen parent looks at | M | S | Critical |
| 4 | App allowlist editor (pull installed apps from child, toggle) | The primary control UI | M | M | Critical |
| 5 | Signed policy bundle builder + send | Pushes changes | S | S | Critical |
| 6 | "Lock now" / "Unlock now" / "Add 15 min" big buttons | Pocket-friendly response to live events | S | S | High |
| 7 | Time-window scheduler UI per app | The "ban Roblox after 7pm" UI | M | S | High |
| 8 | Pending request feed (Oliver asked for more YouTube — approve/deny) | Closes the parent half of the ask-loop | S | S | High |
| 9 | LAN/mDNS transport with auto-retry; manual IP fallback | The actual networking | M | M | Critical |
| 10 | Local notifications on events (Android: native; iOS: BGAppRefreshTask poll + local notif) | iOS reality from §3 | M | M | iOS adoption rests on this; Android adoption needs it |
| 11 | Settings: backup/restore identity, export logs, factory-reset child (signed command) | Operations | S | S | Medium |
| 12 | Read-only "history" tab — last 30 days of events | Audit + sanity | S | S | Low for v1 |

**Cut from v1:** iOS APNs/ntfy push (defer to v2 Path A), desktop builds, transport-mode picker UI (LAN only first), CRDT'd shared notes, multi-child UI, daily-digest emails.

**Distribute via:** GitHub Releases (signed APK + iOS .ipa via TestFlight invite link). Move to F-Droid + App Store for v2 once stable. Pre-Sept-2026 Google policy change you can keep sideloading; after, you need F-Droid registration or own developer-verification.

---

## Cross-cutting recommendations

- **Switch parent app from Flutter to Kotlin Multiplatform.** Biggest single architectural decision. Cost is one weekend of scaffolding swap; payoff is years of native parity and a shared `:proto` module with the DPC.
- **Iroh is the v2 transport** beneath your signed-log layer. Drop Tailscale to "optional" status; Iroh is more aligned with the OSS / no-SaaS goal.
- **iOS parent v1 = no push, open-the-app model.** Document plainly. ntfy.sh wake-up doorbell in v2.
- **Borrow heavily from TestDPC + Headwind; quarantine GPL projects.** Open TimeLimit and Briar are read-only references.
- **Ship to Oliver in 8–12 weekends.** Resist generalization until lived experience exposes the actual gaps. Then write v2 around real friction, not imagined personas.

---

## Key new references (not already in `openwarden-research.md`)

- [Iroh 1.0 — Kotlin + Swift FFI](https://www.iroh.computer/blog/v1) and [mobile roadmap](https://github.com/n0-computer/iroh/discussions/517)
- [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium)
- [JetBrains 2026 KMP default structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/)
- [Background Sync in KMP — WorkManager + BGTaskScheduler](https://medium.com/@ignatiah.x/background-sync-in-kotlin-multiplatform-workmanager-android-background-tasks-iosx-1f92ad56d84b)
- [Compose Multiplatform what-works guide 2026](https://mzeus.medium.com/sharing-ui-across-android-and-ios-with-compose-multiplatform-what-actually-works-e3d9cd14609b)
- [Silent push reliability on iOS](https://mohsinkhan845.medium.com/silent-push-notifications-in-ios-opportunities-not-guarantees-2f18f645b5d5)
- [iOS background execution limits 2026](https://www.appsonair.com/blogs/background-execution-limits-in-ios-what-every-developer-must-know)
- [UnifiedPush distributors / ntfy iOS reality](https://unifiedpush.org/users/distributors/ntfy/)
- [Falconsai NSFW image detection](https://huggingface.co/Falconsai/nsfw_image_detection)
- [NudeNet v3](https://github.com/notAI-tech/NudeNet)
- [AICore + Gemma 4 Developer Preview](https://android-developers.googleblog.com/2026/04/AI-Core-Developer-Preview.html)
- [Open TimeLimit on Codeberg](https://codeberg.org/timelimit/opentimelimit-android)
- [childscreentime/cst](https://github.com/childscreentime/cst)
- [Headwind MDM device management deep-dive](https://deepwiki.com/h-mdm/hmdm-android/2.4-device-management)
- [Briar pairing UX study](https://code.briarproject.org/goapunk/briar-repeater/-/issues/2)
- [Element/Matrix mandatory verification 2026](https://element.io/blog/verifying-your-devices-is-becoming-mandatory-2/)
- [Signal safety-number single-scan flow](https://signal.org/blog/safety-number-updates/)
- [F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)
- [Google Sept 2026 developer verification](https://groundy.com/articles/keep-android-open-f-droid-s-fight-against-locked-down/)
- [Veilid](https://veilid.com/), [delta-chat-core](https://github.com/deltachat/deltachat-core-rust), [jvm-libp2p](https://github.com/libp2p/jvm-libp2p)
