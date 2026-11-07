# AI SDLC flow — description (English)

The process runs in eight stages. Each stage is done by a separate role in a fresh context, so
no role inherits the previous one's reasoning. A gate is a condition the orchestrator checks
before work moves on.

- **Stage 0, orchestrator.** Opens and maintains the state board in `docs/workflow/`. It routes
  work and checks every gate; it writes no code and no tests.
- **Stage 1, business analyst.** Writes the specification in `docs/specs/` as numbered business
  rules (`BR-xx`). Any business number it cannot trace to the product owner must be marked
  `ASSUMPTION`. **Gate:** no unresolved `ASSUMPTION` marker.
- **Stage 2, architect.** Writes the technical design in `docs/adr/`. **Gate:** the design covers
  every command in the specification.
- **Stage 3, test designer (RED).** Writes the contracts and the failing tests. Stubs return
  `null`. **Gate:** `test-compile` succeeds and `test` **fails**. A green run here means the
  tests check nothing.
- **Stage 4, developer (GREEN).** Implements `src/main/` until the tests pass. It may not edit
  tests. **Gate:** `verify` succeeds and `src/test/` is unchanged, checked by
  `scripts/check-write-lock.sh green`.
- **Stage 5, QA explorer.** Writes HTTP scenarios and adversarial tests. It may not patch
  production code. **Gate:** `src/main/` is unchanged.
- **Stage 6, code and security reviewers.** Read-only. Each returns `[APPROVED]` or
  `[CHANGES REQUIRED]`.

When a reviewer returns `CHANGES REQUIRED`, the work goes back to **stage 3**. A failing test is
written for each finding first, and only then is the code changed. A test that does not fail
before the fix does not prove the fix.

Write-lock rules are enforced by `scripts/check-write-lock.sh`, which compares against the last
reviewed commit, not against whatever `HEAD` happens to be.
