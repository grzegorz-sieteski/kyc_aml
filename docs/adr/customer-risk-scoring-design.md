# Design: customer-risk-scoring

Stage 2 artefact, from `docs/specs/customer-risk-scoring-spec.md` (stage 1 passed, D-01 to D-07).
Every business number here traces to that specification; the design adds none.

## Scope

Covers both commands (Evaluate Customer Risk, Record Compliance Decision), both queries (Compliance
Risk View, Front-Office Status), both events, and the persistence, delivery and projection that
connect them. Not covered: everything under the specification's "Out of scope", and the items in
"Not doing" below.

## 1. Module and layout

One module, named for the capability: `com.gs5.kycaml.risk`.

```
com.gs5.kycaml
├── shared/kernel/          Result, ActorContext, ActorRole (roles replaced, §2), ControlCharacters,
│                           LogSafeText, CanonicalUuid
└── risk/
    ├── domain/             write AND read use cases, commands, queries, errors, ports, events,
    │                       value objects, the package-private aggregate (H-05)
    └── adapters/
        ├── access/         OperationGate, Permission table (BR-02, BR-03)
        ├── web/            *Resource classes (naming rule of the harness)
        ├── persistence/    command-schema JDBC entities, repositories, mappers
        ├── projection/     query-schema writers and readers
        ├── events/         outbox relay and the in-process partitioned bus
        └── config/         beans, the booking-centre policy table
```

There is no `query/` package (H-05, `architect.agent.md`). CQRS lives at the database level: the
write side uses schema `command`, the views live in schema `query`, and only the projector writes
`query`. A read use case exposes `ask`, never touches the aggregate, the command repository or the
event publisher, and reads the `query` schema through a read port.

## 2. Shared kernel changes

- `ActorRole` becomes `ONBOARDING_SYSTEM`, `AML_ANALYST_L1`, `AML_ANALYST_L2`, `MLRO`,
  `FRONT_OFFICE` (A-02). `canDecide()` is removed; permissions live in the permission table.
- `ActorContext.tenantId` is the booking centre's id. Nothing else changes in the kernel.

## 3. Value objects (domain, public, parse returns `Result`)

| Type | Rule | Parse failures |
|---|---|---|
| `CustomerNumber` | 1–64 code points, no Cc, exact (BR-06, A-06) | `Missing`, `TooLong(max, actual)`, `ControlCharacter(codePoint, index)` |
| `CountryCode` | ISO 3166-1 alpha-2, checked against `Locale.getISOCountries()` (BR-08) | `Invalid` |
| `GeographicRiskClass` enum | `LOW` 0, `FATF_GREY_LIST` 50, `FATF_BLACK_LIST_OR_CONFLICT_ZONE` 100 | — |
| `CustomerProfileClass` enum | the five classes with their points (BR-15) | — |
| `AdverseMediaClass` enum | 0 / 50 / 95 (BR-16) | — |
| `OnboardingChannel` enum | `BRANCH` 10, `REMOTE` 40 (BR-17) | — |
| `RiskScore` | `BigDecimal`, scale 2, 0.00–100.00 (A-01) | constructed only by the scoring functions |
| `RiskTrack` enum | `GREEN`, `YELLOW`, `ORANGE`, `RED`, ordered | — |
| `AccountRestriction` enum | `ACTIVE`, `SOFT_LIMITS`, `EDD_HOLD`, `ASSETS_FROZEN` (BR-28) | — |
| `DecisionKind` enum | `FREEZE_ASSETS`, `ESCALATE_EDD`, `CLEAR_FALSE_POSITIVE` | — |
| `Justification` | 20–2 000 code points; Cc allowed only as LF or CR LF (BR-34, A-25) | `Missing`, `TooShort(min, actual)`, `TooLong(max, actual)`, `ControlCharacter(codePoint, index)` |
| `RiskProfileVersion` | `long` ≥ 1 | — |
| `RiskProfileId` | internal UUID, never leaves the service (D-05) | — |

Every number above is a named `public static final` constant or an enum attribute (no magic
numbers). Enum points are the specification's numbers, nothing else.

## 4. Commands (write side)

| Command record | UseCase method | Fields | Error variants (in judgement order) | Event |
|---|---|---|---|---|
| `EvaluateCustomerRiskCommand` | `EvaluateCustomerRiskUseCase.execute(ActorContext, command) → Result<RiskProfileVersion, EvaluateCustomerRiskError>` | `CustomerNumber`, `CustomerProfileClass`, `RatedCountry residence`, `List<RatedCountry> citizenships` (≥1), `RatedCountry birth`, `CountryCode login`, `OnboardingChannel`, `boolean highRiskProductRequest`, `AdverseMediaClass`, `CriticalFacts(boolean sanctionsMatch, boolean terroristLink, boolean forgedIdentity)` | see below | `CustomerRiskEvaluated` |
| `RecordComplianceDecisionCommand` | `RecordComplianceDecisionUseCase.execute(ActorContext, command) → Result<RiskProfileVersion, RecordComplianceDecisionError>` | `CustomerNumber`, `DecisionKind`, `Justification`, `RiskProfileVersion versionViewed` | see below | `ComplianceDecisionRecorded` |

`RatedCountry(CountryCode code, GeographicRiskClass riskClass)`. Each command has a static
`parse(...)` from raw request values, returning its own error in the catalogue's order; the request
DTO is parsed only after the gate admits the caller (BR-03).

```java
public sealed interface EvaluateCustomerRiskError {
    // gate (adapter): CallerNotIdentified, RoleMayNotPerform   — AccessRefusal, shared
    record CustomerNumberMissing() ...
    record CustomerNumberTooLong(int max, int actual) ...
    record CustomerNumberControlCharacter(int codePoint, int index) ...
    record ProfileClassNotRecognised() ...
    record CountryInvalid(CountryField field) ...              // RESIDENCE, CITIZENSHIP, BIRTH, LOGIN
    record NoCitizenship() ...
    record GeographicClassInvalid(CountryField field) ...
    record ChannelNotRecognised() ...
    record ProductRequestMissing() ...
    record AdverseMediaClassNotRecognised() ...
    record CriticalFactMissing(CriticalFactField field) ...    // SANCTIONS_MATCH, TERRORIST_LINK, FORGED_IDENTITY
    record RiskProfileChangedConcurrently() ...
}

public sealed interface RecordComplianceDecisionError {
    // gate: CallerNotIdentified; kind parsed BEFORE role (A-27): DecisionKindNotRecognised; RoleMayNotPerform
    record DecisionKindNotRecognised() ...
    record CustomerNotFound() ...                              // unknown, other centre, malformed number
    record JustificationMissing() ...
    record JustificationTooShort(int min, int actual) ...
    record JustificationTooLong(int max, int actual) ...
    record JustificationControlCharacter(int codePoint, int index) ...
    record RiskProfileChangedSinceViewed() ...
    record CriticalFactInForce() ...
    record AlreadyFrozen() ...
    record AlreadyEscalated() ...
    record NothingToClear() ...
    record RiskProfileChangedConcurrently() ...
}
```

No variant carries a score, a track, a justification or a customer number (BR-41, BR-43).
`DecisionKindNotRecognised` is decided before the role because the permission depends on the kind:
the record-decision gate is the one gate that parses one field (the kind) first.

## 5. Queries (read side)

| Query record | UseCase method | View record | SQL shape | Index |
|---|---|---|---|---|
| `ComplianceRiskViewQuery(CustomerNumber)` | `GetComplianceRiskViewUseCase.ask(ActorContext, query) → Result<ComplianceRiskView, ComplianceRiskViewError>` | `ComplianceRiskView`: every field of spec query 1, no internal id | `SELECT … FROM query.compliance_risk_view WHERE tenant_id = :tenantId AND customer_number = :customerNumber` | PK `(tenant_id, customer_number)` |
| `FrontOfficeStatusQuery(CustomerNumber)` | `GetFrontOfficeStatusUseCase.ask(ActorContext, query) → Result<FrontOfficeStatusView, FrontOfficeStatusError>` | `FrontOfficeStatusView(customerNumber, status, message)` | `SELECT … FROM query.front_office_status WHERE tenant_id = :tenantId AND customer_number = :customerNumber` | PK `(tenant_id, customer_number)` |

`ComplianceRiskViewError` and `FrontOfficeStatusError`: `CustomerNotFound()` (unknown, other centre,
or a number that cannot be one). Access refusals come from the gate. `query.front_office_status` has
no column that could hold risk information, so tipping-off is impossible by schema, not by care
(BR-40). The front-office message texts are constants in the projector, not in the table rows
(A-28).

## 6. Aggregate `CustomerRiskProfile` (package-private)

State: `RiskProfileId id`, `UUID tenantId`, `CustomerNumber`, `latestEvaluation` (inputs, policy,
explanation, total, evaluated track, critical facts), `@Nullable RiskTrack decidedTrack`, derived
`effectiveTrack` and `restriction`, `@Nullable Long version`, `latestDecision`.

Factories and behaviour, all returning `Result`, none performing I/O:

- `create(id, tenantId, number, Evaluation)` — version becomes 1 on insert (BR-29).
- `reevaluate(Evaluation)` — replaces the evaluation (BR-13); keeps the decided track (BR-31).
- `decide(DecisionKind, Justification, versionViewed, actorId, Instant)` — checks BR-33, BR-36, BR-35
  in that order; applies BR-32.
- `reconstitute(snapshot)` — re-parses stored values; failure maps to `CustomerNotFound`.

`effectiveTrack = decidedTrack == null ? evaluated : max(decided, evaluated)` (BR-31);
`restriction = restrictionOf(effectiveTrack)` (BR-28).

**Scoring is a pure function** `RiskEvaluation.of(command inputs, JurisdictionPolicy, bookingCentreCountry)`:

1. Category points: geographic `min(100, max(class points) + (login ≠ residence ? 30 : 0))`
   (BR-14, A-14); profile, media from the enums; products `max(channel, product request ? 60 : 0)`
   (BR-17, A-15).
2. `R_KYC = Σ weight·points`, exact `BigDecimal` (BR-18).
3. `M_jur = residence ≠ bookingCentreCountry && policy.strictForNonResidents ? 1.25 : 1.00` (BR-21, A-18).
4. Critical fact → `100.00` (BR-23); else `min(100, (w1·R_KYC + w2·0)·M_jur)` rounded `HALF_UP` to
   scale 2, once (BR-22, A-19).
5. Track by `≥` thresholds (BR-24, A-20); non-profit floor Orange (BR-25, A-21).
6. `Explanation`: one `FactorContribution(factor, category, points, weight, contribution)` per
   factor, plus multiplier, or the critical facts (BR-26). A test recomputes the total from it.

`JurisdictionPolicy(jurisdiction, categoryWeights, w1, w2, multiplierStrict, thresholds,
strictForNonResidents)` is recorded with the evaluation (BR-27).

## 7. Ports (domain, public interfaces)

| Port | Methods |
|---|---|
| `RiskProfileRepository` | `Optional<RiskProfileSnapshot> findOwn(UUID tenantId, CustomerNumber)`; `Result<RiskProfileVersion, Concurrent> save(RiskProfileSnapshot, RecordedFact, List<RiskDomainEvent>)` — one transaction: profile row, history row, outbox rows |
| `BookingCentrePolicies` | `Optional<BookingCentrePolicy> forTenant(UUID tenantId)` — country (US, GB, DE: D-06) and `JurisdictionPolicy`; empty means the caller is not identified (fail closed) |
| `RiskProfileIdGenerator` | `RiskProfileId nextProfileId()`; `UUID nextEventId()` (H-07) |
| `ComplianceRiskViewReader`, `FrontOfficeStatusReader` | read the `query` schema, return views |
| `java.time.Clock` | injected (H-07) |

## 8. Events and delivery (H-06, H-08)

`sealed interface RiskDomainEvent permits CustomerRiskEvaluated, ComplianceDecisionRecorded`,
records, carrying `eventId`, `tenantId`, `CustomerNumber`, `version`, and the fields of spec §5. The
partition key is the internal profile id (never published outside). `ComplianceDecisionRecorded`
carries no justification (A-31). `toString` masks every risk field and the actor (BR-43).

Delivery, all in `adapters/events/`, no broker (ARGON's final decision; the Kafka starter is removed
from `pom.xml`, H-08):

1. `save` writes the outbox rows in the same transaction as the profile (no dual write).
2. `OutboxRelay` polls `command.outbox` every `app.outbox.relay.poll-interval` with
   `SELECT … WHERE status = 'PENDING' ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED`.
3. `PartitionedDispatcher`: 16 queues, one virtual thread each, partition `floorMod(hash(profileId),
   16)`, so one profile's events arrive in version order.
4. `RiskViewProjector` upserts both views
   `ON CONFLICT (tenant_id, customer_number) DO UPDATE … WHERE projection_version < EXCLUDED.projection_version`
   (idempotent, out-of-order safe), then marks the outbox row `DELIVERED`.
5. A projection failure is a `Result.failure`, not an exception: the row stays `PENDING`, its
   `attempts` count grows, and the relay retries it on the next poll. JDBC's
   `DataAccessException` is caught by its type only (no catch-all, H-01).

The only consumer in this increment is the projector, inside the compliance boundary (A-30).

## 9. Schema

`V1__command_schema.sql`:

```sql
CREATE SCHEMA command;

CREATE TABLE command.risk_profile (
    id                UUID PRIMARY KEY,                 -- domain-generated, never exposed (D-05)
    tenant_id         UUID          NOT NULL,           -- booking centre
    customer_number   VARCHAR(64) COLLATE "C" NOT NULL,
    evaluated_track   VARCHAR(10)   NOT NULL,
    decided_track     VARCHAR(10),
    effective_track   VARCHAR(10)   NOT NULL,
    restriction       VARCHAR(20)   NOT NULL,
    total_score       NUMERIC(5,2)  NOT NULL CHECK (total_score BETWEEN 0 AND 100),
    critical_fact     BOOLEAN       NOT NULL,
    evaluation        JSONB         NOT NULL,           -- inputs, policy, explanation
    latest_decision   JSONB,
    updated_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL,
    CONSTRAINT uq_risk_profile_tenant_number UNIQUE (tenant_id, customer_number),   -- BR-05
    CONSTRAINT ck_risk_profile_number CHECK (length(customer_number) > 0
                                             AND customer_number !~ '[\x01-\x1F\x7F-\x9F]')
);

CREATE TABLE command.risk_profile_history (           -- append-only (BR-29)
    profile_id   UUID         NOT NULL REFERENCES command.risk_profile (id),
    version      BIGINT       NOT NULL,
    fact_kind    VARCHAR(20)  NOT NULL CHECK (fact_kind IN ('EVALUATION', 'DECISION')),
    fact         JSONB        NOT NULL,
    actor_id     VARCHAR(255) NOT NULL,
    recorded_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (profile_id, version)
);
REVOKE UPDATE, DELETE ON command.risk_profile_history FROM PUBLIC;

CREATE TABLE command.outbox (
    event_id          UUID PRIMARY KEY,
    profile_id        UUID         NOT NULL,           -- partition key
    aggregate_version BIGINT       NOT NULL,
    event_type        VARCHAR(40)  NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED')),
    attempts          INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_outbox_pending ON command.outbox (created_at) WHERE status = 'PENDING';
```

`V2__query_schema.sql`:

```sql
CREATE SCHEMA query;

CREATE TABLE query.compliance_risk_view (
    tenant_id          UUID          NOT NULL,
    customer_number    VARCHAR(64) COLLATE "C" NOT NULL,
    projection_version BIGINT        NOT NULL,
    view               JSONB         NOT NULL,         -- every field of spec query 1
    projected_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (tenant_id, customer_number)
);

CREATE TABLE query.front_office_status (               -- no risk column exists (BR-40)
    tenant_id          UUID          NOT NULL,
    customer_number    VARCHAR(64) COLLATE "C" NOT NULL,
    projection_version BIGINT        NOT NULL,
    status             VARCHAR(30)   NOT NULL CHECK (status IN ('ACTIVE', 'VERIFICATION_PENDING')),
    projected_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (tenant_id, customer_number)
);
```

Concurrency (BR-30): the profile entity carries `@Version @Nullable Long version`; `null` inserts,
non-null updates `WHERE version = :v`. A duplicate `(tenant_id, customer_number)` insert and a
zero-row update both become `RiskProfileChangedConcurrently`. The version viewed (BR-33) is checked in
the aggregate against the loaded write-side version, never against the view.

## 10. Web edge

| Operation | Route | Success |
|---|---|---|
| Evaluate | `POST /api/risk-profiles/{customerNumber}/evaluations` | `200 {customerNumber, version}` |
| Decide | `POST /api/risk-profiles/{customerNumber}/decisions` | `200 {customerNumber, version}` |
| Compliance view | `GET /api/risk-profiles/{customerNumber}` | `200 ComplianceRiskView` |
| Front-office status | `GET /api/risk-profiles/{customerNumber}/front-office-status` | `200 {customerNumber, status, message}` |

Path variables are `String`, parsed after the gate. Refusals are RFC 7807 problems with constant
text and a constant `instance`: 401 not identified, 403 role, 400 input, 404 not found (also other
booking centre), 409 changed concurrently / since viewed, 422 decision rules (critical fact, already
frozen, already escalated, nothing to clear). Each resource folds one sealed refusal union with one
exhaustive `switch`. A front-office caller's refusals never name risk (BR-41).

## 11. Architecture rules added in stage 3

Module rules on top of the bootstrap set: `domain/` depends only on java, kernel, JSpecify; the
aggregate is package-private; use cases have one public `execute` or `ask`; read use cases do not
depend on `RiskProfileRepository` or `RiskDomainEvent`; only `adapters/projection` writes
`query.`, and nothing in `adapters/persistence` names `query.` (constant-pool check, ARGON's schema
guard); commands and queries are records carrying parsed types only.

## H-01 to H-08: resolution

H-01 `Result` and sealed errors (§4); H-02 one use case per operation, beans in config (§1); H-03
Spring Data JDBC and native SQL (§9); H-04 `RiskScore` on `BigDecimal` (§3); H-05 read use cases in
`domain/`, database-level CQRS (§1); H-06 outbox and relay with `SKIP LOCKED`, no catch-all (§8);
H-07 clock and id generator ports (§7); H-08 no broker, Kafka starter removed (§8).

## Open questions

- None that block stage 3. For the record: an unknown booking centre in the identity claims is
  treated as "caller not identified" (fail closed), which the specification covers by BR-01.

## Not doing

Quarantine and replay of a poisoned event per profile (ARGON's DLQ design) — the relay retries
forever and the `attempts` column makes a stuck row visible; a later increment adds quarantine.
Transaction monitoring, screening, policy editing, maker-checker, retention, React UI, Kafka.

Design written to `docs/adr/customer-risk-scoring-design.md`. Next: `@test-designer` turns it into
contracts and failing tests.

---

## Addendum 2026-11-07: relay connection pressure (state board, open design question)

Status: **candidate 2b confirmed by the product owner on 2026-11-08. No change to `src/main/` has been made yet;
it follows through stage 3 (failing tests) and stage 4. Stage 4 is done: candidate 2b is in `src/main` (2026-11-08).**
Evidence: the spike `src/test/java/com/gs5/kycaml/risk/adapters/RelayPressureSpike.java` (not a `*Test` or
`*IT`, so no Maven gate runs it; run it with `./mvnw -B test -Dtest=RelayPressureSpike`, about 6 minutes,
needs Docker). It runs the real migrations, the real `ViewProjector` and the real `OutboxRelay` (as
baseline) on PostgreSQL 17, through Hikari pools of 10 and of 3 connections.

### Problem, as measured on the shipped relay

`OutboxRelay.runOnce` holds its claim connection and the row locks for the whole batch while up to 16
workers each wait for a second connection. Measured on the shipped relay:

- Idle pool of 10: the relay takes **all 10 connections** (peak 10); the longest single connection hold is
  about 200 ms.
- With request traffic on the same pool the longest hold is **0.8–1.4 s** (pool 10) and **1.4–2.0 s** (pool 3).
- Pool-acquire timeouts of workers are **counted as failed attempts**: on a pool of 3 under load the
  baseline burnt **452–595 attempts in 12 s and one row reached `attempts = 10`**, i.e. it was parked and
  no longer claimed, although nothing was wrong with the event. This is a defect in its own right: a
  pool timeout must never count toward the poison threshold.

### How it was measured (and what it does not show)

Every candidate gets the same connection budget B = max(2, pool/2) (5 on a pool of 10, 2 on a pool of 3);
the baseline keeps its 1 + 16. Backlog: 2 000 events idle, 20 000 events for a fixed 12 s window under
load. Load: 2 × pool request threads, each writing a real profile through the real use case and keeping
its connection 10 ms more. Hikari connection timeout 250 ms (the minimum; the production default is 30 s).
Loaded rows were run twice; the two runs differ by up to a factor of two, so read the ranges, not the
digits. **Limits:** one developer machine with Docker on Windows; synthetic closed-loop load that keeps
the pool saturated, which is harsher than the expected onboarding traffic; one view JSON reused for all
seeded events; a "crash" is the server terminating A's connections and A's pool shutting down, not a
killed JVM; the lease is 3 s in the spike.

### Results

Idle backlog of 2 000 events (events per second, longest single connection hold):

| Candidate | pool 10 | pool 3 |
|---|---|---|
| 0 shipped | 880–1 100 ev/s, 200–250 ms, **peak 10 conns** | 800 ev/s, 170 ms |
| 1 bounded workers | 840–1 050 ev/s, 170–250 ms | **500 ev/s**, 210 ms |
| 2 lease, per-event projection | 1 000–1 260 ev/s, **13 ms** | 840–870 ev/s, 13 ms |
| 2b lease, one transaction per chunk | 1 030 ev/s, 73 ms | 720 ev/s, 139 ms |
| 3 partitioned claimers | 1 200–1 400 ev/s, 310–390 ms | 770–790 ev/s, 255–285 ms |

Same pool, request traffic on it, fixed 12 s window. Requests with no relay at all (`alone`) managed
522 req/s on a pool of 10 and 180 req/s on a pool of 3.

| Candidate | pool 10 relay | pool 10 requests | pool 3 relay | pool 3 requests | attempts burnt for nothing |
|---|---|---|---|---|---|
| 0 shipped | 107–166 ev/s | 500–512/s | 45–48 ev/s | 125/s | up to 595 (**one row parked**) |
| 1 bounded workers | 43–51 ev/s | 496–514/s | **4 ev/s**, claim held **15–17 s** | 125/s | 19–36 |
| 2 lease, per-event | 30–68 ev/s | 543–568/s | 13–23 ev/s | 178–188/s | 10–85 |
| **2b lease, chunk per transaction** | **420–443 ev/s** | **549–565/s**, p99 109–113 ms | **291–316 ev/s** | 147–150/s | **0** |
| 3 partitioned claimers | 797–883 ev/s | **403–428/s**, p99 167–200 ms, up to 16 failed | 341–423 ev/s | 122–123/s | 0 |
| 4 projection in the write transaction | n/a (no backlog) | **403/s** (−23 %), p99 254 ms, 45 failed | n/a | **118/s** (−35 %) | n/a |

Two relays at once (pool 10 each, 2 000 events): every candidate delivered every event; 0, 1, 2 and 3
recorded 1–10 harmless "gated" projections (a stale version arrived after a newer one and the version
gate ignored it); 2b recorded none.

Crash between claim and mark (A cut off while projecting, B takes over; 200 events): **no candidate lost
an event**. 0, 1 and 3 hand everything to B at once (the claim transaction rolls back; 0.6–0.7 s to
drain). 2 and 2b leave A's rows leased until the lease expires (3.1 s with a 3 s lease), then B takes
them. Candidate 4 is all-or-nothing: after a crash before commit there was no profile, no outbox row and
no view row for the customer.

One row that fails inside the database in the middle of a healthy batch (a CHECK added by the spike):
0, 1, 2, 2b and 3 delivered all healthy rows and kept the bad row `PENDING` with its attempts counted.
**Candidate 3 is only safe if the projector runs in a savepoint** (`PROPAGATION_NESTED`). With the
shipped projector joined to the claim transaction, PostgreSQL aborts the transaction, the following
statements fail, the whole batch rolls back, **no attempt is ever counted** and the claimer repeats the
same failing batch forever (5 exceptions in 5 rounds; in two of three runs 2–4 healthy rows of 20 stayed
undelivered). Candidate 4 rejects the command that carries the bad projection (accepted: false, nothing
left behind).

### Verdict per candidate

1. **Bounded workers: reject.** Fewer workers keep the same long claim transaction open for longer. It is
   worse than the baseline in every loaded measurement (claim held 15–17 s on a pool of 3, 4 ev/s).
2. **Lease with per-event projection: reject as specified.** It holds no connection while waiting
   (longest hold 13–64 ms) but every event re-queues for the pool, so under saturation it delivered
   13–68 ev/s. The idea is right, the granularity is wrong; see 2b.
3. **2b, lease and a chunk per transaction: recommended.** The lease is claimed in one short transaction;
   the claimed batch is split by profile (one profile stays in version order) into at most B chunks;
   each worker projects its chunk and marks it delivered in **one** short transaction on **one**
   connection, never waiting for a second. Under load it kept request throughput at the level of requests
   alone (549–565/s against 522/s), burnt no attempts, and delivered 420–443 ev/s on a pool of 10 and
   291–316 ev/s on a pool of 3, with the longest connection hold 90–255 ms. A pool timeout rolls the
   chunk back and counts nothing; the lease expires and the rows return.
4. **Partitioned claimers (candidate 3): runner-up.** Highest relay throughput under load (800–880 ev/s)
   but it keeps B connections busy for the whole batch, which cost requests about 20 % of their
   throughput plus some failures, it rolls back a whole batch on a pool timeout, and it is unsafe
   without savepoints (above). Choose it only if the relay must keep up with a sustained rate that 2b
   cannot deliver on a saturated pool.
5. **Projection in the write transaction (candidate 4): reject for this increment.** It removes the
   backlog, but costs 23–35 % of request throughput and raises p99, makes the command fail when a view
   cannot be written, puts the query schema into the command transaction (the projector is meant to be
   the only writer of `query`), and abandons the outbox decision of §8 (H-06). Revisit only if the
   product owner needs the view strongly consistent with the command, which the specification does not
   ask for.

### Recommendation (to apply at stage 3/4 after confirmation)

Adopt **2b**, with these conditions the spike made visible:

- `V3__outbox_lease.sql`: `ALTER TABLE command.outbox ADD COLUMN locked_until TIMESTAMPTZ, ADD COLUMN locked_by UUID`.
  Claim: one `UPDATE … WHERE event_id IN (SELECT … AND (locked_until IS NULL OR locked_until < now())
  ORDER BY created_at, aggregate_version LIMIT :batch FOR UPDATE SKIP LOCKED) RETURNING …`, in its own
  short transaction.
- A connection-pool failure is **not an attempt**. Only a projection failure of the event itself counts
  toward `MAX_ATTEMPTS` (10). The shipped relay violates this and parks healthy rows under load.
- `ViewProjector` must run in a **savepoint** (`PROPAGATION_NESTED`) so that one row that fails in the
  database does not abort the chunk. The spike's 2b and 3 used one; the shipped projector does not.
- The connection budget B (default max(2, pool/2)) and the lease duration are configuration. The lease
  must be much longer than the slowest chunk (a few hundred ms measured), e.g. 30 s, which is also the
  worst-case delay after a crash. A lease that expires mid-chunk is harmless (the version gate ignores
  the repeat) but wasteful.
- On a chunk failure release the lease at once (the spike let it expire).
- The architecture rules of §11 are unaffected: the relay stays in `adapters/events/` and the projector
  remains the only writer of `query`.

### What remains unproven

The numbers come from one saturating synthetic load on one machine. Before stage 4 fixes a batch size,
lease and budget, repeat the spike with the production pool size and a realistic request mix. It shows
the shape, that 2b avoids the pathologies and that 1 and 4 should not be chosen; it does not size the
configuration.

---

## Addendum 2026-11-08: stage 6 fixes (R-01 to R-20, decisions D-08 to D-12)

Source: the consolidated stage 5 and stage 6 list on the state board, and the product owner's
decisions D-08 to D-12 in the specification. This addendum replaces §10's description of how a body
is read, and adds to §9 and §8. Everything not named here stays as designed.

### A. The edge reads in a fixed order (R-01, R-02, R-05, D-08, D-09, D-12)

1. **Identify.** `ActorContextResolver` gets one final method the resources call, `identify()`: the
   identity source's caller (`resolve()`, the method tests override), kept only when
   `BookingCentres.jurisdictionOf(tenant)` is present. A caller of an unconfigured booking centre is
   therefore *not identified* (401) on all four routes, before anything else (BR-01 as amended).
   `EvaluateCustomerRiskError.BookingCentreUnknown` stays as defence in depth in the use case, and the
   resource maps it to the same 401 `caller-not-identified` problem; the 422 `booking-centre-unknown`
   answer disappears.
2. **Evaluate only: the role**, which needs no input.
3. **Read the body, bounded.** No resource binds `@RequestBody String` any more. A package-private
   `web/BoundedBody` reads the servlet input stream only after steps 1 (and 2): a declared
   `Content-Length` above the limit is refused without reading; otherwise at most limit + 1 bytes are
   read, so a chunked body cannot exceed it either. Limits are named constants: 64 KiB (65 536 bytes)
   for an evaluation, 16 KiB (16 384 bytes) for a decision. Too large → **413
   `request-too-large`**. An `IOException` is caught by its exact type and counts as not readable.
4. **Parse strictly into a tree.** A dedicated strict reader (not the application `JsonMapper`, and
   not the mapper that reads stored JSON) parses the bytes as UTF-8 into a JSON tree with duplicate
   key detection on. Anything that is not exactly one JSON object (absent body, empty body, array,
   `null`, scalar, malformed JSON, a duplicate key, trailing content) → **400 `request-not-readable`**.
5. **Read each field by its JSON type, never coerced.** From the object, each raw field is taken only
   when its node has the expected JSON type: string for text and enumerations, boolean for yes/no,
   object for a rated country, array for citizenships, integral number within `long` for the version
   viewed. A node of any other type is treated as absent, so the field's own refusal applies
   (`critical-fact-missing`, `product-request-missing`, `profile-class-not-recognised`, …), never a
   neighbouring one (R-07). Unknown fields stay ignored.
6. **Decide only: the role, from the kind (D-08).** The permission is chosen from the parsed kind, an
   unrecognised kind falling back to `FREEZE_ASSETS`; then `DecisionKindNotRecognised` for a caller who
   passed. Because the kind is read on its own (step 5), a mistyped other field no longer changes the
   permission (R-02).
7. Then the command's own parse and the use case, as before.

### B. New refusals and their problems

| Refusal | Command | Domain | Problem |
|---|---|---|---|
| Request too large (D-09) | both | none: decided at the edge | 413 `request-too-large` |
| Request not readable (D-12) | both | none: decided at the edge | 400 `request-not-readable` |
| Too many citizenships (D-10) | evaluate | `EvaluateCustomerRiskError.TooManyCitizenships`, after `NoCitizenship`; limit `MAX_CITIZENSHIPS = 10` | 400 `too-many-citizenships` |
| Version viewed not valid (D-12) | decide | `RecordComplianceDecisionError.VersionViewedNotValid`, judged after the justification and before `RiskProfileChangedSinceViewed`; valid means present and ≥ 1 | 400 `version-viewed-not-valid` |

The command keeps the version viewed as a not-yet-judged value, the same way it already carries the
justification, so the BR-37 order is decided in the use case.

### C. Customer number taken from the raw path (R-03)

Spring's path matching strips `;…` from a segment. The four resources take the customer number from
the raw request URI instead: the segment after `/api/risk-profiles/`, percent-decoded as UTF-8
(`UriUtils.decode`), so a literal `;` stays part of the number (BR-06: compared exactly). The route
mappings stay as they are; only the value comes from the raw segment. A segment Tomcat already
refuses (`%00`, `%2F`, `%5C`, invalid UTF-8) is still refused by Tomcat.

### D. Errors outside the four resources (R-08)

An `ErrorController` in `adapters/web` answers every container error with a constant RFC 7807
problem, `urn:problem-type:risk:request-not-handled`, the status taken from the servlet error status
(4xx or 5xx, anything else 404), constant detail and constant `instance` `/api/risk-profiles`. No
path, message, exception or trace. `server.error.include-path: never` is set as a second guard.

### E. Responses

Every response of the four resources carries `Cache-Control: no-store` (R-18).

### F. Confidential `toString` (R-11)

`CriticalFacts`, `RatedCountry`, `Explanation` and `CategoryContribution` override `toString` as
`Type[***]`, like the records around them.

### G. Relay (R-13, R-14)

- `ViewProjector` maps to `ProjectionFailure` only failures that belong to the event itself
  (`DataIntegrityViolationException`, unreadable JSON). A `TransientDataAccessException` (deadlock,
  lock or query timeout) is not a projection failure: the chunk is released like a pool failure and
  no attempt is counted.
- `markDelivered` and `countAttempt` add `AND locked_by = :owner`; a relay whose lease was taken over
  changes nothing.

### H. Schema and database rights (R-09, R-17, R-20)

- **V4** adds a `BEFORE TRUNCATE ... FOR EACH STATEMENT` trigger on `command.risk_profile_history`
  that refuses. The total-score `CHECK` of §9 is deferred: the score lives inside the evaluation
  document and is already capped by `RiskScore`; the deviation is recorded here instead.
- **Two roles (D-11).** Flyway runs as the schema owner: `spring.flyway.user: ${FLYWAY_USERNAME}`,
  `spring.flyway.password: ${FLYWAY_PASSWORD}`, no defaults. The service connects as
  `DATABASE_USERNAME`, passed to migrations as the Flyway placeholder `service_role`. **V5** grants
  that role `USAGE` on both schemas, `SELECT, INSERT, UPDATE` on the mutable tables, and only
  `SELECT, INSERT` on `command.risk_profile_history`; it owns nothing, so it cannot disable the
  append-only triggers.
- The ERROR log in `RiskProfileSqlAdapter` no longer prints the internal profile id (D-05).

### I. Build and text (R-12, R-15, R-16)

- `@NullMarked` on every package under `com.gs5.kycaml`; NullAway's `AnnotatedPackages` corrected to
  `com.gs5.kycaml`. An architecture test asserts every package is `@NullMarked`.
- The PostgreSQL-backed relay tests are `*IT` (Failsafe), so `./mvnw test` needs no Docker.
- The front-office message uses the en dash of BR-40: `Account active – no operational restrictions.`

### Not changed

R-19 (the 403 names the caller's own role) is kept. R-21 nits are optional.

---

## Addendum 2026-11-09: stage 6 re-review of the fix round (R-21 to R-29)

Source: the independent code and security re-reviews of `66a9642..0b4aabf`. The orchestrator confirmed
R-21 on a running server: an unidentified multipart POST with a 2 MB part is answered 413, not 401, so
the body is parsed before identification.

| R | Finding | Design |
|---|---|---|
| R-21 | Multipart parsing (DispatcherServlet `checkMultipart`, before any handler) reads and stores up to 10 MB before `identify()` | `spring.servlet.multipart.enabled: false`: no `MultipartResolver` exists. No route accepts multipart |
| R-22 | `FormContentFilter` reads a whole form body of a PUT/PATCH/DELETE, unbounded, before dispatch | `spring.mvc.formcontent.filter.enabled: false`: no `FormContentFilter` bean |
| R-23 | `ViewProjector` lets every non-integrity `DataAccessException` escape, so a persistent failure (lost grant, bad SQL) is retried forever, silently, never counted or parked | Amends G: only `TransientDataAccessException` escapes `ViewProjector` (with `TransactionException` in the relay); any other `DataAccessException` is the event's `ProjectionFailure` and counts an attempt. `OutboxRelay` logs one WARN per released chunk, naming only the exception type: `Outbox chunk released without counting an attempt (<SimpleName>)` |
| R-24 | The `service_role` placeholder is quoted but not escaped | `RiskDatabaseConfiguration` sets it only when the user name matches `^[a-z_][a-z0-9_]{0,62}$`; otherwise it stays unset and Flyway refuses to migrate |
| R-25 | `markDelivered`'s `locked_by` guard is untested | test only |
| R-26 | the raw customer number is pinned on evaluate only | test only: view, front-office status and decision |
| R-27 | `TooManyCitizenships` has no fast test and no order against `CountryInvalid` | test only: catalogue order rows 9, 10, 11 |
| R-28 | the owner TRUNCATE test accepts any failure | test only: SQLState `P0001` from the append-only function |
| R-29 | the production role wiring (`spring.flyway.user` ≠ datasource user through `RiskDatabaseConfiguration`) is never exercised | test only: a Spring context with a non-owner datasource user |

**Deferred, recorded:** a fail-closed migration check that the service role is neither the migration
owner nor a superuser (SR-03a). Every Spring IT today runs Flyway and the service as the same container
superuser, so the check needs the IT setup moved to a separate role first. Also deferred:
`server.tomcat.max-swallow-size` and a whole-body read deadline for identified callers (SR-04, low), the
`Expect: 100-continue` timing, and default privileges for tables added after V5.

### Recorded after stage 4 (round 3): SQL exception translation

R-13 and R-23 assume a PostgreSQL lock timeout (SQLState `55P03`) arrives as a
`TransientDataAccessException`. With Spring JDBC 7's default `SQLExceptionSubclassTranslator` it does
not: it becomes `UncategorizedSQLException` (non-transient), which R-23 counts as an attempt. The
deliberately empty `src/main/resources/sql-error-codes.xml` switches every `JdbcTemplate`/`JdbcClient`
to `SQLErrorCodeSQLExceptionTranslator` with Spring's bundled PostgreSQL codes, where `55P03` is
`CannotAcquireLockException`. Proven by removal: without the file (and its stale copy in
`target/classes`), `OutboxTransientFailureIT` and `OutboxFailureVisibilityIT.shouldLogATransientRelease`
fail. Side effects accepted: SQLState `21000` maps to `DuplicateKeyException` (a subclass of what it was);
the first translated exception per data source reads database metadata.

`ViewProjector` lists the non-transient branches of `DataAccessException` explicitly (no catch-all,
no rethrow); a new direct subclass in a later Spring release would escape as if transient, and the relay's
WARN line would show it.

---

## Addendum 2026-11-09 (2): after the approved re-review (R-30)

Both re-reviews of `0b4aabf..836287b` returned `[APPROVED]`. One warning both raised is fixed:

- **R-30 (CR W-1/W-2, SR-30).** `RiskDatabaseConfiguration` always removes any `service_role`
  placeholder it finds, then sets it only for a user name that matches the plain-identifier pattern and
  is not `public`, `none`, `current_user`, `session_user`, `current_role`, `user`, nor starts with `pg_`.
  In PostgreSQL a quoted `"public"` still means PUBLIC, so V5 would otherwise grant every role in the
  cluster, and V5 never runs again to undo it.
- `ViewProjector`'s Javadoc names `TransactionException` (and non-database runtime exceptions) as also
  escaping, as this design already says.

Recorded, not fixed (reliability, not correctness of a successful run): on the first translated
exception per data source, Spring reads database metadata through a second connection; if that fails
(pool exhausted), that translation falls back and a lock timeout can be counted once (CR W-3). Also
recorded: never enable `spring.mvc.log-request-details` with DEBUG on `org.springframework.web`
(it would read and log form bodies before identification; SR I-2). Tomcat's `100 Continue` timing and
`max-swallow-size` stay deferred (SR I-1).

- **R-31 (security check of R-30, SR-31).** Flyway 12 resolves placeholder names case-insensitively
  (`PlaceholderReplacingReader$CaseInsensitiveMap`, default-locale `toLowerCase`). The customizer must
  remove every key that Flyway could read as `service_role`: equal ignoring case, or lower-casing to
  `service_role` in the root locale or the default locale. With a valid user, the service's own name is
  then the only such entry.
- **R-32 (security check of R-31, SR-32).** Cleaning the placeholder map is not enough while the
  placeholder syntax stays configurable: a suffix of `_role}` makes Flyway read `${service_role}` in V5 as
  the name `service`. The customizer also pins `placeholderPrefix("${")`, `placeholderSuffix("}")` and
  `placeholderReplacement(true)`, whatever the configuration says and whether or not the user is accepted.
  With replacement off, V5 would fail on a role literally named `${service_role}` (SR-33, fails closed);
  pinning it on removes that path too.

### Closed (2026-11-09): exhaustive review of every `spring.flyway.*` setting

After R-30, R-31 and R-32, one exhaustive security pass classified all 63 `spring.flyway.*` settings and
the code paths into Flyway: `[APPROVED]`. None gives its holder more than the schema owner's credentials
already give. The placeholder path is closed (map cleaned in every spelling, syntax pinned, absent name
fails closed). Recorded as deployment controls in AGENTS.md: distinct Flyway and service users, no
session-role switching, no extra migration or callback locations, no `init-sqls`. Deferred: a startup
self-check that the session runs as the validated, non-owning, non-superuser service role (needs the
`@ServiceConnection` ITs on two roles first; same prerequisite as SR-03a).

---

## Addendum 2026-11-28: refusals name their field (OAS-01), one precision for moments (OAS-02), a contract test

Source: the state board, section "2026-11-27: build wrapper fixed, full gate re-run, API contract
drafted" (OAS-01, OAS-02) and its "Next" item 2. No new refusal, no new business rule, no migration.
Everything not named here stays as designed.

### A. OAS-01: three refusals name their field (BR-08, BR-09, BR-12)

**Cause.** The domain already carries the field (`EvaluateCustomerRiskError.CountryInvalid(CountryField)`,
`GeographicClassInvalid(CountryField)`, `CriticalFactMissing(CriticalFactField)`);
`EvaluateCustomerRiskResource.refused` matches them with `_` and drops it.

**Decision.** The problem gets one RFC 9457 extension member, **`field`**, at the top level beside `type`.
Its value is the name of the request member the refusal is about, spelled as the request schema spells it.

| Problem `type` | Rule | `field` | Domain value → `field` |
|---|---|---|---|
| `country-invalid` | BR-08, row 9 | required | `RESIDENCE` → `residence`, `CITIZENSHIP` → `citizenships`, `BIRTH` → `birth`, `LOGIN` → `loginCountry` |
| `geographic-class-invalid` | BR-09, row 12 | required | `RESIDENCE` → `residence`, `CITIZENSHIP` → `citizenships`, `BIRTH` → `birth` |
| `critical-fact-missing` | BR-12, row 16 | required | `SANCTIONS_MATCH` → `sanctionsMatch`, `TERRORIST_LINK` → `terroristLink`, `FORGED_IDENTITY` → `forgedIdentity` |
| every other problem of all four operations | — | absent | — |

1. **One value.** The first failure in catalogue order is the one refusal (A-27), so `field` names one
   member: residence and login both invalid → `residence`.
2. **A citizenship is named `citizenships`**, the member that holds them. BR-08 asks which of four
   countries (residence, citizenship, birth, login), not which of several citizenships, so no position
   is added (Q-1). A citizenship that is not an object is already `country-invalid` and is named the same.
3. **BR-09 "naming the country"** is read as BR-08 spells it: which rated country, never the code sent
   (Q-2). `loginCountry` never comes with `geographic-class-invalid`: the login country has no class, and
   the parse never builds `GeographicClassInvalid(LOGIN)`.
4. `detail` stays the constant sentence of its `type` and `instance` the constant of the operation (§10).
5. **Stage 4, `adapters/web` only.** `ProblemResponses.of` gains an overload with `String field` that calls
   `ProblemDetail.setProperty("field", field)`. Boot 4.1.2 registers `ProblemDetailJacksonMixin` on the
   application `JsonMapper` (`JacksonAutoConfiguration.JsonProblemDetailsConfiguration`, only
   `@ConditionalOnClass(ProblemDetail)`), so the member is written top level, with no `properties` member.
   The resource maps each enum with one exhaustive `switch` (no `default`); its results are the same
   constants `RequestBodies` reads the members by, one constant per member. The value never comes from the
   request (not the key as sent, not `name().toLowerCase()`). The domain does not change.

| Limit on a refusal | Why it holds |
|---|---|
| BR-41 | Only the onboarding system reaches the evaluation's input; every other role is refused 403 before the body is read (BR-02, BR-03). The value names an input, never its content: not the code, not the class, not whether a critical fact is yes. `critical-fact-missing` + `sanctionsMatch` says the answer is missing or not a boolean, nothing about a match |
| BR-43 | Nothing new is logged (`adapters/web` has no logger). A member name is none of the data BR-43 lists |
| §10 | `detail` and `instance` constant; `field` is one of seven server constants chosen by a `switch`; nothing sent is echoed |

**Tests (stage 3),** in `RiskApiHardeningIT` (real Tomcat), each from a valid E3 body changed in one place:
1. Ten cases, one per value in the table (the country `"QQ"`, not an ISO code; the class `"NO_SUCH_CLASS"`;
   the critical fact `0`): 400, the `type`, `$.field` equal to the value, no `$.properties`, `detail` and
   `instance` unchanged, and the body contains neither `QQ` nor `NO_SUCH_CLASS`.
2. Residence and login both `"QQ"`: `field` is `residence`.
3. A front-office caller sending an invalid country: 403, no `field`.
4. No other problem has `field`: the contract test (C) checks it on every operation through closed schemas.

### B. OAS-02: one precision for every recorded moment

**Cause.** Both write use cases take `now = clock.instant()`; the bean is `Clock.systemUTC()`
(`RiskModuleConfiguration`), with nanoseconds on Linux. `RiskProfileSqlAdapter.save` writes that `Instant`
in one transaction as JSON text with all nine digits, and as `TIMESTAMPTZ`, microseconds, which pgjdbc
42.7.14 rounds half-up (`TimestampUtils.nanosExceed499`: +1 µs when `nanos % 1000 > 499`, then truncates).
`findOwn` reads `evaluated_at` back from the column, and the decision's view is built from that snapshot
(`OutboxDocuments.view` → `snapshot.evaluatedAt()`): `…53.552410932Z` at version 1, `…53.552411Z` at 2.

**Every moment the same cause affects.**

| Moment | JSON copies (exact) | Column copies (rounded) | Effect today |
|---|---|---|---|
| Evaluation | outbox `payload.occurredAt`, outbox `view.evaluatedAt` | `risk_profile.evaluated_at` and `updated_at`, history `recorded_at`, outbox `created_at` | OAS-02: the first decision after an evaluation changes the view's `evaluatedAt`; later decisions keep the rounded value |
| Decision | `risk_profile.latest_decision.decidedAt`, history `fact.decision.decidedAt`, outbox `payload.occurredAt` and `view.latestDecision.decidedAt` | `risk_profile.updated_at`, history `recorded_at`, outbox `created_at` | The view is stable (`decidedAt` comes back from JSON), but a history row's `recorded_at` differs from its own `fact`, and an outbox row's `created_at` from its payload |
| Either, rounded up | — | — | A stored moment can be later than the clock reading: `2026-12-31T23:59:59.999999700Z` is stored as `2027-01-01T00:00:00Z` |

Not affected: `projected_at`, `delivered_at`, `locked_until` (columns only, never served, never compared
with a JSON copy) and the relay's order (`created_at, aggregate_version`: rounding is monotone).

**Fix: the aggregate records moments to the microsecond.**
1. `CustomerRiskProfile.create`, `reevaluate` and `decide` truncate the moment they are given, once, first:
   `now.truncatedTo(RECORDED_PRECISION)`, with `static final ChronoUnit RECORDED_PRECISION = ChronoUnit.MICROS`
   (package-private). Every copy is then the truncated value: `evaluatedAt`, `updatedAt`,
   `EvaluationFact.recordedAt`, `DecisionRecord.decidedAt`, both events' `occurredAt`.
2. Truncation, not rounding: never later than the reading; `Instant.truncatedTo` is the JDK's (§0.7).
   `nanos % 1000 == 0`, so PostgreSQL stores exactly what the JSON says and pgjdbc never rounds.
3. The `Clock` bean, the adapters and the schema do not change. Nothing stored needs migrating (corrected
   2026-11-28: an identity source now exists, so profiles can exist): the columns already hold microseconds,
   and truncation only changes the moments of new facts. An older profile whose JSON copy has nanoseconds keeps
   showing the rounded column value; no invariant of this addendum depends on it.

Not the `Clock` bean (`Clock.tick(..., 1 µs)`): the domain tests and `RiskDatabaseSupport.evaluateAt` /
`decideAt` give use cases their own `Clock`, so no test could see that fix, and the defect would show or
not by the host clock's resolution. Not the adapter: truncating the columns leaves the JSON copies
different, and truncating every copy repeats the fix in four documents. The aggregate is where all copies
come from.

**Invariants.** Clock readings: `T1 = 2026-11-27T21:52:53.552410932Z` (rounds up today),
`T2 = 2026-11-27T21:53:23.811650417Z` (rounds down), `T3 = 2026-11-27T21:53:24.000000500Z`,
`T4 = 2026-12-31T23:59:59.999999700Z`. R(T) = `T.truncatedTo(MICROS)`: `…53.552410Z`, `…23.811650Z`,
`…21:53:24Z`, `2026-12-31T23:59:59.999999Z`. Steps: v1 evaluate E3 at T1; v2 escalate (L2, viewed 1) at
T2; v3 freeze (viewed 2) at T3; v4 re-evaluate E3 at T4; relay after each.

| # | Invariant |
|---|---|
| I-1 | The view's `evaluatedAt` is R(T1) at v1, v2 and v3, and R(T4) at v4 (OAS-02) |
| I-2 | Every recorded moment is R(its reading): never later, never 1 µs or more earlier |
| I-3 | One evaluation, one value: for v1 and v4, `risk_profile.evaluated_at` (while current), history `recorded_at`, outbox `created_at`, `payload.occurredAt`, `view.evaluatedAt`, and the query view's `evaluatedAt` are equal |
| I-4 | One decision, one value: for v2 and v3, `risk_profile.updated_at` (while current) and `latest_decision.decidedAt`, history `recorded_at` and `fact.decision.decidedAt`, outbox `created_at`, `payload.occurredAt` and `view.latestDecision.decidedAt`, and the query view's `latestDecision.decidedAt` are equal |
| I-5 | After v2 and v3, `evaluated_at` and the view's `evaluatedAt` equal the `occurredAt` of the v1 `CustomerRiskEvaluated` |
| I-6 | With T4, nothing is recorded in 2027 |

**Tests (stage 3).**
1. Unit, Surefire: `EvaluateCustomerRiskUseCaseTest`, `RecordComplianceDecisionUseCaseTest` with `Clock.fixed`
   at the readings and `InMemoryRiskProfileRepository` (`recordedFacts`, `announcedEvents`): I-2 and I-6 on
   the snapshot, the fact and the event; after a decision `evaluatedAt` is still R(T1). Expected values are
   literals (`Instant.parse("2026-11-27T21:52:53.552410Z")`), so no stub is needed.
2. IT: a new `RecordedMomentIT extends RiskDatabaseSupport`, the steps above with `evaluateAt` / `decideAt`
   and `relay(10).runOnce()`, I-1 to I-6 read with `JdbcClient` (columns, `->>` members) and the view reader.

Today 1 fails (nine digits) and 2 fails at I-1 (v2), I-2, I-3, I-4, I-6. The contract types both moments as
`Moment`, at most six fraction digits (D.5), so the contract test also sees a regression.

### C. A contract test

**Purpose.** Fail the build when the service and `openapi/customer-risk-scoring.yaml` disagree either way:
the service answers what the contract does not describe, or the contract lists an answer never given.

**Library.** `com.networknt:json-schema-validator:3.0.9`, scope `test`; and
`tools.jackson.dataformat:jackson-dataformat-yaml`, scope `test`, no version (Boot 4.1.2's `jackson-bom`:
3.1.7), declared because the test imports `YAMLMapper`.

| Check | Result |
|---|---|
| Exists, old enough | `maven-metadata.xml` fetched with curl from `repo.maven.apache.org/maven2` on 2026-11-28: `latest` and `release` 3.0.9; its POM `Last-Modified: Tue, 03 Nov 2026 20:15:20 GMT` (25 days) |
| Jackson 3 | 3.x depends on `tools.jackson.core:jackson-databind` and `tools.jackson.dataformat:jackson-dataformat-yaml` (3.0.9 POM: 3.1.8). 2.x and 1.x need `com.fasterxml.jackson.core:jackson-databind` (2.0.8 POM: 2.22.1; 1.5.9 POM: 2.18.3), which the repository bans: rejected |
| Under Boot 4.1.2 | Both Jackson artifacts resolve to 3.1.7 (BOM). The test classpath gains only `json-schema-validator` 3.0.9, `com.ethlo.time:itu` 1.17.0, `jackson-dataformat-yaml` 3.1.7 and `org.snakeyaml:snakeyaml-engine` 3.0.1; no Jackson 2 databind |
| OpenAPI 3.1, JSON Schema 2020-12 | `Dialects.getOpenApi31()`. A scratch spike outside the repository (JDK 25; this contract, and a copy with D.3 and D.4 applied) loaded the YAML, resolved `#/components/schemas/…` fragments, applied `unevaluatedProperties` through `allOf` / `$ref`, `oneOf`, and asserted `date-time`: 32 of 32 expectations met, including that today's contract accepts an added member |

No gate checks imports in `src/test` (Checkstyle runs on `src/main`; every ArchUnit suite uses
`DoNotIncludeTests`); the test still imports only `tools.jackson.*` and `com.networknt.schema.*`.
Copilot-instructions §0.1 forbids adding a dependency to make a first idea work: this is the design
decision it asks for. Nothing in `pom.xml` validates JSON Schema 2020-12, and hand-writing `oneOf`,
`unevaluatedProperties` and formats is what §0.7 rejects.

**Where.** `pom.xml`, test block after `archunit-junit5`, version in a property
`json-schema-validator.version`, with a comment that 3.x is the Jackson 3 line. Added by `@test-designer`
in stage 3 (the RED gate needs `test-compile`); see E.

**The test:** `src/test/java/com/gs5/kycaml/risk/adapters/web/RiskApiContractIT.java`, with a package-private
`OpenApiContract` beside it.
1. **Service.** `@SpringBootTest(webEnvironment = RANDOM_PORT)`, PostgreSQL 17 by Testcontainers
   `@ServiceConnection`, `app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE`,
   `app.outbox.relay.poll-interval=PT1H`. Requests through `java.net.http.HttpClient` to the real Tomcat,
   as `RiskApiAdversarialIT`. Views are filled by `OutboxRelay.runOnce()` until it returns 0.
2. **Caller.** A `@Primary ActorContextResolver` subclass overriding `resolve()` from a static volatile
   `Optional<AuthenticatedCaller>` set per exchange, empty for 401 (as `RiskApiFlowIT`). `identify()` is not
   overridden, so the booking-centre check stays real.
3. **Concurrent change, deterministic.** A `@Primary RiskProfileRepository` wraps the real
   `RiskProfileSqlAdapter`. Armed for one customer number, `findOwn` returns what the adapter read and then
   raises that profile's `version` by one with a SQL `UPDATE`; the use case's guarded update changes nothing
   and the real path answers 409 `risk-profile-changed-concurrently`. Disarmed, it only delegates (the same
   idea as `InMemoryRiskProfileRepository`'s one-shot interference).
4. **Contract.** Read `openapi/customer-risk-scoring.yaml` from the module directory (Failsafe's working
   directory), navigate it with `YAMLMapper`, validate with
   `SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31(), b -> b.schemas(Map.of(IRI, text))
   .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()))`, `IRI`
   ending in `.yaml` (the registry reads YAML by suffix). Operations are found by `operationId` and URLs built
   from their path template, so a route renamed on one side only gets 404 `request-not-handled`, which no
   operation lists. Response schemas are `$ref`s to `#/components/schemas/<Name>` (all are today), loaded as
   `IRI#/components/schemas/<Name>`; an inline one fails the test by name.

**Exchanges** (E1, E3, E7 are the spec's examples; DE booking centre):

| Operation | Status | How (each listed problem `type` at least once) |
|---|---|---|
| `evaluateCustomerRisk` | 200 | onboarding system, E3, new number |
| | 400 | `request-not-readable` (`[]`); `customer-number-too-long` (65 code points); `customer-number-control-character` (`%7F`); `profile-class-not-recognised`; `country-invalid` ×4, `geographic-class-invalid` ×3, `critical-fact-missing` ×3 (A); `no-citizenship` (`[]`); `too-many-citizenships` (11); `channel-not-recognised`; `product-request-missing` (`0`); `adverse-media-class-not-recognised` |
| | 401 · 403 · 409 · 413 | no caller · front office · re-evaluation, repository armed · 65 537 bytes |
| `recordComplianceDecision` | 200 | L2 freezes v1 |
| | 400 | `request-not-readable`; `decision-kind-not-recognised` (L2, kind `X`); `justification-missing`, `-too-short` (19), `-too-long` (2 001), `-control-character` (TAB); `version-viewed-not-valid` (`1.9`) |
| | 401 · 403 · 404 | no caller · L1 freezes · unknown number |
| | 409 | `risk-profile-changed-since-viewed` (viewed 7); `risk-profile-changed-concurrently` (armed) |
| | 413 · 422 | 16 385 bytes · `critical-fact-in-force` (clear E7), `already-frozen`, `already-escalated`, `nothing-to-clear` |
| `getComplianceRiskView` | 200 | E3 at v1 (no decision); E3 after a freeze; E7 |
| | 401 · 403 · 404 | no caller · front office · unknown number |
| `getFrontOfficeStatus` | 200 | front office: E1 (`ACTIVE`), E3 (`VERIFICATION_PENDING`) |
| | 401 · 404 | no caller · unknown number |

**Each exchange fails the test, naming operation, status and the library's messages, when:**
1. the status is not a key of the operation's `responses`;
2. the media type (without parameters) is not a key of that response's `content`;
3. a header the response declares `required: true` is missing or fails its schema (`Cache-Control: no-store`);
4. the body fails the response schema. With the closed schemas of D: a member added
   (`unevaluatedProperties: false`), removed (`required`), retyped (`type`, `enum`, `const`, `format`,
   `pattern`), a `type` this operation and status do not list, a `field` on the wrong problem;
5. in a 200 compliance view, a number at a member the contract types `integer` (`points`, `version`,
   `versionTaken`) is not an integer token, or any other number is not a decimal with exactly two fraction
   digits (read with `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`, `scale() == 2`). JSON Schema
   cannot tell `51.0` from `51.00`; the contract says two places.

**After all exchanges** the observed (operation, status, `type`) set must equal the contract's (for 200:
operation and status), so the contract cannot keep a dead entry. The only exception is a named set
`NOT_REACHABLE_OVER_HTTP`, each entry with its reason. Expected entry: `evaluateCustomerRisk` 400
`customer-number-missing` (an empty segment does not match `{customerNumber}`); stage 3 confirms it with
`//evaluations` and `/;/evaluations` and records what they answer.

**Self-checks, before the exchanges,** because a validator that accepts everything is green while checking
nothing (the ArchUnit 1.4.0 lesson in AGENTS.md):
1. the contract has exactly the four `operationId`s;
2. every response `example` and `examples.*.value` validates against its response schema;
3. the loaded schemas refuse: `yellowAfterFreeze` plus a member `id`; it without `version`; it with
   `totalScore` as a string; it with a nine-digit `evaluatedAt`; `country-invalid` without `field`;
   `no-citizenship` with one. The status check refuses 500 for `getFrontOfficeStatus`.

Not covered: container errors and Tomcat's own 400s (prose only in the contract); request schemas stay
open, but every success body the test sends validates against its request schema first.

At stage 3 it fails on the three `field` types and, on a clock with sub-microsecond digits, on `Moment`;
every other exchange passes, which is right: the contract describes what runs.

### D. The change to `openapi/customer-risk-scoring.yaml`

1. `info.version` 0.1.0 → 0.2.0.
2. `info.description`, Refusals: "Three refusals of the evaluation also carry `field`, the request member
   they are about: `country-invalid`, `geographic-class-invalid` and `critical-fact-missing` (BR-08, BR-09,
   BR-12). No other problem has it." Responses: "Moments are UTC, ISO 8601, to the microsecond at most. A
   response has exactly the members listed here."
3. Replace `EvaluationInputProblem` (the evaluate 400 keeps its `$ref`):
   ```yaml
   EvaluationInputProblem:
     description: Every 400 of the evaluation; `type` decides which branch applies.
     oneOf:
       - $ref: '#/components/schemas/EvaluationGeneralInputProblem'
       - $ref: '#/components/schemas/CountryInvalidProblem'
       - $ref: '#/components/schemas/GeographicClassInvalidProblem'
       - $ref: '#/components/schemas/CriticalFactMissingProblem'
   EvaluationGeneralInputProblem:
     description: A 400 of the evaluation whose `type` alone says what is wrong. It has no `field`.
     allOf:
       - $ref: '#/components/schemas/Problem'
     properties:
       type:
         enum:   # today's ten other codes
           - urn:problem-type:risk:request-not-readable
           - urn:problem-type:risk:customer-number-missing
           - urn:problem-type:risk:customer-number-too-long
           - urn:problem-type:risk:customer-number-control-character
           - urn:problem-type:risk:profile-class-not-recognised
           - urn:problem-type:risk:no-citizenship
           - urn:problem-type:risk:too-many-citizenships
           - urn:problem-type:risk:channel-not-recognised
           - urn:problem-type:risk:product-request-missing
           - urn:problem-type:risk:adverse-media-class-not-recognised
       status:
         const: 400
     unevaluatedProperties: false
   CountryInvalidProblem:
     description: BR-08. `field` names the request member whose country is missing or not valid.
     allOf:
       - $ref: '#/components/schemas/Problem'
     required: [field]
     properties:
       type:
         const: urn:problem-type:risk:country-invalid
       status:
         const: 400
       field:
         type: string
         enum: [residence, citizenships, birth, loginCountry]
     unevaluatedProperties: false
   ```
   `GeographicClassInvalidProblem` (BR-09) and `CriticalFactMissingProblem` (BR-12) are the same with
   `type` `urn:problem-type:risk:geographic-class-invalid`, `field` enum `[residence, citizenships, birth]`,
   and `urn:problem-type:risk:critical-fact-missing`, `[sanctionsMatch, terroristLink, forgedIdentity]`.
4. `unevaluatedProperties: false` at the top level of every other response schema: `RiskVersionResponse`,
   `ComplianceRiskView`, `JurisdictionPolicy`, `Explanation`, `CategoryContribution`, `DecisionRecord`,
   `FrontOfficeStatusView`, `CallerNotIdentifiedProblem`, `PermissionDeniedProblem`, `CustomerNotFoundProblem`,
   `RequestTooLargeProblem`, `EvaluationConflictProblem`, `DecisionInputProblem`, `DecisionConflictProblem`,
   `DecisionRuleProblem`. `Problem` stays open (closing it would refuse `field` under `allOf`); request
   schemas stay open (unknown fields are ignored).
5. A new schema, used through `allOf` by `ComplianceRiskView.evaluatedAt` and `DecisionRecord.decidedAt`:
   ```yaml
   Moment:
     type: string
     format: date-time
     pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$'
     description: A UTC moment, to the microsecond at most.
   ```
6. Evaluate 400: the description adds "`country-invalid`, `geographic-class-invalid` and
   `critical-fact-missing` name the member in `field`"; example `countryInvalid` gets `field: loginCountry`,
   `criticalFactMissing` gets `field: terroristLink`.
7. View examples to microseconds: `evaluatedAt` `2026-11-27T21:52:53.552410Z` and
   `2026-11-27T21:52:53.625363Z`, `decidedAt` `2026-11-27T21:52:53.811650Z`.

### E. Who changes what in this round

| Stage | Agent | Writes |
|---|---|---|
| 3 | `@test-designer` | `src/test/` (A, B, C); `openapi/customer-risk-scoring.yaml` (D: the contract the test executes); `pom.xml`, only the two test-scoped dependencies and the property of C. The last two are outside its usual list and are named here for that reason |
| 4 | `@developer` | `src/main/` only: `EvaluateCustomerRiskResource`, `ProblemResponses`, `RequestBodies` (A.5); `CustomerRiskProfile` (B). `openapi/` and `pom.xml` are read-only, like `src/test/` |
| — | orchestrator | `check-write-lock.sh green` watches `src/test` and `docs` only: check `git diff <stage-3 commit> -- openapi pom.xml` is empty by hand, and that the stage 3 `pom.xml` diff is exactly C |

AGENTS.md says a change of a field changes the contract in the same commit. With a contract test, that is
the stage 3 commit, together with the test that fails for it.

RED gate of this round: `./mvnw test` fails on B.1; `./mvnw verify` also fails on A, B.2 and C (the three
`field` types).

### Open questions for the product owner

- **Q-1.** Should a refusal about a citizenship also say which one (its position in the list)? BR-08 asks
  which of the four countries only, so the design names `citizenships`. A position would be a new member
  next to `field`, not a change of it.
- **Q-2.** BR-09 says "naming the country". The design reads it as BR-08 does, which rated country
  (residence, citizenship, birth), never the code sent, which would echo personal input (§6 of the spec,
  §10 here). Confirm.

## Addendum 2026-11-09 (3): identity source (D-13 to D-16)

**Mechanism.** Spring Security OAuth2 Resource Server with JWT (`spring-boot-starter-security` and the
OAuth2 resource-server starter). Stateless: no session, no CSRF (bearer tokens only, no cookies), no form
login, no HTTP Basic. Every request is let through the filter chain (`permitAll`): the decision stays in
`OperationGate`, so the refusal order and the constant problems are unchanged. A request **without** a
token reaches the resources as unauthenticated and is refused there as `caller-not-identified`. A
request **with** an invalid token is refused by the resource-server filter; its `AuthenticationEntryPoint`
and `AccessDeniedHandler` answer the same constant 401 problem (`urn:problem-type:risk:caller-not-identified`,
`Cache-Control: no-store`, a `WWW-Authenticate: Bearer` header without error description). Nothing reads the
body: the bearer token is taken from the `Authorization` header only; form-parameter and query-parameter
tokens are explicitly disabled.

**Token validation.** Signature (RS256 only) against the issuer's keys; `exp` and `nbf` with Spring's
default clock skew; `iss` equal to the configured issuer; `aud` containing the configured audience. Configuration
(Boot properties, no defaults for the security-relevant ones): `spring.security.oauth2.resourceserver.jwt.issuer-uri`
(`ISSUER_URI`, for Entra `https://login.microsoftonline.com/<tenant>/v2.0`),
`spring.security.oauth2.resourceserver.jwt.audiences` (`TOKEN_AUDIENCE`, the app's client id or App ID URI), and
`spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (`JWK_SET_URI`, for Entra
`https://login.microsoftonline.com/<tenant>/discovery/v2.0/keys`). The issuer is validated, never used for
discovery at startup. The production path is used in tests too: `IdentityIT` serves a JWK set from a
local HTTP server with a key generated at test time. `src/test/resources/config/application.yml` gives every
other Spring test a test issuer, audience and an unused JWK set URI; they never send a token, because they
override `resolve()`.

**Claims → `AuthenticatedCaller`** (D-14), names in `app.identity.*`:

| Property | Default | Entra ID setup |
|---|---|---|
| `app.identity.actor-claim` | `oid` | `oid`: the user's or service principal's object id, stable across apps |
| `app.identity.role-claim` | `roles` | app roles; each app role's *value* is exactly an `ActorRole` name |
| `app.identity.booking-centre-claim` | `booking_centre` | an optional claim from a directory extension attribute, renamed in the token configuration |

The claim value must have the expected JSON type: the actor claim a string; the role claim a string or an
array of strings; the booking-centre claim a string. A role value that is not exactly one `ActorRole` name
refuses the token (D-23, corrected 2026-11-28; this section first said such roles are ignored), and exactly one
`ActorRole` must be named (D-15), else no caller. The booking centre must be a canonical UUID
(`ActorContext.parse`) and in scope (`identify()`, D-16). Any other outcome is `Optional.empty()`, i.e. 401.

**Where.** `ActorContextResolver.resolve()` reads the `JwtAuthenticationToken` from the security context
(the tests' `@Primary` override of `resolve()` keeps working). A `SecurityFilterChain` bean and the claim
mapping live in `risk/adapters/web` (or `risk/adapters/config` for the chain); no Spring Security type reaches
`domain/`.

**Not doing now.** mTLS for the onboarding system, token introspection, per-request revocation checks.

### Recorded after stage 4 (identity)

- **HTTP firewall.** Spring Security's `StrictHttpFirewall` refuses a literal `;` and encoded CR/LF before any
  resource runs, which broke BR-06's own answers (R-03, R-26, the control-character refusal). The strict
  firewall stays, with exactly those three allowances; the resources keep deciding as before.
- **Protected-resource metadata.** Spring Security 7.1 always serves `GET /.well-known/oauth-protected-resource`
  (no switch found in 7.1.2). It reads no body; whether its content is acceptable is for the security review.
- **Shipped settings are pinned by a test** (`ProductionIdentitySettingsTest`): the ITs set issuer, audience
  and keys themselves, so only this test notices if `application.yml` loses `audiences`.
- `check-write-lock.sh red` checks Surefire only; a RED round made of `*IT` alone cannot pass it.

---

## Addendum 2026-11-09 (4): identity review findings (I-01 to I-10)

Source: the stage 6 code and security reviews of `7bfb1cd..63e6fda`, both `[CHANGES REQUIRED]`.

| I | Finding | Design |
|---|---|---|
| I-01 | An empty `ISSUER_URI`, `TOKEN_AUDIENCE` or `JWK_SET_URI` starts the service with that check absent (Boot adds the issuer and audience validators only for non-empty values; an empty JWK set URI falls back to issuer discovery) | Startup fails when any of the three is missing **or blank** (validated configuration properties). In depth, one extra token validator refuses every token unless the issuer is configured and equal, the audience list is non-empty and intersects, and `exp` is present |
| I-02 | A token without `exp` is accepted (`JwtTimestampValidator` allows it) | Covered by the validator of I-01: `exp` is required |
| I-03 | The firewall also refuses `%25`, `%2E` and U+2028/U+2029 in a customer number, which BR-06 allows and the resources answered before | Four more allowances (encoded percent, period, line separator, paragraph separator). The earlier note "exactly those three" is replaced: the firewall allows exactly what BR-06 needs, and still refuses `//`, `\\`, `%2F`, `%00`, traversal |
| I-04 | Two distinguishable 401s: the filter's (instance `/api/risk-profiles`, `WWW-Authenticate`) and the resources' (route instance, no header) reveal whether a token passed validation | Every caller-not-identified answer is byte-identical: instance `/api/risk-profiles`, `WWW-Authenticate: Bearer`, `no-store`, on all four routes and from the filter |
| I-05 | JWK set unreachable: a request with a token ends as 500 | D-13: anything else is not identified. The answer is the same constant 401; one WARN per occurrence names only the exception type. No token, URL or message in the log |
| I-06 | Every token with an unknown `kid` forces an outbound JWK set fetch at the caller's rate | The decoder's key source is rate limited (Nimbus `JWKSourceBuilder` with rate limiting on) with short connect and read timeouts; an unknown `kid` inside the limit window is the constant 401 |
| I-07 | `GET /.well-known/oauth-protected-resource/**` (Spring Security 7.1, no off switch) reflects host and path and claims mTLS-bound tokens | Answered by the constant 404 `request-not-handled`, like any unknown path, before Spring Security's metadata filter |
| I-08 | DEBUG on `org.springframework.security` would log request paths (customer numbers) and validation detail | `logging.level.org.springframework.security: INFO` pinned in `application.yml` and by `ProductionIdentitySettingsTest`; ban recorded in AGENTS.md |
| I-09 | Lines no test protects: a non-string element among the roles, `/logout`, a nested claim object | tests only |
| I-10 | The comment says the audience is "the client id or App ID URI"; with the v2.0 issuer Entra puts the client id GUID in `aud` | comment corrected; `accessTokenAcceptedVersion: 2` noted as a deployment requirement |

**Open for the product owner (not in this round):** how the onboarding *system* is identified. It will use
app-only (client-credentials) tokens, and Entra emits directory-extension claims for users only, so such a
token may carry no booking centre. Also whether ID tokens must be told apart from access tokens when a
client and the API share one app registration, and who may write the directory attribute behind the
booking-centre claim.

### Recorded after stage 4 (identity round 2)

- I-05: the WARN names the root cause's type (for example `ConnectException`); the outer type would always
  be the same. A refresh refused by the rate limiter is an ordinary invalid token: constant 401, no WARN,
  so a caller cannot write a log line per request.
- I-06: Nimbus lets up to two fetches through per 30 s window. Connect and read timeouts are 2 s, constants
  in code. The fetch uses `HttpURLConnection`, so it follows the JVM proxy properties.
- I-07: the 404 filter is registered after `HeaderWriterFilter`, which puts it ahead of Spring Security's
  metadata filter; that filter has no registered order to refer to.
- Exercised for I-05: connection refused only. A read timeout or a malformed key set takes the same path, untested.

### Identity re-review of round 2 (2026-11-11): one trade-off, open for the product owner

Both reviews of `63e6fda..ddd1db1` agree on one finding, F1. The key-fetch budget (Nimbus
`RateLimitedJWKSetSource`: one fetch per window, global, not per `kid`, 30 s window) is shared by all
callers. A caller who sends tokens with random unknown `kid`s at about two requests per window keeps the
budget spent, so a legitimately rotated signing key cannot be fetched for as long as the flood lasts.
Legitimate callers then get the constant 401, with no log line. This is fail-closed, not a bypass. Keys
already cached keep verifying until their 300 s TTL.

The trade-off is inherent to the two options: without a limit, every unknown `kid` costs an outbound
fetch (I-06 fails); with it, rotation can be delayed by a flood. Both reviewers propose keeping the
last-known key set when a refresh is refused, which still needs a decision (it can serve a revoked key
for up to the TTL). Not changed in this round; recorded as a known risk until the product owner decides.

Also recorded from the same reviews, accepted as low: a request the firewall refuses gets 400 before any
identification (the answer does not depend on the token); a header block over Tomcat's limit gets a
Tomcat 400 before any filter. Both depend only on the request, not on the token, so neither is an oracle.

Firewall negative tests (`RiskApiHardeningIT.Firewall`) for `%2F`, `%5C`, `%00`, `%2E%2E%2F`, `..%2F`,
`//` and `%252F%2E%2E` pass with the current allowances: they guard against widening.

### Decision 2026-11-11: F1 through a backend-for-frontend; LOW items accepted

- **F1 (key-fetch budget shared by all callers).** Direction chosen by the product owner: a
  backend-for-frontend (BFF) in front of this service. The browser or app talks to the BFF with its
  session; only the BFF calls this service, and it sends tokens it obtained itself. This removes the public
  surface where an unauthenticated caller can submit tokens with arbitrary `kid`s. What it does not remove:
  the BFF's own key fetches (same budget logic, but no attacker input reaches it), and callers that are not
  BFF-fronted (the onboarding system, machine to machine). For those, the open question stays: either
  restrict them to the network path (mTLS or private network, a deployment control) or accept the F1 trade-off
  for them. The architect writes the BFF boundary and its trust model in a separate ADR before any code.
- **Firewall refusals before identification (LOW): accepted.** They do not depend on the token, so they are
  no oracle. Denial of service through the firewall is not a threat model for this service.
- **Tomcat refusal of an oversized header block (LOW): accepted.** Same reasoning; no container
  configuration is changed for it. The business code stays the place where requirements are met.

### Decision 2026-11-11 (2): the onboarding system is internal only

Product owner (spec D-17): the onboarding system reaches this service only from the internal network, and
nothing from the internet can reach it. Its risk classification is therefore lower than the customer-facing
channels'. Consequence for F1: the key-fetch flood needs a caller who can already reach the internal network,
and that risk is accepted for onboarding.

This is a **deployment control**, not code: AGENTS.md lists it beside the D-11 controls. It must be enforced
by the network (no route from outside to this service, or mTLS), and it must be checked at deployment.
Customer-facing callers keep the BFF direction above.

---

## Addendum 2026-11-28 (2): review of the merged lines

Source: the independent code and security reviews of the merge `d2bd785` (state board, same date). Written
by the orchestrator, not by `@architect`, because every item below is already decided by a spec decision
or is a configuration bound; nothing here is a new business rule. Items that need a design are not here;
they are listed on the board as open.

| # | Review | Design for stage 3 and 4 |
|---|---|---|
| 1 | CR-01, SR-02 (D-23) | `CallerClaims`: the role claim yields a caller only when it holds exactly one value and that value is an `ActorRole` name. Any other value, of this or another application, is no caller (401). `IdentityIT.shouldIgnoreForeignRoles` is replaced by a bad-token case |
| 2 | CR-08, SR-05 | `IdentitySettings` refuses `issuer-uri` and `jwk-set-uri` that are not `https://`, except a loopback host (`127.0.0.1`, `localhost`, `[::1]`) over `http://`, which the tests use. The message names the setting and never prints the URL. The test settings keep working |
| 3 | SR-04 | Every not-identified answer has the same HTTP framing, whoever writes it: the filter's refusal uses the same writing path as `ProblemResponses.notIdentified()`, so `Content-Length` and `Transfer-Encoding` agree across no token, a forged token and unusable claims. A raw-socket test compares the header names of all cases (without `Date`) |
| 4 | CR-04, SR-09 (D-21) | New property `app.identity.key-cache-ttl` (ISO-8601 duration), required, at most `PT5M`, never above it; it is the Nimbus cache time-to-live, set explicitly. Missing or too large stops startup with a message naming the property. `ProductionIdentitySettingsTest` pins it in the shipped `application.yml` |
| 5 | CR-03, SR-03 (D-36, D-24) | A request with **no authenticated bearer principal**, using `OPTIONS` or a method the route does not take, on one of the four routes, gets the constant not-identified answer (401, same body, headers and framing as item 3), before Spring MVC. A request with a valid token behaves as before. `TRACE` and `OPTIONS *` stay container refusals (D-36). Unknown paths stay the constant 404. Residual, accepted and recorded: a valid token with unusable claims is not identified, yet reaches MVC on a wrong method |
| 6 | CR-07 | A unit test of `ConfiguredIdentityValidator` that fails when the issuer or audience guard is removed, and a startup test that asserts the message text of the `jwk-set-uri` constraint, not the word `jwk` |
| 7 | CR-05, CR-06, CR-09, CR-10 | One contract: `openapi/customer-risk-scoring.yaml` is authoritative; `openapi/risk-profiles.yaml` is removed (its history stays in git) after its security scheme is taken over as `bearerAuth` (HTTP bearer, JWT). 401 examples use `instance` `/api/risk-profiles` and `WWW-Authenticate: Bearer`; "today every call is 401" prose goes; decisions state that the body is read before the role (BR-37, D-08); the 400 for `//` and `/a/../b` is described; unauthenticated `OPTIONS` and wrong methods are 401 (item 5) |

**Out of this round, open.** D-19 (application check; onboarding registration; the onboarding booking centre
from the registration), D-20 and D-28 (mutual TLS), D-25 (the identification step for the BFF), D-30 (role
Operations), SR-06 and SR-07 (Entra tenant setup: who may write the booking-centre attribute; ID token against
access token), SR-08 (no maximum token lifetime), and Spring Boot 4.1.3 (patch release). The `ARCHITECT`
decides D-19's registration shape first: the spec leaves open where an application-only token gets its
booking centre.
