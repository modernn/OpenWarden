<!-- Thanks for contributing to OpenWarden! Fill this in; delete what doesn't apply. -->

## Summary

<!-- What does this change and why? -->

Closes #<!-- issue number -->

## Type
- [ ] feat
- [ ] fix
- [ ] docs
- [ ] chore / test / refactor

## Checklist
- [ ] Conventional PR title (`feat:`/`fix:`/`docs:`/`chore:`/`test:`)
- [ ] Commits **signed + DCO** (`git commit -S -s`); no `--no-verify`
- [ ] `cd parent-kmp && ./gradlew check` passes (or the relevant module build)
- [ ] **Tests added/updated** — required for crypto, protocol, policy logic, features, and bug fixes (regression test)
- [ ] Touched behavior described in `docs/` or an ADR? Doc updated in **this** PR
- [ ] Architecture pivot? New ADR in `docs/adr/`
- [ ] No secrets committed (keys, tokens, BIP39 phrases)

## Non-negotiables (confirm)
- [ ] No subscription / SaaS / telemetry / analytics / phone-home
- [ ] No content monitoring (messages/photos/audio never read or sent)
- [ ] Fail-closed: error paths default to more restriction, not less

## Notes for reviewers
<!-- Anything tricky, follow-ups, screenshots, etc. -->
