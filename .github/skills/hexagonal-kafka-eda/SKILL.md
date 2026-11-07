---
name: hexagonal-kafka-eda
description: "Generates event-driven components on Spring Boot 4 and Apache Kafka: domain and integration events, the Transactional Outbox pattern on Spring Data JDBC with FOR UPDATE SKIP LOCKED, idempotent consumers using an inbox table, retry and dead letter topics, and Testcontainers integration tests with Awaitility. USE FOR: publishing events to Kafka, writing a @KafkaListener, implementing Transactional Outbox or Inbox, handling poison messages and DLT. DO NOT USE FOR: synchronous REST calls, JPA projects, plain CRUD with no events."
---

# Event-driven slice: Outbox, idempotent consumers, DLT

## Iron rules

**1. Domain events and integration events are different things.**
A domain event is a pure Java record inside `domain/`, expressing a fact in your own language.
An integration event is a versioned public contract in `adapters/messaging/contract/`, serialized
to Kafka. Publishing your domain model as the wire format welds every consumer to your internals.

**2. No dual write — the outbox is mandatory.**
Never call `KafkaTemplate.send(...)` inside a method that also changes database state. The
database commit and the broker send are two separate systems; between them the process can die,
and you lose an event with no trace. The event is written to `outbox_events` in the **same
transaction** as the state change. A relay publishes it afterwards. ArchUnit blocks
`KafkaTemplate` outside `adapters/messaging` so this cannot be bypassed by accident.

**3. Partition key is always the aggregate id.**
Without a key, two events about the same entity can land on different partitions and be processed
out of order. `kafkaTemplate.send(topic, aggregateId.toString(), payload)`.

**4. Consumers are idempotent.**
Kafka delivers at least once. Every consumer must assume it will see the same message twice:
deduplicate on `event_id` through the inbox table, atomically.

**5. No `Thread.sleep` in tests.** Awaitility, always.

## Before starting

Confirm the direction (outbound, inbound, or both), the topic name, the event fields, and the
error policy (how many retries, DLT or not). If any of that is unknown, ask — do not pick a
retry count yourself.

## Step 1: the domain event and the outbound port

```java
package com.gs5.underwriting.submission.domain;

import module java.base;

public sealed interface SubmissionDomainEvent {

    UUID eventId();

    SubmissionId aggregateId();

    Instant occurredAt();

    record SubmissionRegistered(UUID eventId, SubmissionId aggregateId,
                                String externalRef, Instant occurredAt)
            implements SubmissionDomainEvent { }
}
```

The port stays in `domain/` and speaks only domain types:

```java
public interface SubmissionEventPublisher {
    void publish(SubmissionDomainEvent event);
}
```

In unit tests, a fake collecting into a `CopyOnWriteArrayList` proves the use case emitted the right
event — no broker needed.

## Step 2: the outbox writer

```java
@Table("outbox_events")
record OutboxEventJdbcEntity(
        @Id UUID id,
        UUID tenantId,
        String aggregateId,
        String topic,
        String eventType,
        String payloadJson,
        Instant createdAt,
        @Nullable Instant processedAt,
        @Version @Nullable Long version) { }
```

The adapter maps the domain event to the integration contract, serializes with
`tools.jackson.databind.ObjectMapper` (**Jackson 3** — Boot 4 does not ship
`com.fasterxml.jackson`), and saves with `version = null` so Spring Data JDBC issues an INSERT.
This runs inside the caller's transaction; do not open a new one.

## Step 3: the relay

```java
interface OutboxSpringJdbcRepository extends Repository<OutboxEventJdbcEntity, UUID> {

    @Query("""
            SELECT * FROM outbox_events
            WHERE processed_at IS NULL
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEventJdbcEntity> lockNextBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE outbox_events SET processed_at = :processedAt WHERE id = :id")
    void markAsProcessed(@Param("id") UUID id, @Param("processedAt") Instant processedAt);
}
```

`FOR UPDATE SKIP LOCKED` is not an optimisation, it is the correctness condition. The naive
`SELECT ... WHERE processed_at IS NULL` works perfectly on one instance and duplicates every
event the moment a second pod starts — which is to say, in production and not in your test.

```java
@Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval}")
@Transactional
void publishPending() {
    outboxRepository.lockNextBatch(batchSize).forEach(entry -> {
        kafkaTemplate.send(entry.topic(), entry.aggregateId(), entry.payloadJson());
        outboxRepository.markAsProcessed(entry.id(), clock.instant());
    });
}
```

## Step 4: the idempotent consumer

```java
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import tools.jackson.databind.ObjectMapper;

@RetryableTopic(
        attempts = "3",
        backOff = @BackOff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
        dltTopicSuffix = ".dlt")
@KafkaListener(topics = "${app.kafka.topics.decisions}", groupId = "${spring.application.name}")
@Transactional
public void onMessage(ConsumerRecord<String, String> record) {
    var event = objectMapper.readValue(record.value(), DecisionIntegrationEvent.class);

    if (inboxRepository.markProcessed(event.eventId(), record.topic()) == 0) {
        return; // already handled; at-least-once delivery is normal, not an error
    }

    useCase.handle(new ProcessDecisionCommand(/* ... */));
}
```

Two details that are easy to get wrong and expensive to debug:

- **The annotation is `@BackOff` from `org.springframework.kafka.annotation`, and the attribute is
  `backOff`.** Spring Kafka 4.0 dropped its Spring Retry dependency; the old
  `org.springframework.retry.annotation.Backoff` with `backoff =` no longer exists here and is
  blocked by Checkstyle.
- **Deduplicate with an atomic insert, not a check-then-act.** `existsByEventId(...)` followed by
  an insert is a race: two consumers both see "not present" and both process the message.

```java
@Modifying
@Query("""
        INSERT INTO inbox_events (event_id, topic, processed_at)
        VALUES (:eventId, :topic, now())
        ON CONFLICT (event_id) DO NOTHING
        """)
int markProcessed(@Param("eventId") UUID eventId, @Param("topic") String topic);
```

A return of `1` means this consumer won the race and should process; `0` means it is a duplicate.

## Step 5: integration tests

```java
@SpringBootTest
@Testcontainers
class SubmissionMessagingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:4.0.0");
}
```

Use `org.testcontainers.kafka.KafkaContainer` (Testcontainers 2.x), not the deprecated
`org.testcontainers.containers.KafkaContainer`. The Maven artifact is
`org.testcontainers:testcontainers-kafka` — the 1.x name `org.testcontainers:kafka` does not
resolve under Boot 4.

Required tests:

1. **Outbox round trip** — a business operation writes a row; the relay publishes it; the row is
   marked processed.
2. **Duplicate delivery** — send the identical event twice, assert the effect happened exactly
   once and nothing threw.
3. **Poison message** — send an unparseable payload, assert it lands on `.dlt` and the partition
   keeps moving.

Assert with `await().atMost(...).untilAsserted(...)`, never `Thread.sleep`.

## Validation checklist

- [ ] `domain/` imports nothing from `org.apache.kafka`, `org.springframework.kafka` or any Jackson
- [ ] No `KafkaTemplate.send` outside the relay
- [ ] Every send passes the aggregate id as the key
- [ ] The outbox query contains `FOR UPDATE SKIP LOCKED`
- [ ] Deduplication is `ON CONFLICT DO NOTHING` with a row count check, not `existsBy`
- [ ] `@BackOff` is imported from `org.springframework.kafka.annotation`
- [ ] Jackson imports are `tools.jackson.*`
- [ ] No `Thread.sleep` in any test
- [ ] `./mvnw verify` output pasted, not summarised
