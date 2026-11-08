package com.gs5.kycaml.risk.adapters.events;

import com.gs5.kycaml.risk.adapters.projection.Projection;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Relays the transactional outbox to the projector, holding no connection while it waits.
 *
 * <p>A round has two steps. The batch is first <em>leased</em> in a short transaction of its own
 * ({@code FOR UPDATE SKIP LOCKED}, so several relays never take the same row) and that connection is
 * released. The leased rows are then split by profile, so one profile stays in version order, into at
 * most {@code connectionBudget} chunks, and each chunk is projected and marked delivered in one short
 * transaction on one connection, never waiting for a second.
 *
 * <p>A chunk that cannot run for a reason that is not the event's own (the pool timed out, a lock or
 * query timed out, a deadlock, the commit failed) rolls back, counts nothing and gives its rows back
 * at once. A row whose own projection fails counts an attempt, keeps its lease as a pause, and stops
 * being claimed after {@value #MAX_ATTEMPTS} attempts. A relay that dies leaves leases that run out;
 * nothing is lost. A relay only ever marks or counts rows it still holds, so a relay whose lease was
 * taken over changes nothing on them.
 */
public class OutboxRelay {

    /** A row that failed this often is left pending but no longer claimed, so it cannot starve the rest. */
    private static final int MAX_ATTEMPTS = 10;
    private static final int MINIMUM_BUDGET = 2;
    private static final int DEFAULT_BUDGET = 4;
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final JdbcClient jdbc;
    private final ViewProjector projector;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final int connectionBudget;
    private final Duration lease;
    private final UUID owner = UUID.randomUUID();

    /** A relay with the default budget of a small service and a lease long enough for any chunk. */
    public OutboxRelay(JdbcClient jdbc, ViewProjector projector, TransactionTemplate transaction, Clock clock,
                       int batchSize) {
        this(jdbc, projector, transaction, clock, batchSize, DEFAULT_BUDGET, DEFAULT_LEASE);
    }

    public OutboxRelay(JdbcClient jdbc, ViewProjector projector, TransactionTemplate transaction, Clock clock,
                       int batchSize, int connectionBudget, Duration lease) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = batchSize;
        this.connectionBudget = Math.max(1, connectionBudget);
        this.lease = lease;
    }

    /** The relay's share of a connection pool: half of it, and never fewer than two. */
    public static int defaultConnectionBudget(int poolSize) {
        return Math.max(MINIMUM_BUDGET, poolSize / 2);
    }

    /** Relays one batch and returns how many events were delivered. */
    public int runOnce() {
        var chunks = chunksOf(leaseBatch());
        var delivered = new AtomicInteger();
        var workers = chunks.stream()
                .map(chunk -> Thread.ofVirtual().start(() -> delivered.addAndGet(projectChunk(chunk))))
                .toList();
        workers.forEach(this::await);
        return delivered.get();
    }

    /** Leases a batch in a transaction of its own, which is over before any chunk takes a connection. */
    private List<Claimed> leaseBatch() {
        // execute() is declared nullable only for callbacks that return null; this one never does.
        return Optional.ofNullable(transaction.execute(unused -> jdbc.sql("""
                        UPDATE command.outbox
                           SET locked_until = now() + make_interval(secs => :leaseSeconds), locked_by = :owner
                         WHERE event_id IN (
                               SELECT event_id FROM command.outbox
                                WHERE status = 'PENDING' AND attempts < :maxAttempts
                                  AND (locked_until IS NULL OR locked_until < now())
                                ORDER BY created_at, aggregate_version
                                LIMIT :batchSize
                                FOR UPDATE SKIP LOCKED)
                        RETURNING event_id, profile_id, tenant_id, customer_number, aggregate_version,
                                  view::text AS view
                        """)
                .param("leaseSeconds", lease.toMillis() / MILLIS_PER_SECOND)
                .param("owner", owner)
                .param("maxAttempts", MAX_ATTEMPTS)
                .param("batchSize", batchSize)
                .query((rs, row) -> new Claimed(rs.getObject("event_id", UUID.class),
                        rs.getObject("profile_id", UUID.class),
                        new Projection(rs.getObject("tenant_id", UUID.class), rs.getString("customer_number"),
                                rs.getLong("aggregate_version"), rs.getString("view"))))
                .list())).orElse(List.of());
    }

    /** One profile always lands in one chunk, in version order; there are never more chunks than the budget. */
    private List<List<Claimed>> chunksOf(List<Claimed> leased) {
        var profiles = leased.stream().collect(Collectors.groupingBy(Claimed::profileId)).values().stream()
                .map(rows -> rows.stream()
                        .sorted(Comparator.comparingLong((Claimed row) -> row.projection().version())).toList())
                .toList();
        var count = Math.min(connectionBudget, profiles.size());
        var chunks = IntStream.range(0, count).mapToObj(i -> new ArrayList<Claimed>()).toList();
        for (var index = 0; index < profiles.size(); index++) {
            chunks.get(index % count).addAll(profiles.get(index));
        }
        return List.copyOf(chunks);
    }

    private int projectChunk(List<Claimed> chunk) {
        try {
            return Optional.ofNullable(transaction.execute(unused -> projectAndMark(chunk))).orElse(0);
        } catch (TransactionException | DataAccessException notTheEventsFault) {
            release(chunk);
            return 0;
        }
    }

    private int projectAndMark(List<Claimed> chunk) {
        var delivered = new ArrayList<UUID>();
        var failed = new ArrayList<UUID>();
        for (var row : chunk) {
            (projector.project(row.projection()).isSuccess() ? delivered : failed).add(row.eventId());
        }
        countAttempt(failed);
        return markDelivered(delivered);
    }

    /**
     * Marks delivered only the rows this relay still holds (R-14): a relay whose lease ran out and was
     * taken over changes nothing. Returns how many rows it marked.
     */
    private int markDelivered(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE command.outbox SET status = 'DELIVERED', delivered_at = :now
                         WHERE event_id IN (:ids) AND locked_by = :owner
                        """)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("ids", eventIds)
                .param("owner", owner)
                .update();
    }

    /** Counts an attempt only on the rows this relay still holds (R-14), so no row is counted twice. */
    private void countAttempt(List<UUID> eventIds) {
        if (!eventIds.isEmpty()) {
            jdbc.sql("UPDATE command.outbox SET attempts = attempts + 1 WHERE event_id IN (:ids) AND locked_by = :owner")
                    .param("ids", eventIds)
                    .param("owner", owner)
                    .update();
        }
    }

    /** Gives the rows of a failed chunk back at once. If even that fails, the lease runs out by itself. */
    private void release(List<Claimed> chunk) {
        try {
            transaction.executeWithoutResult(unused -> jdbc.sql("""
                            UPDATE command.outbox SET locked_until = NULL, locked_by = NULL
                             WHERE event_id IN (:ids) AND locked_by = :owner AND status = 'PENDING'
                            """)
                    .param("ids", chunk.stream().map(Claimed::eventId).toList())
                    .param("owner", owner)
                    .update());
        } catch (TransactionException | DataAccessException leaseRunsOut) {
            return;
        }
    }

    private void await(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Claimed(UUID eventId, UUID profileId, Projection projection) { }
}
