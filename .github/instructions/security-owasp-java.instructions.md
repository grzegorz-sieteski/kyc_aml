---
description: "OWASP Top 10 and SAST-clean rules for production Java in this service"
applyTo: "src/main/**/*.java"
---

# Security rules for production code

## A03 — Injection

Native SQL is used everywhere here, so this is the highest-frequency risk in the codebase.

```java
// NO — string concatenation into SQL
var sql = "SELECT * FROM submissions WHERE status = '" + status + "'";

// YES — named parameters, always
@Query("""
        SELECT id, external_ref, status, created_at
        FROM submissions
        WHERE tenant_id = :tenantId AND status = :status
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """)
```

Dynamic filtering is still bound: build the `WHERE` from a fixed set of known fragments and bind
every value. A column or direction that varies must be validated against an allow-list
(an enum), never interpolated from input. Same rule for `JdbcClient`.

Read `src/main/resources/db/migration/*.sql` before writing SQL. Do not guess column names.

## A01 — Broken access control (IDOR / BOLA)

Every lookup and every mutation that takes a UUID must also filter by the caller's tenant:

```java
// NO
repository.findById(submissionId);

// YES
repository.findByIdAndTenantId(submissionId, actor.tenantId());
```

A record belonging to another tenant returns **`NotFound`**, never `Forbidden`. Distinguishing the
two confirms to an attacker that the id exists.

**Who may call a use case is decided in the adapter, before any input is parsed.** The adapter
knows which caller may invoke which operation: identify the caller (401), check the permission for
that use case (403), and only then parse the request into value objects. An unauthorised caller's
input is never examined. Keep the guard in one place, so a second inbound path cannot forget it.

A rule that depends on the data is different: an authority limit that depends on an amount, or a
state transition, is a business rule and stays in `domain/`, because it needs the aggregate.
The test is "could I decide this from the caller and the operation alone?" — if yes, it is a
permission and belongs at the edge.

## A08 — Deserialization and payload integrity

- Never enable polymorphic typing by class: no `activateDefaultTyping`, no
  `@JsonTypeInfo(use = Id.CLASS)`.
- Every inbound record — REST body, Kafka contract, command — validates in its compact
  constructor: not-null, length bounds, numeric range, allowed character set. Unbounded strings
  and collections are a denial-of-service vector.
- Bound pagination: reject `limit` above a fixed maximum rather than passing it to SQL.

## A09 — Logging failures and PII

- Any record holding a national id, financial figure, policy detail or token overrides
  `toString()` with masking. Records log their whole state by default, so a single
  `log.info("cmd={}", cmd)` leaks everything.
- Never log a secret, token or full payload at `INFO` or `DEBUG`.
- **Log injection (CRLF):** never log a raw user-supplied string. Strip `\r` and `\n` first — an
  attacker who can inject a newline can forge log entries.
- Log the error type, not the whole object:
  `log.warn("approve rejected: {}", error.getClass().getSimpleName())`.

## A02 / A05 — Configuration & Secret Management

- **Secrets strictly via environment variables:** API keys, database credentials, tokens, and passwords
  must NEVER be hardcoded in repository code, `application.yml`, or test resources. Use environment
  variables (`${DATABASE_PASSWORD}`, etc.) or a secret store.
- **External service integration boundaries (Port & Adapter):** External API clients or SDKs must be
  encapsulated in `adapters/` behind clean domain ports. Auth tokens and credentials must stay inside
  the adapter configuration and never leak into domain or query interfaces.
- Kafka producers: `acks=all` and `enable.idempotence=true`.
- Postgres: the service's role gets `SELECT/INSERT/UPDATE` on its own tables; it is not the owner.

## Errors crossing the boundary

Map `Result.failure` to RFC 7807 `ProblemDetail` with an exhaustive `switch`:

```java
return result.fold(
        view -> ResponseEntity.ok(view),
        error -> switch (error) {
            case SubmissionNotFound _ ->
                    problem(HttpStatus.NOT_FOUND, "Submission not found");
            case AuthorityLimitExceeded(var limit, var requested) ->
                    problem(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Approval exceeds your authority limit of " + limit);
        });
```

The response carries a stable error code and a message safe to show a user. It must never carry a
stack trace, a SQL fragment, an internal id or a class name.

## Concurrency

The service runs on virtual threads. Never use `ThreadLocal` — it pins state to a carrier thread
and leaks between tasks. Request context goes through `ActorContext` / `ScopedValue`.
Never synchronize on a shared mutable field in a request path.
