# customer-risk-scoring

Stage: 3 · Status: IN_PROGRESS (design confirmed by the product owner 2026-11-07)
Updated: 2026-11-07

## Source

The ARGON analysis: a Gemini conversation supplied by the product owner
(`https://share.gemini.google/duz8FJERYums`, published 2026-11-07). It holds an AML/KYC/CTF
specification for a global bank and an architecture the product owner narrowed in the conversation:
CQRS at the database level, JDK 25, DDD with a rich aggregate and a version, a modular monolith with
an in-process partitioned event bus instead of a Kafka broker, and ArchUnit fitness rules. It was read
in full (3 379 lines) before the specification was written.

Product owner decisions of 2026-11-07, recorded in the specification as D-01 to D-04: the first
increment is the scoring core; the jurisdiction multiplier applies to the whole weighted sum; no user
interface yet; ARGON's numbers are not confirmed and appear as assumptions.

## Gates

| Stage | Agent | Artefact | Gate result | Verified at |
|---|---|---|---|---|
| 0 | bootstrap | build, shared kernel, gates | `./mvnw -B verify`: 0 Checkstyle violations, 75 tests, BUILD SUCCESS. Gates broken on purpose once: a `throw new` in the kernel failed Checkstyle (`noThrowNew`); a kernel `*Helper` depending on Spring failed `shared_kernel_stays_pure_java` and `no_grab_bag_classes`. Probes removed | commit `a5edac7` |
| 1 | `@business-analyst` | `docs/specs/customer-risk-scoring-spec.md` | **Passed**: A-01 to A-31 confirmed by the product owner (D-07); booking centres US, GB, DE (D-06); `grep -c 'NOT CONFIRMED'` → 0 | 2026-11-07 |
| 2 | `@architect` | `docs/adr/customer-risk-scoring-design.md` | **Written**: covers both commands and both queries; H-01 to H-08 resolved in the design |  2026-11-07 |
| 3 | `@test-designer` | contracts and failing tests | not started | — |
| 4 | `@developer` | production code | not started | — |
| 5 | `@qa-explorer` | `requests/customer-risk-scoring.http` | not started | — |
| 6 | `@code-reviewer` + `@sec-reviewer` | review reports | not started | — |

## Carried to stage 2: where ARGON and this harness disagree

ARGON's code samples are illustrations, not a design. Where they break a harness rule, the harness
wins, because the product owner asked for the implementation to follow `AGENTS.md`.

| # | ARGON | Harness rule | Direction for the design |
|---|---|---|---|
| H-01 | `throw new IllegalArgumentException`, `Objects.requireNonNull`, exceptions for conflicts | No `throw` and no `requireNonNull` in `src/main`; failures are values | `Result` with one sealed `*Error` per operation |
| H-02 | `CtfScoringDomainService`, `CtfQuarantinePersistenceService`, `@Service` in the application layer | No `*Service`; one `*UseCase` per operation; no Spring in `domain/` | One use case class per command and per query; beans in `adapters/config/` |
| H-03 | jOOQ for aggregation queries | No JPA, QueryDSL or jOOQ | Spring Data JDBC with native SQL into records |
| H-04 | `double` scores, `Math.round` | Value objects; no magic numbers | A score value object on `BigDecimal` with an explicit scale and rounding, constants named |
| H-05 | A separate read module (`readprojections`) that cannot see the write module | The architect's audit rule: no `query/` package; read use cases live in `domain/` with `ask` | Read use cases in `domain/`; CQRS stays at the database level, with separate `command` and `query` schemas and a projector writing the second |
| H-06 | In-process bus that dispatches only after commit and catches `Exception` | Outbox relay with `FOR UPDATE SKIP LOCKED`; no catch-all; consumers deduplicate | Outbox in the write transaction, a relay polling it, partitioned in-process delivery by customer, version-gated idempotent projection; no event is lost if the process stops between commit and delivery |
| H-07 | `Instant.now()` and `UUID.randomUUID()` inside the aggregate | The write side is a pure function of its inputs | A clock and an id-generator port |
| H-08 | Kafka named in the stack | ARGON's last decision removes the broker; the build still carries the Kafka starter | The architect decides whether the starter stays |

Two inconsistencies inside the harness itself, found while preparing this stage:

- `.github/copilot-instructions.md`, the `hexagonal-cqrs-slice` skill and the `domain-cqrs-purity`
  instructions still describe a `query/` package, while `architect.agent.md` makes its existence a
  `[BLOCKER]`. This board follows the architect's rule (H-05) until the product owner says otherwise.
- `ActorRole` in the shared kernel still carries the template project's roles (`VIEWER`,
  `UNDERWRITER`, `SENIOR_UNDERWRITER`). The KYC roles come from assumption A-02 once it is confirmed.

## Open defects

None. Known gaps: no identity source (every call is 401 until one is chosen), no dead-letter handling for an outbox row that keeps failing (attempts make it visible), no real booking-centre configuration, no React yet.

## Next

Open a new chat and call the architect:

> `@architect` design mode. Write `docs/adr/customer-risk-scoring-design.md` from
> `docs/specs/customer-risk-scoring-spec.md`, following the `spec-to-plan` skill. Resolve H-01 to
> H-08 in `docs/workflow/customer-risk-scoring-state.md` explicitly, and cover both commands and both
> queries.

## Status (2026-11-07)

Stages 1-4 are done for the whole scoring core: domain, access gate, persistence, projection and
relay, web edge and wiring. `./mvnw verify` is green on a real PostgreSQL 17 (296 unit tests, 36
integration tests). The web edge was written together with its tests rather than RED first; the tests
were proved by mutating a status mapping and a permission (both made them fail). Stage 5 has only the
request file `requests/customer-risk-scoring.http`; the independent code and security reviews of
stage 6 have not been run.

## Open design question for the architect: relay connection pressure (2026-11-07)

Raised by the code review, deliberately not fixed by the developer because it is an architecture choice.

**Problem.** `OutboxRelay.runOnce` claims a batch with `FOR UPDATE SKIP LOCKED` inside one transaction
and keeps that transaction (one connection, row locks) open while up to 16 virtual-thread workers each
project in their own transaction, so each takes another connection. With the default Hikari pool of 10
the workers queue for connections while the claim locks are held; under request load a worker can wait
past the connection timeout, fail, and count an attempt for nothing.

**Constraints.** Projection stays idempotent and version-gated; per-profile order within a batch;
several relays may run at once without delivering a row twice; no event is lost on a crash; no broker;
no `throw`, no catch-all; harness rules unchanged.

**Candidate solutions to compare in the proof of concept.**
1. Bound the workers to the pool (a semaphore or fewer partitions) and keep the long claim transaction.
2. Claim and mark short: claim rows into a lease (`locked_until`, `locked_by`) in a short transaction,
   project outside any claim transaction, then mark delivered in a second short transaction; expired
   leases are reclaimed after a crash.
3. Project inside the claim transaction on the same connection, partitioned by profile across
   several claimers instead of threads.
4. Move the projection into the write transaction (same database) and drop the relay for the view.

**Expected output.** `docs/adr/` addendum with the measured comparison (connections held, throughput,
behaviour on a crash between claim and mark, on a pool of 10 and of 3), a recommendation, and a spike
under `src/test/` or a scratch module that shows it; no change to `src/main/` until the design is
confirmed.
