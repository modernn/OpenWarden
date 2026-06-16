# Changelog

All notable changes to OpenWarden are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Pre-1.0 — expect churn.

## [Unreleased]

### Added
- `parent-kmp/` scaffold — Kotlin Multiplatform parent app: `:proto` (shared wire
  types), `:shared` (crypto/sync/policy/state), `:androidApp` (Compose Material 3).
  Builds + tests green on the Android/JVM targets; iOS targets and SKIE are
  host-gated to macOS (ADR-002, ADR-011, `docs/PARENT_KMP_STRUCTURE.md`).
- Committed Gradle 8.11 wrapper and version catalog (`parent-kmp/gradle/libs.versions.toml`).
- `Canonical` (`:proto`) — RFC 8785 (JCS) canonicalizer, the single signing input
  (ADR-015), integers-only with the 0..2^53−1 guard (ADR-017). Host-tested.
- `PolicySigner` / `Identity` (`:shared`) — JCS signing-input bytes + Ed25519
  sign/verify via libsodium. Pure signing-input tests run on the host; the
  libsodium round-trip runs on-device / CI (native lib not on the desktop JVM).
- ADRs 013–017 (Proposed) resolving Phase-0 red-team findings — see
  `docs/research/07-redteam-design-review.md`.
- Doc-freshness automation: Stop + PostToolUse hooks in `.claude/settings.json`.

### Changed
- `scripts/bootstrap.sh`: install ktlint from its GitHub release (the
  `Pinterest.Ktlint` winget id does not exist).
- Project skills converted to the `skills/<name>/SKILL.md` layout so they load.
- ROADMAP parent-app section corrected from Flutter to KMP (post-ADR-011).

### Notes
- `parent-kmp` ↔ `child-android` composite Gradle build is deferred (version skew;
  `child-android` does not yet consume the shared `:proto`).
- iOS is developed later on a macOS clone (Android-first); the Xcode project is
  not committed from Windows.
