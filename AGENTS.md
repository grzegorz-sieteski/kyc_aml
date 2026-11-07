# AGENTS.md — operating manual for this repository

Backend service. **Java 25 LTS**, **Spring Boot 4.1.2**, Maven, PostgreSQL, Apache Kafka.
Read this before anything else; `.github/copilot-instructions.md` holds the hard bans.

## Commands

```bash
./mvnw -B validate                                   # Checkstyle architecture gate
./mvnw -B test                                       # fast unit tests + ArchUnit (Surefire)
./mvnw -B verify                                     # the above + Testcontainers ITs (Failsafe)
./mvnw -B verify -DskipITs                           # skip ITs when Docker is unavailable
./mvnw -B test -Dtest=ArchitectureRulesTest          # architecture rules only
./mvnw -B test -Dtest=ResultTest                     # one test class
./mvnw -B spring-boot:run                            # run locally (needs Postgres + Kafka)
```

JDK 25 is required. Integration tests (`*IT.java`) need a running Docker daemon.

**Never report a build result you did not run.** Paste the terminal output. If you could not run
it, say so.

## Layout

```
underwriting-service/
├── AGENTS.md                          this file
├── pom.xml                            Boot 4 modular starters, no preview features
├── config/checkstyle/checkstyle.xml   statement-level gate (throw, imports, catch-all)
├── docs/
│   ├── specs/                         @business-analyst output
│   ├── adr/                           @architect output
│   └── workflow/                      @orchestrator state boards
├── requests/                          @qa-explorer HTTP Client files
├── openapi/                           API contract (OpenAPI specification)
├── scripts/check-write-lock.sh        phase boundary check
├── .github/
│   ├── copilot-instructions.md        always active: bans + router
│   ├── instructions/                  auto-injected by file path (applyTo)
│   ├── skills/                        loaded on demand
│   └── agents/                        eight roles, invoked with @name
└── src/
    ├── main/java/com/gs5/underwriting/
    │   ├── shared/kernel/             Result, ActorContext, ActorRole — keep tiny
    │   └── <module>/                  domain/ · adapters/
    └── test/java/com/gs5/underwriting/
        └── ArchitectureRulesTest.java structural gate
```

## Architecture in one paragraph

Each business module is self-contained: `domain/` is the write side in pure Java with a
package-private aggregate behind a `*UseCase`; `adapters/` holds web, persistence, messaging and
Spring wiring. Business failures are values — `Result<T, E>` with a `sealed interface *Error`
per operation — so the compiler forces every caller to handle exactly the failures that operation
can produce. There are no `*Service` classes and no `throw` statements in `src/main`.

## The eight agents

| # | Agent | Produces | May write | Gate |
|---|---|---|---|---|
| 0 | `@orchestrator` | state board | `docs/workflow/` | verifies every other gate |
| 1 | `@business-analyst` | specification | `docs/specs/` | no unresolved `ASSUMPTION` marker |
| 2 | `@architect` | technical design | `docs/adr/` | covers every command in the spec |
| 3 | `@test-designer` | contracts + failing tests | `src/test/`, stubs in `domain/` | `test-compile` passes, `test` **fails** |
| 4 | `@developer` | production code | `src/main/` | `verify` passes, `src/test/` untouched |
| 5 | `@qa-explorer` | exploratory findings | `requests/`, `src/test/` | `src/main/` untouched |
| 6 | `@code-reviewer` | review report | nothing | `[APPROVED]` |
| 7 | `@sec-reviewer` | security report | nothing | `[APPROVED]` |

Call each in a **fresh chat**. The separation is the point: an agent that can see the previous
agent's reasoning starts agreeing with it.

Stage 3 passing its build is a failure, not a success — green tests there assert nothing.

When a reviewer rejects, work goes back to **stage 3**, not stage 4: a failing test is written
for each defect before anything is fixed.

## Execution heuristics (macro roles vs micro subagents)

The eight roles above govern the **macro SDLC lifecycle**. Within any stage, agents must observe
these micro-level execution heuristics:

1. **Direct tools first:** Prefer direct tool calls (`read_file`, `edit_file`, targeted searches)
   for simple operations. Spawning a subagent introduces overhead and latency; do not delegate
   trivial tasks.
2. **Subagents for context isolation (`explore` / `research`):** Spawn an isolated background
   subagent only when an investigation spans many files or external sources and the raw output
   would pollute the conversation context. The subagent must return a synthesized summary.
3. **Deterministic tasks in isolation (`task`):** Run heavy build or verification cycles
   (`./mvnw verify`, Testcontainers) as discrete execution tasks. Return the pass/fail outcome and
   actionable failure output rather than dumping thousands of raw log lines into the conversation.
4. **Read-only review isolation (`code-review` / `security-review`):** Review stages focus
   strictly on git diffs, invariant adherence, and security attack surfaces (OWASP, BOLA, PII masking)
   with write tools permanently disabled.

## How the boundaries are actually enforced

Be honest about this, because the distinction matters:

- **`tools:` in each agent's frontmatter** removes the edit capability from the two reviewers
  entirely. That part is real.
- **Directory-level write locks are not enforceable from frontmatter.** No IDE restricts an
  agent's edits to `src/test/` versus `src/main/`. The prose in each agent file states the rule;
  **`scripts/check-write-lock.sh` and the CI workflow are what make it true.** Run the check after
  every stage rather than trusting a claim.
- GitHub lists custom agents and skills as **partially supported** in JetBrains IDEs, so behaviour
  there may differ from VS Code. Verify rather than assume.

```bash
./scripts/check-write-lock.sh green    # developer must not have touched src/test or docs
./scripts/check-write-lock.sh qa       # qa must not have touched src/main
./scripts/check-write-lock.sh red      # test-compile passes AND test fails
```

## Known state of this repository

- The `submission` module exists: register a submission, and search submissions by filter. It was
  built through the agent workflow, which is also how the workflow itself got tested.
  `docs/workflow/submission-registration-state.md` records what each stage found, including four
  defects in this repository's own rules that the separation of roles caught.
- **NullAway runs on `src/main` at compile time** (Error Prone 2.52 + NullAway 0.14.3). It needs the
  `--add-exports` flags in `.mvn/jvm.config`, which `./mvnw` picks up automatically. If you build
  with another Maven (IntelliJ's bundled one included), point it at that file or the compile fails
  with an `IllegalAccessError`. Tests are not checked: they hand null to production code on purpose.
- **ArchUnit must stay at 1.5.1 or newer.** Version 1.4.0 cannot read Java 25 class files: it logs
  `Unsupported class file major version 69` at WARN, skips every class, and then reports all rules
  as passing. The suite was green while analysing nothing until this was caught. Treat a downgrade
  as a breaking change, and after any ArchUnit or JDK upgrade, break a rule on purpose once and
  confirm the build fails.
- `src/test/resources/archunit.properties` sets `archRule.failOnEmptyShould=true`, and there is no
  per-rule escape hatch left anywhere. Keep it that way: with it false, a typo in a package name
  turns a rule into a green test that checks nothing, and you will trust it.
- `openapi/` is empty: there is no contract to publish until there is an endpoint.
