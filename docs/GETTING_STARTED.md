# Getting started (run OpenWarden locally)

OpenWarden is developed **Android-first**. The child DPC (`child-android/`) and the
parent app's Android target (`parent-kmp/`) build on Windows, macOS, and Linux.
The iOS parent target is built later on a Mac (it's host-gated off elsewhere).

> New and using an AI agent? Read [`AGENTS.md`](../AGENTS.md) first, then run the
> **`/openwarden`** skill (Claude Code) — it asks what you want to
> do and walks you through it.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | Build verified on JDK 17. (JDK 21 is used for the F-Droid reproducible release pipeline.) |
| Android SDK | platform 35, build-tools 35 | `google_apis` system image (see emulator note) |
| adb / emulator | current | from the Android SDK |
| git | 2.x | worktrees used heavily |
| gh (optional) | current | for issues/PRs |

One-shot setup + check (installs JDK, SDK, the Pixel-7 API-35 AVD, ktlint, etc.):
```bash
./scripts/bootstrap.sh
./scripts/verify-env.sh     # expect all green
```

## 2. Clone (with a worktree per task)

```bash
git clone https://github.com/modernn/OpenWarden.git
cd OpenWarden
# Each task / agent window gets its own worktree + branch (see CLAUDE.md → Git worktrees):
git worktree add -b feat/my-thing ../OpenWarden-my-thing main
cd ../OpenWarden-my-thing
```

## 3. Build the parent app (Android)

```bash
cd parent-kmp
# Point Gradle at your SDK (not committed):
echo "sdk.dir=$ANDROID_HOME" > local.properties      # or the absolute SDK path
./gradlew :proto:build :shared:build :androidApp:assembleDebug
```
Always use the committed wrapper (`./gradlew`), never a global Gradle — it pins the
version. APK lands at `androidApp/build/outputs/apk/debug/`.

## 4. Tests

```bash
cd parent-kmp
./gradlew :proto:allTests :shared:allTests        # fast host (JVM) tests
./gradlew check                                   # umbrella: build + tests + lint
```
The **libsodium crypto round-trip** runs on a device/emulator
(`./gradlew :shared:connectedAndroidTest`), not the desktop JVM — the native lib
isn't on the host. Pure logic (JCS signing input, policy model) is host-tested.

## 5. The child DPC (`child-android/`)

Open `child-android/` in Android Studio (it provides Gradle) and run on a **dev
device or emulator — never a real kid's phone**.

### Provision as Device Owner (emulator)
The DPC needs a **non-Play `google_apis` image (NOT `google_apis_playstore`)** and a
device with **zero accounts** — `dpm set-device-owner` refuses otherwise, and Device
Owner can't be removed via `dpm` (wipe to reset).
```bash
emulator @openwarden-pixel7-api35 -no-snapshot -wipe-data &
adb wait-for-device
./scripts/provision-emulator.sh        # sets device-owner, verifies
```
Full flow: [`PROVISIONING_V2.md`](PROVISIONING_V2.md). E2E: `./scripts/test-emulator.sh`.

## 6. Worktree hygiene
- One agent window = one worktree folder = one branch. Don't run two windows in the
  same folder on different branches.
- `git worktree list` at session start; commit/push or clean stray work; `git worktree prune`.
- `build/`, `.gradle/`, and `local.properties` are not shared between worktrees and
  are git-ignored.

## Next
- How to contribute: [`../CONTRIBUTING.md`](../CONTRIBUTING.md)
- Pick a task: [issues](https://github.com/modernn/OpenWarden/issues) (`good first issue`, `agent-ready`)
- The rules: [`../CLAUDE.md`](../CLAUDE.md) / [`../AGENTS.md`](../AGENTS.md)
