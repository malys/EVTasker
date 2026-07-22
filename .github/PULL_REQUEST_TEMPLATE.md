## What and why

<!-- What changes, and what problem it solves. The diff already says what; explain why. -->

## Verification

<!-- Be specific about what you actually ran. "Not verified on a vehicle" is a fine and
     expected answer — most of this cannot be tested off the car. -->

- [ ] `mise run check` passes (permission gate + lint + unit tests)
- [ ] New behaviour is covered by a unit test
- [ ] Tried on a real MG4 — if yes, which firmware: <!-- e.g. SWI68 -->

## Vehicle-safety checklist

- [ ] MG4Tasker still writes nothing to the vehicle directly (all writes via MG4Control)
- [ ] Unreadable conditions make a rule *not evaluable*, never fire on a default
- [ ] Any new failure mode fails closed
- [ ] New vehicle catalogue entries carry `@SupportedOn(...)`; `docs/firmware-matrix.md` regenerated
- [ ] No new `uses-permission` — or it is added to the allowlist with a justification

## Notes for the reviewer

<!-- Anything you are unsure about, or deliberately left out of scope. -->
