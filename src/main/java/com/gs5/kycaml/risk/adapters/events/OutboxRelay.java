package com.gs5.kycaml.risk.adapters.events;

import com.gs5.kycaml.risk.adapters.projection.Projection;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Relays the transactional outbox to the projector. A batch is claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several relays never take the same row, and the claimed rows
 * of one profile are projected in version order on the partition of that profile. Partitions run in
 * parallel on virtual threads. A row that cannot be projected stays pending with its attempt
 * counted, until it has failed {@value #MAX_ATTEMPTS} times; then it is no longer claimed and stays
 * visible as a pending row with a high attempt count.
 */
public class OutboxRelay {

    private static final int PARTITIONS = 16;
    /** A row that failed this often is left pending but no longer claimed, so it cannot starve the rest. */
    private static final int MAX_ATTEMPTS = 10;

    private final JdbcClient jdbc;
    private final ViewProjector projector;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, ViewProjector projector, TransactionTemplate transaction, Clock clock,
                       int batchSize) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Relays one batch and returns how many events were delivered. */
    public int runOnce() {
        // execute() is declared nullable only for callbacks that return null; this one never does.
        return Optional.ofNullable(transaction.execute(unused -> relayClaimedBatch())).orElse(0);
    }

    private int relayClaimedBatch() {
        var claimed = jdbc.sql("""
                        SELECT event_id, profile_id, tenant_id, customer_number, aggregate_version, view::text AS view
                        FROM command.outbox
                        WHERE status = 'PENDING' AND attempts < :maxAttempts
                        ORDER BY created_at, aggregate_version
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("maxAttempts", MAX_ATTEMPTS)
                .param("batchSize", batchSize)
                .query((rs, row) -> new Claimed(rs.getObject("event_id", UUID.class),
                        rs.getObject("profile_id", UUID.class),
                        new Projection(rs.getObject("tenant_id", UUID.class), rs.getString("customer_number"),
                                rs.getLong("aggregate_version"), rs.getString("view"))))
                .list();
        var delivered = new ConcurrentLinkedQueue<UUID>();
        var partitions = claimed.stream().collect(Collectors.groupingBy(
                row -> Math.floorMod(row.profileId().hashCode(), PARTITIONS)));
        var workers = partitions.values().stream()
                .map(rows -> Thread.ofVirtual().start(() -> rows.stream()
                        .sorted(Comparator.comparingLong(row -> row.projection().version()))
                        .forEach(row -> projector.project(row.projection())
                                .peek(projected -> delivered.add(row.eventId())))))
                .toList();
        workers.forEach(this::await);
        markDelivered(List.copyOf(delivered));
        // Whatever was claimed and not delivered counts an attempt, including a worker that died.
        countAttempt(claimed.stream().map(Claimed::eventId).filter(id -> !delivered.contains(id)).toList());
        return delivered.size();
    }

    private void await(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void markDelivered(List<UUID> eventIds) {
        if (!eventIds.isEmpty()) {
            jdbc.sql("UPDATE command.outbox SET status = 'DELIVERED', delivered_at = :now WHERE event_id IN (:ids)")
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .param("ids", eventIds)
                    .update();
        }
    }

    private void countAttempt(List<UUID> eventIds) {
        if (!eventIds.isEmpty()) {
            jdbc.sql("UPDATE command.outbox SET attempts = attempts + 1 WHERE event_id IN (:ids)")
                    .param("ids", eventIds)
                    .update();
        }
    }

    private record Claimed(UUID eventId, UUID profileId, Projection projection) { }
}
