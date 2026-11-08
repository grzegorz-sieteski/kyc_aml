# customer-risk-scoring

Stage: 5 · Status: CHANGES REQUIRED (stage 5 found four defects; back to stage 3 per AGENTS.md)
Updated: 2026-11-08

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
| 3 | `@test-designer` | contracts and failing tests | **Done** for the domain (RED proved); web edge written with its tests and proved by mutation | commit `d87f0fd` |
| 4 | `@developer` | production code | **Done**: `./mvnw -B verify` 332 unit, 37 IT green (re-run 2026-11-08 on JDK 25.0.4, PostgreSQL 17) | commit `894c0a8` |
| 5 | `@qa-explorer` | `requests/customer-risk-scoring-exploratory.http`, `RiskApiAdversarialIT` | **[REJECTED]**: QA-01 to QA-04, 14 failing cases; 17 guard cases pass; `check-write-lock.sh qa` OK | 2026-11-08 |
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

Stage 6 (code and security review, read-only) runs on the current state; its findings join QA-01 to
QA-04. Then back to stage 3: the failing tests for QA-01 to QA-04 already exist in
`RiskApiAdversarialIT`, so `@developer` fixes `src/main/` until they pass, with `src/test/` frozen.

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

## Relay connection pressure: closed (2026-11-08)

Candidate 2b of the ADR addendum is implemented: `V3__outbox_lease.sql`, a relay that leases a batch in a
short transaction and projects one chunk per connection (budget `app.outbox.relay.connection-budget`,
default half the pool, lease `app.outbox.relay.lease` = PT30S), a pool or commit failure that gives the rows
back and counts no attempt, and a projector that runs in a savepoint. `./mvnw verify`: 332 unit, 37
integration tests green. One test (`OutboxConcurrentRelaysTest.shouldLetTheOtherRelayWorkAroundALeasedBatch`)
was wrong, not the code: it compared with calls the blocked holder had not yet recorded, and was changed to
ask the database which rows are held. Not done: the spike was not re-run with the production pool size and a
realistic request mix, which the ADR asks before the batch size, lease and budget are final.

## Stage 5 report: `@qa-explorer` (2026-11-08)

Real Tomcat on a random port and a real PostgreSQL 17 (`RiskApiAdversarialIT`, `java.net.http`, no
MockMvc, so the container's own path handling and error dispatch are in play). `src/main/` untouched.

Verdict: **[REJECTED]**.

| ID | Severity | What was done | Expected | Actual | Failing test |
|---|---|---|---|---|---|
| QA-01 | Medium | L2 freezes with `"versionViewed": 1.9` and with `"versionViewed": "1"` | Refused, nothing recorded: the person viewed no version 1.9 (BR-33) | 200; the decision is recorded as taken on version 1. Jackson coerces float and string to `Long` | `shouldNotRecordADecisionOnAVersionThatIsNotAWholeNumber` (2 cases) |
| QA-02 | Medium | Evaluate with `"sanctionsMatch": 0`, `1`, `2`, `"false"`; `"terroristLink": 0`; `"forgedIdentity": "false"`; `"highRiskProductRequest": 0` | Refused: each is yes or no (BR-10, BR-12). An upstream sending a match score or a flag as a number must not be read as "no sanctions match" | 200; numbers and strings are coerced to `Boolean`. `0` and `"false"` become "no critical fact" | `shouldRefuseAYesOrNoAnswerThatIsNotABoolean` (7 cases) |
| QA-03 | Medium | Evaluate `POST /api/risk-profiles/C-1;x/evaluations` | `C-1` untouched: the number is compared exactly as supplied (BR-06), or the request is refused | 200 on customer `C-1`, whose profile moves to version 2. Spring strips `;x` as a matrix parameter. Encoded `%3B` is kept correctly | `shouldNotTruncateACustomerNumberAtASemicolon` |
| QA-04 | Low | `DELETE` / `PUT` on a profile, `GET .../evaluations`, an unmapped sub-path | A 4xx that does not repeat the customer number (design section 10, which already pins `instance` for that reason) | Spring Boot's default error body: `{"status":405,...,"path":"/api/risk-profiles/C-SECRET"}`, not `problem+json` | `shouldNotEchoTheCustomerNumberFromTheContainerErrorPath` (4 cases) |

QA-01 and QA-02 share a root cause (lenient scalar coercion in the `JsonMapper` the resources read
bodies with), but they are separate findings: each needs its own guard.

**Held under attack (guard tests, passing):** 24 concurrent freezes on one version, exactly one
recorded and every other 409; 24 concurrent first evaluations of one new number, one profile, one
history row per success and no 5xx; customer number 64 emoji accepted and 65 too long (code points);
encoded LF, CR, DEL and NEL refused as control characters; encoded NUL, `/`, `\`, invalid UTF-8 and a
CESU surrogate refused by Tomcat with a 400 that names no server or version; justification 19 emoji too
short and 20 accepted, 2 000 accepted and 2 001 too long, CR LF accepted and a lone CR refused; JSON
nested 5 000 deep read as no input; a `tenantId` smuggled into the body ignored.

**Tried, not defects, for the product owner or the architect to decide:**
- No request size limit: a 5 MB body and 1 000 citizenships were accepted and scored. Not in the spec.
- A justification of 25 spaces is accepted (BR-34 counts characters, not visible ones).
- Format characters (U+202E right-to-left override, U+2028 line separator) and a lone surrogate
  (`\ud800`) are accepted in a justification; BR-34 bans control characters only (Unicode `Cc`).
- `..` is accepted as a customer number (BR-06 allows it).
- Paths Tomcat refuses answer `text/html`, not `problem+json`; nothing is disclosed.
- `GET /error` answers 500 with status 999 (Spring Boot default).
- A duplicate `"kind"` key: the last one wins for both the permission check and the command, so the role
  check is not bypassed (an L1 sending `FREEZE_ASSETS` then `ESCALATE_EDD` escalates, which L1 may do).
