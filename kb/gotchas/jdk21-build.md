---
id: jdk21-build
title: Build needs JDK 21 even though the default `java` may be 17
type: gotcha
tags: [build, toolchain, jdk, gradle]
status: active
created: 2026-06-15
updated: 2026-06-15
expires: 2026-12-31
source_pr: null
---

# Build needs JDK 21, default `java` may be 17

OpenWarden pins **JDK 21 LTS** (Eclipse Temurin or equivalent) for reproducible builds, but
many dev machines have an older default `java` on `PATH` (commonly 17). If Gradle picks up
17, the build can fail or silently produce non-reproducible output.

## What to do
- Install JDK 21 (the `/bootstrap-repo` skill does this) and make sure Gradle uses it
  (`JAVA_HOME` / Gradle toolchain), not whatever `java -version` happens to print.
- `./scripts/verify-env.sh` checks for JDK 21 — run it if a build acts strange.

## Source / citations
- `BOOTSTRAP.md` — "JDK 21 LTS … pinned for reproducible builds".
- `scripts/verify-env.sh` — enforces JDK version 21.

Note: `AGENTS.md` shows an example build line annotated "all green on JDK 17". That is about
what the modules *can* compile against, not the pinned toolchain. When in doubt, use 21 and
trust `verify-env.sh`.
