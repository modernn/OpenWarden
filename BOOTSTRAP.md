# Bootstrap

First-time setup of an OpenWarden development environment.

## Quick start

```bash
./scripts/bootstrap.sh
```

This script is idempotent. Run it any time you want to verify your environment is healthy.

## What it installs / verifies

Required:
- **JDK 21 LTS** (Eclipse Temurin or equivalent) — pinned for reproducible builds
- **Android SDK + platform-tools** — adb, fastboot, emulator
- **Android system image** — `system-images;android-35;google_apis;x86_64` (Pixel 7 target)
- **AVD** — `openwarden-pixel7-api35` pre-configured
- **Gradle wrapper** — version pinned in `gradle/wrapper/gradle-wrapper.properties`

Optional (used by hooks + CI):
- **ktlint** — Kotlin lint
- **detekt** — Kotlin static analysis
- **swiftlint** — Swift lint (iOS)
- **gh** — GitHub CLI
- **codex** — OpenAI Codex CLI (for `/codex:rescue` skill)
- **jq** — JSON tooling

## Manual prereqs

Bootstrap will check these but cannot install them automatically:

- **Xcode 16+** (macOS only, iOS parent app builds) — install via Mac App Store
- **Apple Developer account** — for TestFlight distribution (defer until first iOS release)

## Configuration

Bootstrap reads `.bootstrap.env` if present for overrides:

```bash
JDK_VERSION=21
ANDROID_API_LEVEL=35
SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
AVD_NAME="openwarden-pixel7-api35"
KOTLIN_VERSION=2.1.0
AGP_VERSION=8.7.3
```

## Verifying

After bootstrap, run:

```bash
./scripts/verify-env.sh
```

Exits 0 if everything is set up, non-zero w/ specific failures if not.

## Tooling versions

See [`gradle/libs.versions.toml`](gradle/libs.versions.toml) once Phase 1 scaffolds it. Until then, [`docs/PARENT_KMP_STRUCTURE.md`](docs/PARENT_KMP_STRUCTURE.md) §13 has the planned pinned matrix.

## Pre-Phase-1 state

Right now (Phase 0) the repo is docs + Claude Code config only. No Gradle build yet. Bootstrap will install OS-level prereqs (JDK, Android SDK) but skip Gradle steps until Phase 1 scaffolds the build.

## CLAUDE.md instructions

When you open a Claude Code session in this repo and say "start setting up" or "bootstrap repo":

1. Claude will read this file
2. Run `./scripts/bootstrap.sh`
3. Report success / failures + remediation steps
4. Confirm ready for Phase 1 scaffolding

If you say "scaffold parent-kmp" (Phase 1), Claude will read [`docs/PARENT_KMP_STRUCTURE.md`](docs/PARENT_KMP_STRUCTURE.md) and execute the layout there.
