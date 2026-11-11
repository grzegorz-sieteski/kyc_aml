# customer-risk-scoring

Stage: 6 · Status: IN REVIEW (I-01 to I-10 fixed, `./mvnw -B verify` green; re-review running)
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
| 6 | `@code-reviewer` + `@sec-reviewer` | review reports (below) | **[CHANGES REQUIRED]** both; each in a fresh read-only context that did not see the stage 5 report | 2026-11-08 |

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

Back to stage 3 with the consolidated list below. `@test-designer` writes a failing test for every
R-xx not yet covered (QA-01 to QA-04 are), and corrects the three tests that pin behaviour the spec
forbids. Items marked **PO** wait for a product owner decision before their test is written.

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

## Stage 6 report: consolidated (2026-11-08)

Two independent read-only reviews at `22d1f43` (`@code-reviewer` CR-xx, `@sec-reviewer` SR-xx). Neither
saw the stage 5 report; both found QA-01 to QA-04 again on their own. The orchestrator checked four
claims directly: the en dash (code has ASCII `-`, BR-40 has `–`), the `*Test` placement, and NullAway by
breaking it on purpose (a `@Nullable` dereference fails the build in `risk/domain`, compiles cleanly in
`risk/adapters/web`).

**Held, per both reviews:** no `throw`, catch-all or `*Service`; aggregate package-private and pure;
scoring BR-14 to BR-27 and examples E1 to E7; BR-30 optimistic locking; tenant filter on every SQL
statement, all parameters named; 404 for foreign and unknown alike; no risk column in the front-office
view; no internal id in a response body; the relay lease (2b), savepoint and version-gated projection;
no password default, no actuator, stack traces off.

| R | From | Severity | Finding | Decision |
|---|---|---|---|---|
| R-01 | SR-01 | Critical | Both POST routes bind `@RequestBody String`: the whole body is buffered before the caller is identified, with no size limit (5 MB accepted in stage 5). Anonymous memory exhaustion, live today | **PO**: body limits |
| R-02 | SR-02, CR-6 | High | The decision body is fully parsed before the gate, for anonymous callers too; a mistyped field turns `ESCALATE_EDD` into the freeze permission, so 403 versus 200 depends on input validity (BR-03, BR-37 step 1) | fix |
| R-03 | QA-03, SR-03, CR-3 | High | A literal `;` truncates the customer number: `C-1;x` writes `C-1` (BR-06) | fix: read the raw segment, keep `;` as data (no new refusal) |
| R-04 | CR-1, SR-10 | High | An unknown decision kind is checked against the freeze permission, so the role is judged before the kind. BR-37 and A-27 say kind first. `RiskApiFlowIT.shouldRefuseTheRoleBeforeTheKind` pins the contradiction | **PO**: follow A-27, or amend it |
| R-05 | CR-2, SR-04 | High | A booking centre with no jurisdiction gets 422 after body validation on evaluate, and is admitted on the other three routes. Design and `EvaluateCustomerRiskError` javadoc say caller not identified (401), before any input. `RiskApiFlowIT.shouldRefuseABookingCentreWithoutAJurisdiction` pins it | fix: 401 at identification, all four routes |
| R-06 | QA-01, QA-02, SR-07, CR-4 | Medium | Lenient JSON coercion: `versionViewed` 1.9 or `"1"`, critical facts `0`/`2`/`"false"`. Also duplicate keys, last wins (`sanctionsMatch` true then false is recorded false) | fix: strict mapper for request bodies |
| R-07 | CR-5 | Medium | One unreadable field makes the whole body absent, so the refusal names the profile class instead of the field | fix with R-06 |
| R-08 | QA-04, SR-06, CR-9 | Medium | Container errors (404/405 outside the routes) echo the path with the customer number, not `problem+json` | fix |
| R-09 | SR-08 | Medium | Flyway uses the runtime credentials, so the service owns its schemas and can disable the append-only trigger on the history | **PO**: separate migration role |
| R-10 | SR-09 | Medium | `citizenships` has no upper bound; the list is kept forever in the append-only history | **PO**: maximum |
| R-11 | SR-05 | Medium | `CriticalFacts`, `RatedCountry`, `Explanation`, `CategoryContribution` print their content in `toString` (BR-43); no log sink today | fix + reflection test |
| R-12 | SR-14 (orchestrator verified) | Medium | NullAway is not applied to `risk/adapters/**`: no `@NullMarked` there, and `AnnotatedPackages` names `com.gs5.underwriting`. AGENTS.md claims all of `src/main` | fix: `@NullMarked` on adapter packages, correct the option |
| R-13 | CR-7 | Medium | Transient `DataAccessException` (deadlock, timeout) counts a projection attempt; 10 park a healthy row | fix |
| R-14 | CR-8 | Medium | `markDelivered` / `countAttempt` do not check `locked_by`; after a lease overrun two relays count one row twice | fix |
| R-15 | CR-11 | Medium | Six PostgreSQL-backed `Outbox*Test` run in Surefire, so `./mvnw test` needs Docker | fix: rename to `*IT` |
| R-16 | CR-10 | Low | Front-office text has `-`, BR-40 has `–`; `QueryUseCasesTest:97` pins the hyphen | fix: follow the spec |
| R-17 | CR-12 | Low | Schema deviates from design section 9: no total-score `CHECK`; append-only trigger does not stop `TRUNCATE` | fix |
| R-18 | SR-12 | Low | No `Cache-Control: no-store` on the compliance view | fix |
| R-19 | SR-11 | Low | The 403 detail names the internal role constant | keep: own role, no risk data |
| R-20 | SR-13 | Low | The internal profile id is written to the ERROR log (D-05) | fix |
| R-21 | CR-13, CR-14, CR-15, CR-16 | Nit | Accumulating loops; a `Result` inside a command; one loose assertion; a stale column name and mixed-case UUIDs in kernel docs | optional |

Unconfirmed, carried for later: format characters and lone surrogates in text (stage 5 saw them accepted;
storage not checked); framework DEBUG logging would print the raw body; a lease overrun that holds view
row locks; parked rows never reported; no test boots the real fail-closed resolver.

## Stage 3 (second round): failing tests for R-01 to R-20 (2026-11-08)

Spec D-08 to D-12 and the design addendum of 2026-11-08 come first (commit `66a9642`). Then, in
`src/test/` only:

| R | Test |
|---|---|
| R-01, R-02, R-06, R-07, R-08, R-10, R-18, R-03 | `RiskApiHardeningIT` (real Tomcat and PostgreSQL): byte-exact limits, chunked bodies, 401 before the body, strict JSON, field-level refusals, the four new problem types, `Cache-Control`, container errors |
| R-05 | `RiskApiFlowIT.shouldRefuseABookingCentreWithoutAJurisdiction` corrected to 401 on all four routes; the BOLA test now uses a second configured centre (US) |
| R-09, R-17 | `DatabaseRolesIT`: owner migrates, the service role owns nothing, has no UPDATE/DELETE/TRUNCATE on history, can still evaluate, decide and project; TRUNCATE refused even for the owner |
| R-11 | `ConfidentialToStringTest` |
| R-12 | `NullMarkedPackagesTest` |
| R-13 | `OutboxTransientFailureIT` (a real lock timeout) |
| R-14 | `OutboxLeaseOwnershipIT` |
| R-15 | six `Outbox*Test` renamed to `*IT` |
| R-16 | `QueryUseCasesTest:97` expects the en dash |
| R-20 | `RiskProfileLogIT` |

Gate: `check-write-lock.sh red` OK (test-compile passes, `test` fails). `./mvnw verify`: Surefire 312
run, 5 failures (all new); Failsafe 150 run, 63 failures and 1 error, every one a new or stage 5 test
failing for the reason it names. Seven new cases pass already by design (order guards: 401 before the
body, role before size, D-08).

## Stage 4 (second round): R-01 to R-20 implemented (2026-11-08)

`@developer` in a fresh context, `src/main/` and `pom.xml` only (commit `8a44571`). The green write lock
held: `git diff 619ad52 8a44571 -- src/test docs` is empty. The developer reported one stale stage 3
test instead of editing it (`RecordComplianceDecisionUseCaseTest.shouldRefuseAMissingVersion`, still
pre-D-12); the test designer corrected it in `0b4aabf`.

Orchestrator's own run at `0b4aabf`: `./mvnw -B verify` → 0 Checkstyle violations; Surefire 312 tests,
0 failures; Failsafe 150 tests, 0 failures; BUILD SUCCESS; no `Unsupported class file` warning.

Found on the way, confirmed in the Boot 4.1.2 metadata: `server.error.*` is removed in Spring Boot 4.0
(`level: error`, replacement `spring.web.error.*`). The old `include-message: never` and
`include-stacktrace: never` were never bound; both reviews took them as a control. They now live under
`spring.web.error`, with `include-path: never`.

Deviations from the addendum, accepted: `ActorContextResolver.identify(BookingCentres)` takes the centres
as an argument (the tests subclass the resolver through its no-argument constructor); the
`service_role` placeholder is set from the service's own connection in `RiskDatabaseConfiguration`.

Known, not fixed: `RelayPressureSpike` (not a gated test) fails when run by hand, because it adds the V3
lease columns again. Local `spring-boot:run` now needs `FLYWAY_USERNAME` and `FLYWAY_PASSWORD` too.


## Stage 6 re-review of `66a9642..0b4aabf` (2026-11-09): [CHANGES REQUIRED] from both

Both reviewers ran in fresh read-only contexts (interrupted once by an API limit and resumed). The new
code held against every attack tried: the edge order, `BoundedBody`, strict JSON, the raw customer
number, the error controller, `Cache-Control`, grants, masking. What failed were **framework defaults
left switched on**, plus tests that look like proof but are not.

The orchestrator confirmed the critical finding on a running server before acting: an unidentified
`multipart/form-data` POST with a 2 MB part is answered **413**, not 401, so the body is parsed before
identification.

| R | From | Severity | Finding |
|---|---|---|---|
| R-21 | CR-1, SR-02 | Critical | Multipart parsing (up to 10 MB, written to temp files) before `identify()` on every route |
| R-22 | SR-01 | Critical | `FormContentFilter` reads a whole PUT/PATCH/DELETE form body, unbounded, before dispatch |
| R-23 | CR-2 | Warning | Any non-integrity database failure in the projector is released forever, never counted, never logged |
| R-24 | SR-03b | Warning | `service_role` placeholder quoted but not escaped |
| R-25 to R-29 | CR-3, 5 to 8 | Warning | Missing proof: `markDelivered` guard, production role wiring, `;` on GET and decision routes, citizenship order, TRUNCATE SQLState |

Design: ADR addendum of 2026-11-09. Deferred and recorded there: a fail-closed migration check that the
service role is neither owner nor superuser (SR-03a; needs the Spring ITs moved to a separate role
first), `max-swallow-size`, a body-read deadline for identified callers, `Expect: 100-continue` timing.

## Stage 3 (third round): R-21 to R-29 (2026-11-09)

New: `RiskApiHardeningIT.BeforeIdentification` (no multipart resolver, no form content filter, a 2 MB
multipart POST is 401, `;` kept on every route), `OutboxFailureVisibilityIT`, `RiskDatabaseConfigurationTest`,
`ProductionRolesIT`; extended: `OutboxLeaseOwnershipIT` (late success), `EvaluateCustomerRiskCommandParseTest`
(10/11 citizenships, country before count), `DatabaseRolesIT` (TRUNCATE is `P0001`).

Gate: `check-write-lock.sh red` OK. Failing, each for the reason it names: 7 `RiskDatabaseConfigurationTest`
cases, 3 `BeforeIdentification` cases, 2 `OutboxFailureVisibilityIT` cases. Passing by design (test-only
items R-25 to R-29, the code already does it): the new ownership, citizenship, TRUNCATE, production-role
and raw-number tests.

## Stage 4 (third round): R-21 to R-24 implemented (2026-11-09)

`@developer` in a fresh context, `src/main/` only; `git diff 0bf5e2f -- src/test docs` empty. Multipart
and the form content filter are off; the projector counts every non-transient database failure; each
released chunk is logged with the exception type only; the role name is validated.

The developer found what the design missed: R-23 depends on SQL exception translation. An empty
`sql-error-codes.xml` is required (see the ADR note). The orchestrator proved it by removal: without the
file, two lock-timeout tests fail; a first removal attempt passed falsely because `target/classes` kept
a stale copy.

A WIP commit (`6c94cc3`) of the developer's half-done tree was pushed while it ran, at a stop hook's
request; the verified commit follows it.

Orchestrator's run: `./mvnw -B verify` → 0 Checkstyle violations; Surefire 324, 0 failures; Failsafe
159, 0 failures; BUILD SUCCESS.

## Stage 6 re-review of `0b4aabf..836287b` (2026-11-09): [APPROVED] from both

No blocker, no critical. Security: no way left for an unidentified caller to make the service read,
buffer, parse or store a body before `identify()` in the shipped configuration (checked against the
Boot 4.1.2, Spring 7.0.10 and Tomcat 11.0.27 bytecode). Both reviewers raised the same warning: the role
pattern accepts `public` (R-30). Further warnings are recorded in the ADR addendum (2) of 2026-11-09.

## Stage 3 (fourth round): R-30 and the test warnings (2026-11-09)

`RiskDatabaseConfigurationTest`: reserved names and a preset placeholder (9 failing cases, R-30); a
63-character name accepted (W-5). `OutboxFailureVisibilityIT`: the WARN line must end in exactly the type,
with no exception text or stack trace (W-4, passes). `RiskApiHardeningIT`: the multipart probe body is
1.5 MB, under Tomcat's 2 MB swallow limit, so the 401 cannot be cut off (still 413 before R-21's fix).
Run: Surefire 335, 9 failures (all R-30); Failsafe 159, 0 failures.

## Stage 4 (fourth round): R-30 fixed (2026-11-09)

Commit `51071e1`, `src/main/` only. Orchestrator's run of `check-write-lock.sh green`: src/test and docs
untouched; `./mvnw -B verify` → 0 Checkstyle violations; Surefire 335, 0 failures; Failsafe 159,
0 failures; BUILD SUCCESS.

Known gap reported by the developer, not fixed: Flyway 12 environment-level placeholder overrides are
read by `getPlaceholders()` but not cleared by `placeholders(Map)`. Spring Boot sets no such override.

## Security check of R-30 (2026-11-09): [CHANGES REQUIRED], R-31

Flyway matches placeholder names ignoring case; the R-30 removal did not. A preset `SERVICE_ROLE=public`
would survive and reach V5's GRANT. Needs control of the configuration, but the effect is permanent.
Stage 3: `RiskDatabaseConfigurationTest` gains 6 failing cases (case variants, including a Turkish
dotted capital I; and a valid user leaving only its own name).

## R-31 fixed; security check of it: [CHANGES REQUIRED], R-32 (2026-11-09)

`a0fca7f`: every case variant of `service_role` removed. Orchestrator's GREEN gate: src/test and docs
untouched; Surefire 341, Failsafe 159, 0 failures; BUILD SUCCESS. The check confirmed the key removal is
complete, then found that a configured placeholder suffix makes V5 read another name (R-32). Stage 3:
two failing cases in `RiskDatabaseConfigurationTest` pin prefix, suffix and replacement.

## R-32 fixed and the Flyway path closed (2026-11-09)

`05a9fbb`: prefix, suffix and replacement pinned. Orchestrator's GREEN gate: src/test and docs untouched;
Surefire 343, Failsafe 159, 0 failures; BUILD SUCCESS. Then one exhaustive security pass over every
`spring.flyway.*` setting instead of another narrow one: `[APPROVED]`. Its two recommendations became
deployment controls in AGENTS.md and a deferred startup self-check in the ADR; a Javadoc line in
`RiskDatabaseConfiguration` that overstated the guarantee was corrected (comment only).

## Where the module stands

Every review round is approved: stage 5 QA findings, stage 6 code and security reviews and their three
re-reviews, and the R-30 to R-32 security checks. Open, for the product owner: the identity source (every
call is 401 until one is chosen), dead-letter handling of the outbox, and the `openapi/` contract.
Deferred and recorded in the ADR: the fail-closed D-11 checks at migration and at startup (both need the
Spring ITs on two roles), `max-swallow-size` and `100 Continue` timing, error-code lookup under an
exhausted pool, the total-score `CHECK`, and `RelayPressureSpike` failing when run by hand.

## Identity source (2026-11-09): decisions D-13 to D-16

Branch restarted from `main` after PR #1 was merged. Product owner: JWT bearer tokens (OAuth2 resource
server) issued by Microsoft Entra ID; claim names configurable; exactly one role of this service per token,
else 401. Spec D-13 to D-16 and BR-01; design: ADR addendum 2026-11-09 (3).

Stage 3: `IdentityIT` and `IdentityClaimNamesIT` drive the real filter chain and resolver with RS256 tokens
signed by JDK crypto and a JWK set served locally (`TestTokens`); `src/test/resources/config/application.yml`
gives the other Spring tests test values. Failing, for the reason named: every token that should be accepted
(7 cases) and the `WWW-Authenticate: Bearer` header. The 18 bad-token cases and the query/form cases pass
already **only because today's resolver refuses everything**: they guard against over-acceptance once the
resolver exists, and are to be proved by mutation after stage 4.
Run: Surefire 343, 0 failures; Failsafe 189, 8 failures (all identity).
Gate: `check-write-lock.sh red` reports FAIL ("./mvnw test PASSED") because it checks Surefire only and
this round's failing tests are all integration tests; the RED evidence is the Failsafe run above. Gap in
the script, recorded: a RED round made only of `*IT` cannot pass it.

## Identity source: stage 4 done (2026-11-09)

`57e7111`, `src/main/` and `pom.xml` only (the container restarted mid-round; the working tree survived and
the developer's report was recovered from its transcript). Orchestrator's GREEN gate: src/test and docs
untouched; 0 Checkstyle violations; Surefire 343, Failsafe 189, 0 failures; BUILD SUCCESS.

Mutation check of the refusal tests, which had passed before any identity code existed: accepting the
first of several roles, and allowing a query-string token, each fail `IdentityIT`. Removing `audiences`
from the shipped `application.yml` failed nothing, because the ITs set it themselves; `ProductionIdentitySettingsTest`
now pins the shipped settings and fails under that mutation.

Developer's deviations, recorded in the ADR: three firewall allowances (`;`, encoded CR, LF) so BR-06 keeps
being answered by the resources; Spring Security's `/.well-known/oauth-protected-resource` endpoint exists.

## Identity reviews (2026-11-09): [CHANGES REQUIRED] from both; stage 3 for I-01 to I-10

Both reviews agree on the critical one: an identity setting that is present but empty starts the service
with the issuer or audience check missing (I-01). Also: the firewall still refused `%25`, `%2E`, U+2028 and
U+2029 that BR-06 allows (I-03); two distinguishable 401s (I-04); a 500 when the key set is unreachable
(I-05); one outbound key fetch per unknown `kid` (I-06); Spring Security's metadata endpoint reflecting host
and path (I-07); a token without `exp` accepted (I-02). Design: ADR addendum 2026-11-09 (4).

Stage 3 run (`verify` with failures ignored): Surefire 346, 1 failure; Failsafe 208, 14 failures, each a new
case failing for the reason it names: startup with an empty setting succeeds (3), firewall 400 (4),
unequal 401s (2), metadata endpoint served (2), no `exp` accepted (1), 25 fetches for 25 unknown key ids
(1), 500 with keys unreachable (1), logging level unpinned (1). Already passing guards: `/logout`,
`/login`, a non-text role element, a nested claim object.

Open for the product owner, recorded in the ADR: how the onboarding system's app-only tokens carry a
booking centre; ID tokens versus access tokens; who may write the booking-centre attribute.

## Identity round 2: stage 4 done (2026-11-10)

`src/main/` only. Orchestrator's GREEN gate: src/test and docs untouched; 0 Checkstyle violations; Surefire
346, Failsafe 208, 0 failures; BUILD SUCCESS. Developer's choices are recorded in the ADR. Docker had to be
restarted by hand after a container restart left a stale containerd pid.

## Open items, pass 2026-11-11

| Item | State |
|---|---|
| RelayPressureSpike fails when run by hand | fixed (`ALTER` of V3's columns removed); passes with Docker up |
| OpenAPI contract for the four endpoints | written (`openapi/risk-profiles.yaml`), YAML valid, references resolved; contract tests not yet written |
| BFF boundary | architect writes `docs/adr/bff-boundary.md` (in progress) |
| Dead-letter | decided: spec D-18 and BR-44 to BR-46. Design and implementation not started; the releasing role is open for the product owner |
| Identity: key-fetch trade-off (F1) | onboarding accepted internal-only (D-17); customer-facing via BFF; no code change |
| Identity: open questions | ID token vs access token; who may write the booking-centre attribute; onboarding's booking centre (app-only tokens may carry no claim) |
| Deferred technical | startup role check, max-swallow-size, error-code lookup under pool exhaustion, total-score CHECK: recorded in the ADR, not started |

## BFF boundary ADR: audited, `[CHANGES REQUIRED]` (2026-11-11)

Independent architect audit of `docs/adr/bff-boundary.md` (read-only, fresh context): 2 blockers, 9
warnings. The blockers and the list are summarised at the top of the ADR. Next: product-owner answers
(sign-in flow, onboarding's identity and booking centre, what a misprovisioned user sees, acceptance of
internal key-fetch flooding, TLS on the internal hop), a spec decision for the calling-client rule, then
a second ADR draft. No code is authorised.
