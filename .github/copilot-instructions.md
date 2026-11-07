# Repository rules — always active

Backend service: **Java 25 LTS**, **Spring Boot 4**, Maven, PostgreSQL, Apache Kafka.
Architecture: package-by-feature, Hexagonal boundaries, CQRS, Railway Oriented Programming.

---

## 0. Epistemic gate (read before generating anything)

1. **Never invent an API.** Do not write a method, class, annotation or configuration property
   unless you are certain it exists in Java 25, Spring Boot 4, or this repository. Before using a
   library, confirm it is in `pom.xml`. If it is not there, that solution is rejected — do not add
   the dependency to make your first idea work.
2. **Ground in the source of truth before writing.** Read `src/main/resources/db/migration/*.sql`
   before writing any SQL, so table and column names are real. Read the existing signature before
   calling anything on `Result` or a `*UseCase`.
3. **Pick one answer, the one you are most sure of.** When several approaches exist, prefer the one
   the Java compiler can verify (exhaustive `switch` over a `sealed` type beats a runtime check).
   Present that one. Do not offer a menu, and do not propose anything that depends on undocumented
   behaviour or needs a cast to defeat the type system.
4. **Stop when the context is incomplete.** Missing business rule, unknown external contract,
   unknown table shape → ask one precise question. Do not fill the gap with a plausible guess.
   A guess that looks right is more expensive than a question.
5. **Verified means executed.** Nothing is done until `./mvnw verify` has actually run.
   **Paste the real terminal output.** Never write "BUILD SUCCESS" or "tests are green" from
   expectation — if you did not run it, say you did not run it.
6. **Direct tools first; delegate to subagents only for context isolation.** Use direct tools
   (`read_file`, `edit_file`, targeted searches) for simple operations. Delegate to subagents
   only when autonomous work, heavy background execution (`./mvnw`), or broad exploration
   would otherwise pollute the primary conversation context.
7. **Use a library before writing your own.** For any generic technical task (parsing,
   validation, encoding, collections, time), first check the JDK and the dependencies already in
   `pom.xml` for an API that does it. Prefer that API over a hand-written implementation, because
   the library has been tested by many more callers than this repository. Do not add a dependency
   to get it (rule 1 still applies). A hand-written version is allowed only when no JDK or `pom.xml`
   API does the job; then the code says so in a comment, naming the library it was checked against.
   Example: `UUID.fromString` is lenient (it accepts `1-1-1-1-1`), and nothing in the JDK or the
   current `pom.xml` validates canonical UUID text, so `shared/kernel/CanonicalUuid` checks the
   format first and then calls `UUID.fromString`.

---

## 1. Hard bans (these fail the build, not a review)

| Banned | Instead | Enforced by |
|---|---|---|
| `throw new ...` anywhere in `src/main` | `Result.failure(new SomeError(...))` | Checkstyle `noThrowNew` |
| `Objects.requireNonNull` (a `throw` in disguise) | `@NullMarked` + NullAway at compile time; genuine absence is `@Nullable` -> `Result` | Checkstyle `noRequireNonNull` + NullAway |
| Custom `*Exception` classes | `sealed interface *Error` | ArchUnit `no_custom_throwables` |
| `*Service`, `*Facade`, `*Manager`, `*Helper`, `*Util` | one `*UseCase` per operation (`execute` / `ask`) | ArchUnit + Checkstyle |
| `*Controller` in `adapters/web` (the `@RestController` annotation stays) | `*Resource`, for example `SubmissionCommandResource`: the class exposes a resource, it does not control anything | ArchUnit (`haveSimpleNameEndingWith`) + review |
| JPA / Hibernate (`@Entity`, `@OneToMany`, `EntityManager`) | Spring Data JDBC + native SQL | ArchUnit + Checkstyle |
| QueryDSL, jOOQ | native SQL in `@Query("""...""")` or `JdbcClient` | ArchUnit + Checkstyle |
| Jackson 2 (`com.fasterxml.jackson.*`) | Jackson 3 (`tools.jackson.*`) | Checkstyle `forbiddenLibraries` |
| `org.springframework.retry.*` | `org.springframework.kafka.annotation.BackOff` | Checkstyle `forbiddenLibraries` |
| `ScopedValue.callWhere` / `runWhere` | `ActorContext.callWith` / `runWith` | Checkstyle `noScopedValueCallWhere` |
| `ThreadLocal` | `ScopedValue` via `ActorContext` | ArchUnit + Checkstyle |
| `catch (Exception \| Throwable \| RuntimeException)` | catch the specific type | Checkstyle `noCatchAll` |
| `KafkaTemplate` outside `adapters/messaging` | Transactional Outbox relay | ArchUnit |
| Spring, Jackson, Kafka imports in `domain/` or `query/` | ports; adapters do the wiring | ArchUnit |
| Magic numbers in business logic | Named constants (`public static final`) or Value Objects | Checkstyle `noMagicNumbers` |
| Field injection (`@Autowired` / `@Inject` on fields) | Explicit constructor injection | ArchUnit `no_field_injection` + Checkstyle `noFieldAutowired` |

Framework versions move. If you believe a rule above is wrong, say so and stop — do not quietly
work around a gate.

## 2. Package layout

```
com.gs5.underwriting
├── shared/kernel/              Result, ActorContext, ActorRole — keep this tiny
└── <module>/                   one business capability, e.g. submission/
    ├── domain/                 WRITE side. Pure Java. Aggregate is package-private.
    │                           Public: *UseCase, *Command records, *Error, ports, events
    ├── query/                  READ side. Projections only. Cannot see domain/ (compiler-enforced)
    └── adapters/               web/ persistence/ messaging/ config/
```

`domain/` and `query/` may depend only on `java.*`, `com.gs5.underwriting.shared.kernel.*`
and `org.jspecify.*`.

## 3. Commands and queries

Each is a **standalone `public record`**. There is no shared `Command` interface and no
`handle(Command)` dispatcher. A common interface forces every operation into one return type and
one bag of errors, so a caller registering a submission has to handle errors that can only happen
when approving one.

**One operation = one `*UseCase` class = one public method.** There are no `*Facade` and no
`*Service` classes: a facade grows from two methods to fifty, every new feature edits the same
file, and its constructor collects every port any method needs. Instead:

- write side, in `domain/`: `RegisterSubmissionUseCase.execute(RegisterSubmissionCommand)`
- read side, in `query/`: `SearchSubmissionsUseCase.ask(SearchSubmissionsQuery)`

Each returns its own `Result<T, ThatOperationsError>`, and its constructor takes only the ports
that one operation needs. A new operation is a new class, never a new method on an existing one.
ArchUnit (`use_cases_have_exactly_one_public_method_named_for_their_side`) fails the build on a
second public method or a method not named `execute` / `ask`.

## 4. Which file governs what

- `AGENTS.md` — build commands, module map, the agent workflow. Always read first.
- `.github/instructions/*.instructions.md` — injected automatically by file path.
- `.github/skills/*/SKILL.md` — multi-step recipes, loaded on demand:
  - `spec-to-plan` — turn a business requirement into commands, errors, projections, events
  - `hexagonal-cqrs-slice` — build a full module slice, TDD first
  - `hexagonal-kafka-eda` — Transactional Outbox, idempotent consumers, DLT
- `.github/agents/*.agent.md` — the eight roles, invoked with `@name` in chat.

Work in **Plan** mode until the design is agreed; switch to **Agent** mode to write files and run
`./mvnw`.
