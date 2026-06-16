---
name: bootstrap-repo
description: First-time OpenWarden environment setup. Triggers on user saying "start setting up", "bootstrap the repo", "set up dev environment", "install dev tools". Installs JDK 21, Android SDK + emulator, adb, ktlint, detekt, optional Codex CLI. Idempotent.
---

# /bootstrap-repo

Sets up the OpenWarden development environment on a fresh machine.

## When to trigger

- User opens a fresh clone of the OpenWarden repo
- User says "start setting up" / "bootstrap" / "install dev tools" / "set up the repo" / similar
- User invokes `/bootstrap-repo` directly

## What it does

1. Reads [`BOOTSTRAP.md`](../../BOOTSTRAP.md) for current install matrix
2. Reads [`docs/PARENT_KMP_STRUCTURE.md`](../../docs/PARENT_KMP_STRUCTURE.md) §13 for pinned tool versions
3. Runs `./scripts/bootstrap.sh`
4. Runs `./scripts/verify-env.sh` to confirm
5. Reports status + any remediation steps
6. If everything green, asks user: "Ready to scaffold parent-kmp (Phase 1)?"

## What it INSTALLS

Required:
- JDK 21 LTS
- Android SDK + platform-tools (adb, fastboot, emulator)
- Android system image (Pixel 7 API 35)
- AVD named `openwarden-pixel7-api35`

Optional:
- gh CLI
- jq
- ktlint (Kotlin lint)
- swiftlint (macOS only, iOS work)
- Codex CLI (`@openai/codex` via npm) — for `codex:rescue` skill

## What it does NOT install

- Xcode (macOS App Store, manual)
- Apple Developer Program enrollment (paid, manual)
- Real device drivers (parent provides for bench-Pixel testing)

## Failure handling

On install failure for a required tool: surface specific remediation step from script output. Don't proceed to next step until previous resolved.

## Sample invocations

```
/bootstrap-repo
"start setting up the repo"
"install the dev tools"
"bootstrap"
```

## After bootstrap success

Suggest next step: "Run `/scaffold-parent-kmp` to begin Phase 1." (skill TBD).
