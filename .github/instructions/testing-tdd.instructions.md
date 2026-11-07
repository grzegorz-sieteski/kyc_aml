---
description: "TDD standards: JUnit 5, AssertJ, in-memory fakes, Testcontainers, Awaitility"
applyTo: "src/test/**/*.java"
---

# Test standards

## Two kinds of test, two plugins

| Suffix | Runner | Speed | Use for |
|---|---|---|---|
| `*Test.java` | Surefire (`./mvnw test`) | milliseconds | use cases, aggregates, projections — in-memory fakes only |
| `*IT.java` | Failsafe (`./mvnw verify`) | seconds | real PostgreSQL / Kafka through Testcontainers |

Naming decides which plugin runs the file. A slow test named `*Test` poisons the fast loop.

## Unit tests: fakes, not mocks

Do not use Mockito for `domain/` or `query/`. Write a deterministic fake in the test tree:

```java
final class InMemorySubmissionCommandRepository implements SubmissionCommandRepository {
    private final Map<SubmissionId, SubmissionSnapshot> store = new ConcurrentHashMap<>();
    // plus given*/ then* helpers to arrange and inspect state
}
```

A fake tests behaviour; a mock tests that you called a method you already decided to call. Mocks
are acceptable only at an adapter boundary you do not own.

## Assert on the whole `Result`

```java
// NO — passes while the error is completely wrong
assertThat(result.isFailure()).isTrue();

// YES — pins the exact failure
assertThat(result).isEqualTo(Result.failure(new RegisterSubmissionError.ExternalReferenceTooLong(64, 80)));
```

Never use `assertThatThrownBy` for a business rule. Business rules do not throw here; if one does,
that is the bug.

## Cover every variant of every `*Error`

For each record in a `sealed interface *Error` there must be at least one test that produces it.
The sealed hierarchy is the checklist — if you cannot reach a variant, it should not exist.

## Test Naming & BDD Scenario Structure

1. **Naming**:
   - Method name: camelCase with `should...When...` pattern, e.g. `shouldRefuseRegistrationWhenCallerIsUnidentified()`.
   - Every test must have a descriptive `@DisplayName("...")` in clear business language describing the invariant.
2. **BDD Structure**:
   - Every test body must be divided and annotated with explicit comments:
   ```java
   // Given
   var actor = new ActorContext(TENANT_1, "uw-01", ActorRole.UNDERWRITER);
   var command = new RegisterSubmissionCommand("REF-1", new BigDecimal("100.00"));

   // When
   var result = ActorContext.callWith(actor, () -> useCase.execute(command));

   // Then
   assertThat(result).isEqualTo(Result.success(expectedId));
   ```
3. **Parameterized Tests**:
   - Use `@ParameterizedTest` for boundaries: empty string, `"   "`, one under and one over a length limit, `BigDecimal.ZERO`, `-0.01`, and the maximum.
   - Provide a clear `name` template, e.g.:
     `@ParameterizedTest(name = "[{index}] input={0}")` or `@ParameterizedTest(name = "[{index}] {displayName}")`.

## Integration tests

```java
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureDataSourceInitialization;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization   // see below — without this there is no schema
@Testcontainers
class SubmissionPersistenceAdapterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
}
```

`@ServiceConnection` wires the container to Spring — no `@DynamicPropertySource` needed.

Three details that each produce a confusing runtime failure rather than a compile error:

- **`@DataJdbcTest` does not pull in Flyway.** The slice imports only
  `DataJdbcRepositoriesAutoConfiguration` and `DataSourceAutoConfiguration`; migrations arrive
  through `@AutoConfigureDataSourceInitialization`, which the slice is not meta-annotated with.
  Leave it out and the container starts with an empty schema and every test dies on
  `relation "..." does not exist`. **Check this first when a fresh Docker run fails.**
- **`org.testcontainers.postgresql.PostgreSQLContainer`**, not
  `org.testcontainers.containers.PostgreSQLContainer`, which is deprecated in Testcontainers 2.x.
  The live class is **not generic**, so there is no `<?>`. It still extends `JdbcDatabaseContainer`,
  which is the type `@ServiceConnection` binds to, so the move is safe.
- **Boot 4 test annotations live under `.test.autoconfigure`**, e.g.
  `org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest`. Resolve these against the
  jars rather than from memory; the pre-4 package names are close enough to look right.

**Dependency names (Testcontainers 2.x renamed every module):**
`org.testcontainers:testcontainers-junit-jupiter`, `:testcontainers-postgresql`,
`:testcontainers-kafka`. The 1.x names `junit-jupiter`, `postgresql`, `kafka` do not resolve.

An integration test for a Spring Data JDBC adapter must cover the insert-versus-update case: save
a brand-new aggregate whose UUID was generated in the domain, and assert a row was inserted and
`version` was set. That is the trap this architecture is most likely to hit.

## Asynchrony

Never `Thread.sleep(...)`. Use Awaitility:

```java
await().atMost(Duration.ofSeconds(10))
       .pollInterval(Duration.ofMillis(200))
       .untilAsserted(() -> assertThat(repository.findById(id))
               .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(APPROVED)));
```

Every Kafka consumer needs a duplicate-delivery test: publish the identical event twice, assert
the effect happened once and nothing blew up.

## Binding an actor in tests

```java
var actor = new ActorContext(TENANT_1, "uw-01", ActorRole.UNDERWRITER);
var result = ActorContext.callWith(actor, () -> useCase.execute(command));
```

Not `ScopedValue.callWhere(...)` — that was removed from the JDK and will not compile.
