---
name: hexagonal-cqrs-slice
description: "Builds a complete CQRS module slice on Java 25 and Spring Boot 4: pure domain package with a package-private aggregate and a UseCase, a separate query package with projections, plain Spring Data JDBC adapters using native SQL mapped to records, REST controllers, and the tests for all of it. USE FOR: implementing a new business module, adding a command or query to an existing module, adding a persistence adapter, wiring a use case into Spring. DO NOT USE FOR: JPA or Hibernate projects, Kafka and event publishing (use hexagonal-kafka-eda), CI or build configuration."
---

# CQRS slice: pure core, plain JDBC adapters

## Before starting

Read `docs/adr/{feature}-design.md`. If it does not exist, stop and run `spec-to-plan` first —
building from a verbal description is how invented business rules get into code.

Read `src/main/resources/db/migration/*.sql` before writing any SQL.

## Layout

```
com.gs5.underwriting.<module>/
├── domain/                                   WRITE SIDE — pure Java 25
│   ├── package-info.java                     @NullMarked
│   ├── <Operation>UseCase.java               public — ONE operation, one public execute(...)
│   ├── <M>Id.java                            public record wrapping UUID
│   ├── Register<M>Command.java               public record, standalone
│   ├── Register<M>Error.java                 public sealed interface, this operation only
│   ├── <M>CommandRepository.java             public — outbound port, domain types only
│   ├── <M>Snapshot.java                      public — immutable state for persistence
│   ├── <M>DomainEvent.java                   public sealed interface
│   └── <M>.java                              PACKAGE-PRIVATE aggregate
├── query/                                    READ SIDE — cannot see domain/
│   ├── package-info.java
│   ├── <Query>UseCase.java                   public — ONE read, one public ask(...)
│   ├── <M>SearchQuery.java                   public record
│   ├── <M>QueryError.java                    public sealed interface
│   ├── <M>QueryRepository.java               public — returns *View directly
│   └── <M>View.java                          public projection records
└── adapters/
    ├── web/        <M>CommandResource, <M>QueryResource
    ├── persistence/ <M>JdbcEntity, <M>CommandJdbcRepository, <M>CommandSqlAdapter,
    │                <M>QuerySqlAdapter, <M>PersistenceMapper   (all package-private)
    └── config/     <M>Configuration — registers the use cases as beans
```

## Step 1 (RED): contracts and failing tests

Write the public records and interfaces, plus use case methods whose bodies `return null`. Then
write the tests that prove the behaviour, which fail because the bodies are empty.

`./mvnw test-compile` must succeed. `./mvnw test` must fail. If the tests pass at this point,
they are not testing anything.

In-memory fakes for the ports, in the test tree, backed by `ConcurrentHashMap`. No Mockito here.

## Step 2 (GREEN): the aggregate and the use case

The aggregate is package-private with a private constructor and named factories:

```java
record SubmissionSnapshot(UUID id, UUID tenantId, String externalRef,
                          BigDecimal insuredLimit, String status, Long version) { }

final class Submission {

    static Result<Submission, RegisterSubmissionError> register(
            UUID tenantId, String externalRef, BigDecimal insuredLimit) {

        // isEmpty, not isBlank: trimming is a business decision. Unless a rule says whitespace
        // is insignificant, "   " is a reference like any other, and isBlank() silently refuses
        // input the specification accepts.
        if (externalRef == null || externalRef.isEmpty()) {
            return Result.failure(new RegisterSubmissionError.ReferenceMissing());
        }
        if (externalRef.length() > MAX_REF) {
            return Result.failure(
                    new RegisterSubmissionError.ReferenceTooLong(MAX_REF, externalRef.length()));
        }
        // The variant names the reason and carries NO amount. An error record is logged and
        // serialised into ProblemDetail, so anything inside it is published. Never put a
        // sensitive value in an error.
        if (insuredLimit == null || insuredLimit.signum() <= 0) {
            return Result.failure(new RegisterSubmissionError.InsuredLimitNotPositive());
        }
        return Result.success(new Submission(/* ... */));
    }

    /** Rebuilds state from the database. Carries the version; does not re-validate. */
    static Submission reconstitute(SubmissionSnapshot snapshot) { /* ... */ }

    SubmissionSnapshot toSnapshot() { /* ... */ }
}
```

Each operation is its own class with exactly one public method — `execute` on the write side,
`ask` on the read side — and a constructor holding only the ports that operation needs. A second
operation is a second class, never a second method; ArchUnit fails the build otherwise. The
method composes on the railway:

```java
public final class RegisterSubmissionUseCase {

    private final SubmissionCommandRepository repository;
    private final Clock clock;

    public Result<SubmissionId, RegisterSubmissionError> execute(RegisterSubmissionCommand command) {
        return ActorContext.current()
                .map(actor -> Submission
                        .register(actor.tenantId(), command.externalRef(), command.insuredLimit())
                        .map(submission -> repository.save(submission.toSnapshot())))
                .orElseGet(() -> Result.failure(new RegisterSubmissionError.NoActorBound()));
    }
}
```

No `@Service`, no `@Component`, no `@Transactional` in `domain/`. Registration happens in
`adapters/config/`.

## Step 3: the write adapter

```java
@Table("submissions")
record SubmissionJdbcEntity(
        @Id UUID id,
        UUID tenantId,
        @Column("external_ref") String externalRef,
        @Column("insured_limit") BigDecimal insuredLimit,
        String status,
        @Column("created_at") Instant createdAt,
        @Version @Nullable Long version) { }
```

**The one trap in Spring Data JDBC.** The id is a UUID generated in the domain, so the entity is
never "new" by the usual id-is-null test, and `save()` would issue an UPDATE that silently
affects zero rows. The `@Version @Nullable Long version` field is what fixes it: `null` means
insert, non-null means update with an optimistic lock. The mapper **must carry `version` in both
directions** — dropping it is the single most common bug in this stack, and it only shows up
under concurrency.

Write an `*IT` that saves a brand-new aggregate against a real PostgreSQL container and asserts
a row exists and `version` is set. Without that test you have not verified this.

## Step 4: the read adapter

The read side never loads an aggregate. Native SQL straight into a projection record:

```java
interface SubmissionQueryJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    @Query("""
            SELECT id, external_ref, insured_limit, status, created_at
            FROM submissions
            WHERE tenant_id = :tenantId
              AND (:status IS NULL OR status = :status)
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    List<SubmissionSummaryView> search(@Param("tenantId") UUID tenantId,
                                       @Param("status") @Nullable String status,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);
}
```

Extend `Repository`, not `CrudRepository` — a read repository that offers `save()` will eventually
be used to save. Every parameter is bound; nothing is concatenated. Every query filters by tenant.

## Step 5: wiring and the web edge

```java
@Configuration
class SubmissionConfiguration {

    @Bean
    RegisterSubmissionUseCase registerSubmissionUseCase(SubmissionCommandRepository repository, Clock clock) {
        return new RegisterSubmissionUseCase(repository, clock);
    }
}
```

The controller binds `ActorContext` from the security context, calls the use case, and folds the
`Result` into a response with an exhaustive `switch` and no `default` branch. Failures become
RFC 7807 `ProblemDetail`, carrying a stable code and a user-safe message — never a stack trace,
a SQL fragment or an internal id.

## Validation checklist

- [ ] `./mvnw verify` passes, and you pasted the real output
- [ ] The aggregate class is package-private
- [ ] No `throw new` anywhere in `src/main` (Checkstyle enforces this; run it)
- [ ] `domain/` and `query/` import nothing from Spring, Jackson or Kafka
- [ ] `query/` does not import `domain/`
- [ ] Every `*Error` variant has a test that produces it
- [ ] The mapper carries `version` both ways, and an `*IT` proves insert works
- [ ] Every SQL parameter is bound; every query filters by tenant
- [ ] No `default` branch in any `switch` over a sealed type
- [ ] **No error variant carries a sensitive value.** Error records end up in logs and in the
      HTTP response body, so a field placed in one is a field you have published. Name the
      reason, carry identifiers and bounds, never the protected value itself.
- [ ] **Validation matches the specification literally.** `isBlank()` where the rules say
      "empty", a trim nobody asked for, or a case-insensitive compare against an exact-match
      rule each silently change behaviour the business decided on.
