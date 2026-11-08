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
