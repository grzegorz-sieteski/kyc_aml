---
description: "Java 25 LTS language level, functional style, and the APIs models most often get wrong"
applyTo: "**/*.java"
---

# Java 25 and functional style

## Language features you should use

All of these are **final** in Java 25 — no `--enable-preview` needed, and none is configured:

- **Records** for every immutable carrier: commands, queries, views, events, value objects.
- **Sealed interfaces** for closed hierarchies: `*Error`, domain events, state.
- **Pattern matching for `switch`** with record deconstruction:
  `case Success<T, E>(var value) -> ...`
- **No `default` branch** in a `switch` over a `sealed` type. The compiler then tells you about
  every new variant; a `default` hides them and the bug ships.
- **Unnamed variables `_`** (JEP 456) for bindings you do not use.
- **Flexible constructor bodies** (JEP 513): validate or compute before `this(...)` / `super(...)`.
- **Module imports** (JEP 511): `import module java.base;` instead of twenty `java.util.*` lines.
- **Stream Gatherers** (JEP 485): `stream.gather(Gatherers.windowFixed(100))` to batch.
- **Scoped values** (JEP 506): request context on virtual threads.

## Not available — do not use

**JEP 507 (primitive type patterns, `case int len when len > 10`) is still a preview feature in
Java 25** and preview is deliberately switched off in `pom.xml`. Preview class files are pinned to
one exact JDK build and refuse to run on the next one, which is unacceptable for a deployed
service. Write a guard with a normal `if` or a `when` clause on a reference pattern instead.

## APIs that models reliably get wrong

| Wrong (common in training data) | Correct for this stack |
|---|---|
| `ScopedValue.callWhere(KEY, v, op)` | `ActorContext.callWith(actor, op)` — the static form was removed in JDK 24 |
| `ScopedValue.runWhere(KEY, v, op)` | `ActorContext.runWith(actor, op)` |
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` (Boot 4 ships Jackson 3) |
| `org.springframework.retry.annotation.Backoff` | `org.springframework.kafka.annotation.BackOff` |
| `@RetryableTopic(backoff = @Backoff(...))` | `@RetryableTopic(backOff = @BackOff(...))` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` (TC 2.x renamed every module) |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` (Boot 4 modular starters) |
| Vavr / Arrow combinators on JDK types | the methods on our own `Result` |

If you want a method on `Result` that is not in `shared/kernel/Result.java`, add it there with a
test. Do not invent one at the call site.

## Functional style

- Everything immutable: `record`, `final` fields, `List.copyOf(...)` on the way in.
- No mutable accumulation. `stream().map(...).toList()`, not a loop appending to an `ArrayList`.
- No `if / else if` ladders over a closed set — that is what an exhaustive `switch` is for.
- Compose on the railway: `.map`, `.flatMap`, `.ensure`, `.fold`. A method that returns
  `Result` should read as a pipeline, not a staircase of early returns.
- `Optional` for absence, never `null`. `@NullMarked` is on at package level (JSpecify);
  mark the exceptions with `@Nullable`.
- Side effects live at the edges — in adapters, not in `domain/` or `query/`.

## Null-safety

Every package gets a `package-info.java` with `@NullMarked`. Use
`org.jspecify.annotations.Nullable`, not the Spring or JetBrains annotation.
