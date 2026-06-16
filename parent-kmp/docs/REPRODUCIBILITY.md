# Reproducible builds (F-Droid)

Tracks the F-Droid main-repo requirements from
[`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) §8. Status: partial (scaffold).

## In place
- `org.gradle.parallel=false` in `gradle.properties` (deterministic R.jar ordering).
- Committed Gradle wrapper pinned to 8.11.
- All deps resolve from Maven Central / google() with stable versions (catalog in
  `gradle/libs.versions.toml`).

## TODO before claiming F-Droid main-repo readiness
- Pin the build JDK to Temurin **21 LTS** in F-Droid `metadata.yml` (the release
  pipeline JDK; local dev currently builds on JDK 17).
- Strip archive timestamps:
  `tasks.withType<Jar> { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }`.
- **Build libsodium from source** — the ionspin artifact ships prebuilt `.so` JNI
  blobs, which F-Droid main-repo rejects. Add `androidApp/native/build-libsodium.sh`
  (pinned source tarball, SHA-256 verified, 4 ABIs) and exclude the prebuilt
  blobs via `jniLibs.excludes` (§8). Budget ~2 weekends (§14 risk).
- Add an `fdroid` build flavor that swaps any Play-Services QR scanner for ZXing.

See `PARENT_KMP_STRUCTURE.md` §8 for the full checklist.
