---
name: test-openwarden-unit
description: Run OpenWarden unit test suite (fast JVM tests). Use when verifying crypto, protocol, policy logic changes. Auto-trigger on completion of crypto/protocol implementation work.
---

# /test-openwarden-unit

Runs the fast JVM unit tests for OpenWarden.

## What it does

1. Runs `./gradlew :proto:test :shared:test :childAndroid:testDebugUnitTest`
2. Reports pass/fail + failing test names
3. On fail: surfaces stack traces for top 3 failures

## When to use

- After implementing or modifying crypto code in `shared/commonMain/crypto/`
- After modifying signed bundle / log entry types in `proto/`
- After modifying policy logic in `childAndroid/`
- Before committing any change to canon spec implementations

## When NOT to use

- For UI changes (use `/test-openwarden-snapshot` instead)
- For full E2E (use `/test-openwarden-e2e-emulator` instead — slow)
- For provisioning flow (use `/provision-openwarden-emulator` instead)

## Output format

```
Unit tests: 47 passed, 2 failed
Failures:
  - PolicyBundleVerifierTest.rejects_regression_seq: expected REGRESSION got APPLIED
  - SealedBoxTest.ephemeral_key_destroyed: NullPointerException at line 42
```

## Sample invocation

```
/test-openwarden-unit
```
