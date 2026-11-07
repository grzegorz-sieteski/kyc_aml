---
name: spec-to-plan
description: "Turns a business requirement into a technical design in this project's building blocks: standalone Command records, per-operation sealed *Error hierarchies, read projections, domain events and the SQL schema. Produces docs/adr/{feature}-design.md and writes no code. USE FOR: starting a new feature, turning a user story or Jira ticket into a plan, deciding which commands and errors a capability needs, designing a read model. DO NOT USE FOR: writing implementation code, fixing a bug in existing code, CI or configuration changes."
---

# Spec to plan

Run this in **Plan mode**. It produces a design document, not code. The point is that every
argument about shape happens before anything is written, when changing your mind is free.

## Before starting

Read, in this order — do not design against assumptions:

1. `AGENTS.md` — module map and commands
2. `docs/specs/{feature}-spec.md` if the business analyst produced one; it is the source of truth
   for business rules. If it does not exist, say so and ask whether to proceed from the raw
   requirement.
3. `src/main/resources/db/migration/*.sql` — the schema that actually exists
4. An existing module under `src/main/java/com/gs5/underwriting/`, if any, to match conventions

If a business rule, a limit, or a permission is missing, **ask one precise question and stop**.
Do not invent a threshold, a currency, a role or a state machine. A number you made up will be
implemented, tested, and shipped as if someone had decided it.

## Step 1: name the capability

One module directory, named for a business capability, not a technical layer:
`submission/`, `underwriting/`, `billing/`. If you cannot name it without "and", it is two
modules.

## Step 2: the write side

For each state change, specify:

- **Command record** — standalone `public record`, no shared interface. Every field with its hard
  constraint (length, range, precision, allowed characters).
- **One `*UseCase` class per operation** — `RegisterSubmissionUseCase`, `ApproveSubmissionUseCase`,
  each with a single public `execute(Command)`. Not a facade with many methods, not `handle`.
- **`sealed interface *Error` for that one operation.** This is the critical step: list every way
  this specific operation can be refused, and nothing else. Errors that belong to a different
  operation do not go in this hierarchy.
- **Invariants** the aggregate protects, and which error each violation produces.
- **Domain event** emitted on success, with its partition key (always the aggregate id).

## Step 3: the read side

For each information need:

- **Query record** and its own `*UseCase` class with a single public `ask(Query)`
- **`*View` projection record** — exactly the fields the caller needs, nothing more
- The **SQL shape**: tables, filters, sort, pagination bounds, and the index that makes it fast
- Tenant filter on every single query — it is a business rule, not a technical detail

## Step 4: persistence

- Tables, columns, types, constraints, indexes — as a Flyway migration `V{n}__{description}.sql`
- Which columns are `NOT NULL`, which are unique
- The `version BIGINT NOT NULL` column for any aggregate root table
- Note explicitly whether the id is generated in the domain (it should be), because that decides
  the insert-versus-update handling in the adapter

## Step 5: write the design document

Create `docs/adr/{feature}-design.md`:

```markdown
# Design: {feature}

## Scope
What this covers, and what it explicitly does not.

## Commands (write side)
| Command record | UseCase method | Fields and constraints | Error variants | Event on success |

## Queries (read side)
| Query record | UseCase method | View record | SQL shape | Index |

## Aggregate
Fields, invariants, state transitions, factory methods.

## Schema
The Flyway migration, as SQL.

## Open questions
Anything still undecided. An empty section means you are certain, not that you did not look.

## Not doing
Explicitly out of scope, so the next agent does not add it.
```

## Validation before you hand this over

- [ ] Every error variant is reachable by some concrete input — no speculative variants
- [ ] No command shares an `*Error` hierarchy with another command
- [ ] Every query filters by tenant
- [ ] Every SQL identifier exists in a migration, current or newly specified in this document
- [ ] No business number in the document was invented by you; each traces to the spec or to an
      answer from the user
- [ ] The document contains no implementation code beyond record signatures and SQL

End by telling the user the exact prompt for the next stage:
*"Design written to `docs/adr/{feature}-design.md`. Open a new chat and call `@test-designer`
to turn it into contracts and failing tests."*
