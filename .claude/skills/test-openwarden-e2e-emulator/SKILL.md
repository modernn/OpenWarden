---
name: test-openwarden-e2e-emulator
description: Run OpenWarden end-to-end provisioning + sync tests on Android emulator. Long-running (~15min). Use before merging to main or releasing. Spawns emulator, provisions, asserts /health, tears down.
---

# /test-openwarden-e2e-emulator

End-to-end test: boots Android emulator, provisions OpenWarden as Device Owner, runs sync handshake, verifies all expected restrictions applied.

## What it does

1. Boot Android Pixel 7 emulator API 35 (headless)
2. Run `./scripts/provision-emulator.sh`
3. Verify state via `/health` content provider
4. Run synthetic sync handshake against bench parent
5. Assert restrictions present + working
6. Tear down

## When to use

- Before merging anything touching DPC code
- Before tagging a release
- After modifying provisioning script
- After modifying restriction application logic

## When NOT to use

- For quick iteration — use `/test-openwarden-unit` (10x faster)
- For UI tweaks — use `/test-openwarden-snapshot`

## Background mode

This skill auto-runs in background. ~15min runtime. You'll get a notification when complete.

## Output format

```
E2E: PASS
  - Emulator boot: 4m 12s
  - Provisioning: 2m 03s
  - /health: OK (DO=true, restrictions=17, FRP=true)
  - Sync handshake: OK
  - Restrictions verified: 17/17

OR

E2E: FAIL at step "restrictions verified"
  Missing: DISALLOW_OEM_UNLOCK
  Logs at: /tmp/e2e-failures/2026-06-15-22-34.log
```

## Sample invocation

```
/test-openwarden-e2e-emulator
```
