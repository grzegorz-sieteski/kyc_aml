package com.gs5.kycaml.risk.adapters;

import com.gs5.kycaml.risk.adapters.events.OutboxRelay;
import com.gs5.kycaml.risk.adapters.persistence.RiskProfileSqlAdapter;
import com.gs5.kycaml.risk.adapters.projection.Projection;
import com.gs5.kycaml.risk.adapters.projection.ProjectionFailure;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskCommand;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskUseCase;
import com.gs5.kycaml.shared.kernel.Result;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * SPIKE, not a test of the product: compares four ways to relieve the connection pressure of the
 * outbox relay (state board, "relay connection pressure"). It is neither a *Test nor a *IT, so no
 * Maven gate runs it. Run it on purpose with
 * <pre>./mvnw -B test -Dtest=RelayPressureSpike</pre>
 * It uses the real migrations, the real {@link ViewProjector}, the real {@link OutboxRelay} (as the
 * baseline) and a real PostgreSQL. Nothing in src/main is changed: the three candidate relays live
 * in this file. The lease columns of candidate 2 are added by this spike only.
 *
 * <p>Every candidate gets the same connection budget B = max(2, pool / 2): the relay may use at most
 * B connections at the same time, whatever its shape. The baseline is the current relay, which
 * uses up to 1 + 16.
 */
class RelayPressureSpike extends RiskDatabaseSupport {

    // ---- the experiment's knobs (synthetic; stated in the ADR addendum) -------------------------
    private static final int PROFILES = 1_000;
    private static final int VERSIONS = 2;
    private static final int BATCH = 100;
    private static final int MAX_ATTEMPTS = 10;
    /** Hikari's lowest allowed connection timeout; production default is 30 000. */
    private static final long CONNECTION_TIMEOUT_MS = 250;
    /** A request keeps its connection this long after the write (stands for the rest of the request). */
    private static final int REQUEST_HOLD_MS = 10;
    private static final int LOAD_FACTOR = 2;
    private static final double LEASE_SECONDS = 3.0;
    private static final long DRAIN_CAP_SECONDS = 30;
    private static final long LOAD_ONLY_SECONDS = 12;
    private static final long LOADED_WINDOW_SECONDS = 12;
    private static final int LOADED_PROFILES = 10_000;
    private static final int LOADED_REPETITIONS = 2;

    private static final List<String> CANDIDATES = List.of("0-baseline", "1-bounded", "2-lease", "2b-chunked", "3-claimers");

    private final StringBuilder report = new StringBuilder();

    // =============================================================================== metering

    enum Category { NONE, RELAY, REQUEST }

    static final InheritableThreadLocal<Category> CATEGORY = new InheritableThreadLocal<>() {
        @Override
        protected Category initialValue() {
            return Category.NONE;
        }
    };

    static final class Stats {
        final AtomicInteger held = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final LongAdder acquires = new LongAdder();
        final LongAdder holdNanos = new LongAdder();
        final AtomicLong maxHoldNanos = new AtomicLong();
        final AtomicLong maxWaitNanos = new AtomicLong();
        final LongAdder acquireFailures = new LongAdder();

        void reset() {
            peak.set(held.get());
            acquires.reset();
            holdNanos.reset();
            maxHoldNanos.set(0);
            maxWaitNanos.set(0);
            acquireFailures.reset();
        }
    }

    /** Counts, per thread category, the connections borrowed, how long they were held and how long the borrower waited. */
    static final class MeteredDataSource extends DelegatingDataSource {
        final Stats relay = new Stats();
        final Stats request = new Stats();
        private final Stats ignored = new Stats();

        MeteredDataSource(DataSource target) {
            super(target);
        }

        void reset() {
            relay.reset();
            request.reset();
        }

        @Override
        public Connection getConnection() throws SQLException {
            var stats = switch (CATEGORY.get()) {
                case RELAY -> relay;
                case REQUEST -> request;
                case NONE -> ignored;
            };
            var asked = System.nanoTime();
            Connection real;
            try {
                real = Objects.requireNonNull(super.getConnection());
            } catch (SQLException timedOut) {
                stats.acquireFailures.increment();
                throw timedOut;
            }
            var got = System.nanoTime();
            stats.acquires.increment();
            stats.maxWaitNanos.accumulateAndGet(got - asked, Math::max);
            stats.peak.accumulateAndGet(stats.held.incrementAndGet(), Math::max);
            var closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                            stats.held.decrementAndGet();
                            var held = System.nanoTime() - got;
                            stats.holdNanos.add(held);
                            stats.maxHoldNanos.accumulateAndGet(held, Math::max);
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException failed) {
                            throw failed.getCause();
                        }
                    });
        }
    }

    // =============================================================================== the rig

    /** One application instance: its own pool, metered, with its own transaction manager. */
    final class Rig implements AutoCloseable {
        final HikariDataSource pool;
        final MeteredDataSource metered;
        final JdbcClient jdbc;
        final TransactionTemplate tx;
        final TransactionTemplate nested;
        final RiskProfileSqlAdapter adapter;
        final AtomicInteger errors = new AtomicInteger();

        Rig(String name, int poolSize) {
            var base = (PGSimpleDataSource) RelayPressureSpike.this.dataSource;
            var own = new PGSimpleDataSource();
            own.setUrl(base.getUrl());
            own.setUser(base.getUser());
            own.setPassword(base.getPassword());
            own.setApplicationName(name);
            var config = new HikariConfig();
            config.setDataSource(own);
            config.setPoolName(name);
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(poolSize);
            config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
            config.setValidationTimeout(CONNECTION_TIMEOUT_MS);
            pool = new HikariDataSource(config);
            metered = new MeteredDataSource(pool);
            jdbc = JdbcClient.create(metered);
            var manager = new DataSourceTransactionManager(metered);
            tx = new TransactionTemplate(manager);
            nested = new TransactionTemplate(manager);
            nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            adapter = new RiskProfileSqlAdapter(jdbc, json, tx);
        }

        /** The real projector. {@code joined=false}: it runs in a savepoint when a transaction is open. */
        CountingProjector projector(Counters counters, Runnable before, boolean joined) {
            return new CountingProjector(jdbc, json, joined ? tx : nested, clockAt(NOW), counters, before);
        }

        EvaluateCustomerRiskUseCase evaluate() {
            return new EvaluateCustomerRiskUseCase(adapter, bookingCentres, ids, clockAt(NOW));
        }

        @Override
        public void close() {
            pool.close();
        }
    }

    static final class Counters {
        final LongAdder applied = new LongAdder();
        final LongAdder gated = new LongAdder();
        final LongAdder failed = new LongAdder();
    }

    static final class CountingProjector extends ViewProjector {
        private final Counters counters;
        private final Runnable before;

        CountingProjector(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, Clock clock, Counters counters,
                          Runnable before) {
            super(jdbc, json, tx, clock);
            this.counters = counters;
            this.before = before;
        }

        @Override
        public Result<Boolean, ProjectionFailure> project(Projection projection) {
            before.run();
            var result = super.project(projection);
            if (result.isFailure()) {
                counters.failed.increment();
            } else if (result.orElse(false)) {
                counters.applied.increment();
            } else {
                counters.gated.increment();
            }
            return result;
        }
    }

    // =============================================================================== candidates

    interface Relay {
        int runOnce();
    }

    record Claimed(UUID eventId, UUID profileId, Projection projection) { }

    private static final String COLUMNS =
            "event_id, profile_id, tenant_id, customer_number, aggregate_version, view::text AS view";

    private static final String LEASE_CLAIM_SQL = """
            UPDATE command.outbox
               SET locked_until = now() + make_interval(secs => :lease), locked_by = :me
             WHERE event_id IN (
                   SELECT event_id FROM command.outbox
                    WHERE status = 'PENDING' AND attempts < :max
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY created_at, aggregate_version
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED)
            RETURNING """ + " " + COLUMNS;

    private static Claimed claimed(ResultSet rs, int row) throws SQLException {
        return new Claimed(rs.getObject("event_id", UUID.class), rs.getObject("profile_id", UUID.class),
                new Projection(rs.getObject("tenant_id", UUID.class), rs.getString("customer_number"),
                        rs.getLong("aggregate_version"), rs.getString("view")));
    }

    private static Collection<List<Claimed>> byProfile(List<Claimed> rows) {
        return rows.stream().collect(Collectors.groupingBy(Claimed::profileId)).values().stream()
                .map(group -> group.stream()
                        .sorted(Comparator.comparingLong((Claimed row) -> row.projection().version())).toList())
                .toList();
    }

    private static boolean delivers(ViewProjector projector, Projection projection) {
        try {
            return projector.project(projection).isSuccess();
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    /** Projects the groups on at most {@code workers} virtual threads, one profile always in version order. */
    private static List<UUID> projectBounded(Collection<List<Claimed>> groups, int workers, ViewProjector projector) {
        var queue = new ConcurrentLinkedQueue<>(groups);
        var delivered = new ConcurrentLinkedQueue<UUID>();
        var threads = IntStream.range(0, Math.max(1, workers)).mapToObj(i -> Thread.ofVirtual().start(() -> {
            for (var group = queue.poll(); group != null; group = queue.poll()) {
                for (var row : group) {
                    if (delivers(projector, row.projection())) {
                        delivered.add(row.eventId());
                    }
                }
            }
        })).toList();
        threads.forEach(RelayPressureSpike::join);
        return List.copyOf(delivered);
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void markDelivered(JdbcClient jdbc, List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("""
                    UPDATE command.outbox
                    SET status = 'DELIVERED', delivered_at = now(), locked_until = NULL, locked_by = NULL
                    WHERE event_id IN (:ids)
                    """).param("ids", ids).update();
        }
    }

    private static void countAttempt(JdbcClient jdbc, List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("""
                    UPDATE command.outbox SET attempts = attempts + 1, locked_until = NULL, locked_by = NULL
                    WHERE event_id IN (:ids)
                    """).param("ids", ids).update();
        }
    }

    private static List<UUID> undelivered(List<Claimed> rows, List<UUID> delivered) {
        var done = new java.util.HashSet<>(delivered);
        return rows.stream().map(Claimed::eventId).filter(id -> !done.contains(id)).toList();
    }

    /** Candidate 1: today's relay (long claim transaction) with the workers bounded to the budget. */
    static final class BoundedWorkers implements Relay {
        private final Rig rig;
        private final ViewProjector projector;
        private final int workers;

        BoundedWorkers(Rig rig, ViewProjector projector, int budget) {
            this.rig = rig;
            this.projector = projector;
            this.workers = budget - 1;   // one connection of the budget is the claim transaction itself
        }

        @Override
        public int runOnce() {
            return Objects.requireNonNull(rig.tx.execute(status -> {
                var rows = rig.jdbc.sql("SELECT " + COLUMNS + """
                                 FROM command.outbox
                                WHERE status = 'PENDING' AND attempts < :max
                                ORDER BY created_at, aggregate_version
                                LIMIT :batch
                                FOR UPDATE SKIP LOCKED
                                """).param("max", MAX_ATTEMPTS).param("batch", BATCH)
                        .query(RelayPressureSpike::claimed).list();
                var delivered = projectBounded(byProfile(rows), workers, projector);
                markDelivered(rig.jdbc, delivered);
                countAttempt(rig.jdbc, undelivered(rows, delivered));
                return delivered.size();
            }));
        }
    }

    /** Candidate 2: claim into a lease in a short transaction, project outside any claim, mark in a second one. */
    static final class Lease implements Relay {
        private final Rig rig;
        private final ViewProjector projector;
        private final int workers;
        private final UUID me = UUID.randomUUID();

        Lease(Rig rig, ViewProjector projector, int budget) {
            this.rig = rig;
            this.projector = projector;
            this.workers = budget;   // no claim connection is held while projecting
        }

        @Override
        public int runOnce() {
            var rows = Objects.requireNonNull(rig.tx.execute(status -> rig.jdbc.sql(LEASE_CLAIM_SQL)
                    .param("lease", LEASE_SECONDS).param("me", me).param("max", MAX_ATTEMPTS).param("batch", BATCH)
                    .query(RelayPressureSpike::claimed).list()));
            var delivered = projectBounded(byProfile(rows), workers, projector);
            rig.tx.executeWithoutResult(status -> markDelivered(rig.jdbc, delivered));
            rig.tx.executeWithoutResult(status -> countAttempt(rig.jdbc, undelivered(rows, delivered)));
            return delivered.size();
        }
    }

    /**
     * Candidate 2b: the lease of candidate 2, but each worker projects a whole chunk of the batch in ONE
     * short transaction (one connection, never waiting for a second) and marks it in that same transaction.
     */
    static final class LeaseChunked implements Relay {
        private final Rig rig;
        private final ViewProjector projector;
        private final int workers;
        private final UUID me = UUID.randomUUID();

        LeaseChunked(Rig rig, ViewProjector projector, int budget) {
            this.rig = rig;
            this.projector = projector;
            this.workers = budget;
        }

        @Override
        public int runOnce() {
            var rows = Objects.requireNonNull(rig.tx.execute(status -> rig.jdbc.sql(LEASE_CLAIM_SQL)
                    .param("lease", LEASE_SECONDS).param("me", me).param("max", MAX_ATTEMPTS).param("batch", BATCH)
                    .query(RelayPressureSpike::claimed).list()));
            var chunks = new ArrayList<List<Claimed>>();
            IntStream.range(0, workers).forEach(i -> chunks.add(new ArrayList<>()));
            var next = 0;
            for (var group : byProfile(rows)) {
                chunks.get(next++ % workers).addAll(group);
            }
            var total = new AtomicInteger();
            chunks.stream().filter(chunk -> !chunk.isEmpty())
                    .map(chunk -> Thread.ofVirtual().start(() -> total.addAndGet(projectChunk(chunk)))).toList()
                    .forEach(RelayPressureSpike::join);
            return total.get();
        }

        private int projectChunk(List<Claimed> chunk) {
            try {
                return Objects.requireNonNull(rig.tx.execute(status -> {
                    var delivered = new ArrayList<UUID>();
                    for (var row : chunk) {
                        if (delivers(projector, row.projection())) {
                            delivered.add(row.eventId());
                        }
                    }
                    markDelivered(rig.jdbc, delivered);
                    countAttempt(rig.jdbc, undelivered(chunk, delivered));
                    return delivered.size();
                }));
            } catch (RuntimeException unexpected) {
                rig.errors.incrementAndGet();   // the lease simply expires; no attempt is counted
                return 0;
            }
        }
    }

    /** Candidate 3: B claimers, each owning a hash partition of the profiles, projecting on its own claim connection. */
    static final class Claimers implements Relay {
        private final Rig rig;
        private final ViewProjector projector;
        private final int claimers;

        Claimers(Rig rig, ViewProjector projector, int budget) {
            this.rig = rig;
            this.projector = projector;
            this.claimers = budget;
        }

        @Override
        public int runOnce() {
            var total = new AtomicInteger();
            IntStream.range(0, claimers).mapToObj(k -> Thread.ofVirtual().start(() -> total.addAndGet(claimPartition(k))))
                    .toList().forEach(RelayPressureSpike::join);
            return total.get();
        }

        private int claimPartition(int partition) {
            try {
                return Objects.requireNonNull(rig.tx.execute(status -> {
                    var rows = rig.jdbc.sql("SELECT " + COLUMNS + """
                                     FROM command.outbox
                                    WHERE status = 'PENDING' AND attempts < :max
                                      AND (hashtext(profile_id::text)::bigint & 2147483647) % :parts = :part
                                    ORDER BY created_at, aggregate_version
                                    LIMIT :batch
                                    FOR UPDATE SKIP LOCKED
                                    """).param("max", MAX_ATTEMPTS).param("parts", claimers).param("part", partition)
                            .param("batch", BATCH).query(RelayPressureSpike::claimed).list();
                    var delivered = new ArrayList<UUID>();
                    for (var group : byProfile(rows)) {
                        for (var row : group) {
                            if (delivers(projector, row.projection())) {
                                delivered.add(row.eventId());
                            }
                        }
                    }
                    markDelivered(rig.jdbc, delivered);
                    countAttempt(rig.jdbc, undelivered(rows, delivered));
                    return delivered.size();
                }));
            } catch (RuntimeException unexpected) {
                rig.errors.incrementAndGet();
                return 0;
            }
        }
    }

    /** The relay as shipped, on the same pool. */
    static final class Shipped implements Relay {
        private final OutboxRelay relay;
        private final Rig rig;

        Shipped(Rig rig, ViewProjector projector, Clock clock) {
            this.rig = rig;
            this.relay = new OutboxRelay(rig.jdbc, projector, rig.tx, clock, BATCH);
        }

        @Override
        public int runOnce() {
            try {
                return relay.runOnce();
            } catch (RuntimeException unexpected) {
                rig.errors.incrementAndGet();
                return 0;
            }
        }
    }

    private static int budget(int pool) {
        return Math.max(2, pool / 2);
    }

    /** A relay that survives its own failures (a pool timeout, a dead connection) and counts them. */
    private static Relay safe(Rig rig, Relay relay) {
        return () -> {
            try {
                return relay.runOnce();
            } catch (RuntimeException unexpected) {
                rig.errors.incrementAndGet();
                return 0;
            }
        };
    }

    private Relay build(String candidate, Rig rig, Counters counters, Runnable before) {
        return safe(rig, buildRaw(candidate, rig, counters, before));
    }

    private Relay buildRaw(String candidate, Rig rig, Counters counters, Runnable before) {
        var budget = budget(rig.pool.getMaximumPoolSize());
        return switch (candidate) {
            case "0-baseline" -> new Shipped(rig, rig.projector(counters, before, true), clockAt(NOW));
            case "1-bounded" -> new BoundedWorkers(rig, rig.projector(counters, before, true), budget);
            case "2-lease" -> new Lease(rig, rig.projector(counters, before, true), budget);
            case "2b-chunked" -> new LeaseChunked(rig, rig.projector(counters, before, false), budget);
            case "3-claimers" -> new Claimers(rig, rig.projector(counters, before, false), budget);
            case "3-claimers-joined" -> new Claimers(rig, rig.projector(counters, before, true), budget);
            default -> throw new IllegalArgumentException(candidate);
        };
    }

    // =============================================================================== the load

    /** Request threads: write a profile (the real use case), optionally project in the same transaction, hold the connection. */
    final class RequestLoad {
        private final Rig rig;
        private final int threads;
        private final boolean projectInTransaction;
        private final Counters counters = new Counters();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        private final LongAdder failures = new LongAdder();
        private final List<Thread> workers = new ArrayList<>();
        private long startedAt;

        RequestLoad(Rig rig, int threads, boolean projectInTransaction) {
            this.rig = rig;
            this.threads = threads;
            this.projectInTransaction = projectInTransaction;
        }

        void start() {
            startedAt = System.nanoTime();
            var projector = rig.projector(counters, () -> { }, false);
            var evaluate = rig.evaluate();
            for (var t = 0; t < threads; t++) {
                var id = t;
                var worker = new Thread(() -> {
                    CATEGORY.set(Category.REQUEST);
                    for (var i = 0; running.get(); i++) {
                        var number = "R-" + id + "-" + i;
                        var began = System.nanoTime();
                        try {
                            var ok = request(evaluate, projector, employed(number), number);
                            if (!ok) {
                                failures.increment();
                            }
                        } catch (RuntimeException timedOut) {
                            failures.increment();
                        }
                        latencies.add(System.nanoTime() - began);
                    }
                });
                workers.add(worker);
                worker.start();
            }
        }

        private boolean request(EvaluateCustomerRiskUseCase evaluate, ViewProjector projector,
                                EvaluateCustomerRiskCommand command, String number) {
            return Objects.requireNonNull(rig.tx.execute(status -> {
                var written = evaluate.execute(ONBOARDING_IN_DE, command);
                if (written.isFailure()) {
                    return false;
                }
                if (projectInTransaction && !projectOwnRows(rig, projector, number, status)) {
                    return false;
                }
                hold(rig);
                return true;
            }));
        }

        String stop() {
            running.set(false);
            workers.forEach(RelayPressureSpike::join);
            var seconds = (System.nanoTime() - startedAt) / 1e9;
            var sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            var done = sorted.length;
            return String.format(Locale.ROOT, "%d req (%.0f/s), p50 %d ms, p99 %d ms, max %d ms, %d failed",
                    done, done / seconds, percentile(sorted, 0.50), percentile(sorted, 0.99),
                    percentile(sorted, 1.0), failures.sum());
        }
    }

    private void hold(Rig rig) {
        rig.jdbc.sql("SELECT pg_sleep(:s)::text").param("s", REQUEST_HOLD_MS / 1000.0).query(String.class).single();
    }

    private static long percentile(long[] sortedNanos, double q) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        var index = (int) Math.min(sortedNanos.length - 1, Math.ceil(q * sortedNanos.length) - 1);
        return TimeUnit.NANOSECONDS.toMillis(sortedNanos[Math.max(0, index)]);
    }

    /** Candidate 4: project the rows the write just produced inside the write transaction. */
    private boolean projectOwnRows(Rig rig, ViewProjector projector, String number,
                                   org.springframework.transaction.TransactionStatus status) {
        var rows = rig.jdbc.sql("SELECT " + COLUMNS + """
                         FROM command.outbox
                        WHERE status = 'PENDING' AND customer_number = :number
                        ORDER BY aggregate_version
                        """).param("number", number).query(RelayPressureSpike::claimed).list();
        var delivered = new ArrayList<UUID>();
        for (var row : rows) {
            if (!delivers(projector, row.projection())) {
                status.setRollbackOnly();
                return false;
            }
            delivered.add(row.eventId());
        }
        markDelivered(rig.jdbc, delivered);
        return true;
    }

    // =============================================================================== the data

    private String seedView;

    private void prepareDatabase() {
        freshDatabaseNow();
        jdbc.sql("ALTER TABLE command.outbox ADD COLUMN locked_until TIMESTAMPTZ, ADD COLUMN locked_by UUID").update();
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, employed("SEED"));
        seedView = jdbc.sql("SELECT view::text FROM command.outbox WHERE customer_number = 'SEED'")
                .query(String.class).single();
        jdbc.sql("DELETE FROM command.outbox").update();
    }

    private void freshDatabaseNow() {
        try {
            freshDatabase();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private void seed(int profiles, int versions) {
        jdbc.sql("""
                INSERT INTO command.outbox (event_id, profile_id, tenant_id, customer_number, aggregate_version,
                                            event_type, payload, view, status, attempts, created_at)
                SELECT gen_random_uuid(), md5('p' || n)::uuid, CAST(:tenant AS uuid), 'B-' || n, v,
                       'CustomerRiskEvaluated', '{}'::jsonb, CAST(:view AS jsonb), 'PENDING', 0,
                       TIMESTAMPTZ '2026-11-07 11:00:00+00' + ((n * CAST(:versions AS int) + v) * interval '1 millisecond')
                FROM generate_series(1, CAST(:profiles AS int)) n, generate_series(1, CAST(:versions AS int)) v
                """).param("tenant", DE_CENTRE).param("view", seedView).param("profiles", profiles)
                .param("versions", versions).update();
    }

    private int pendingSeeded() {
        return jdbc.sql("SELECT count(*) FROM command.outbox WHERE customer_number LIKE 'B-%' AND status = 'PENDING' AND attempts < :max")
                .param("max", MAX_ATTEMPTS).query(Integer.class).single();
    }

    // =============================================================================== scenarios

    private void line(String text) {
        System.out.println(text);
        report.append(text).append('\n');
    }

    /** Drains the seeded backlog with one relay, with or without request load on the same pool. */
    private void drain(String candidate, int pool, boolean loaded) {
        prepareDatabase();
        var profiles = loaded ? LOADED_PROFILES : PROFILES;
        seed(profiles, VERSIONS);
        var total = profiles * VERSIONS;
        var counters = new Counters();
        try (var rig = new Rig("relay", pool)) {
            var relay = build(candidate, rig, counters, () -> { });
            var load = loaded ? new RequestLoad(rig, LOAD_FACTOR * pool, false) : null;
            if (load != null) {
                load.start();
            }
            rig.metered.reset();
            var began = System.nanoTime();
            // loaded: a fixed window over a backlog that cannot finish; idle: until the backlog is gone
            var deadline = began + TimeUnit.SECONDS.toNanos(loaded ? LOADED_WINDOW_SECONDS : DRAIN_CAP_SECONDS);
            CATEGORY.set(Category.RELAY);
            while ((loaded || pendingSeeded() > 0) && System.nanoTime() < deadline) {
                if (relay.runOnce() == 0) {
                    sleep(20);
                }
            }
            CATEGORY.set(Category.NONE);
            var elapsed = (System.nanoTime() - began) / 1e9;
            var requests = load == null ? "-" : load.stop();
            var delivered = total - pendingSeeded();
            var attempts = jdbc.sql("SELECT coalesce(sum(attempts), 0) FROM command.outbox WHERE customer_number LIKE 'B-%'")
                    .query(Integer.class).single();
            var parked = jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'PENDING' AND attempts >= :max")
                    .param("max", MAX_ATTEMPTS).query(Integer.class).single();
            var s = rig.metered.relay;
            line(String.format(Locale.ROOT,
                    "| %-10s | %2d | %-6s | %4d/%d | %6.1f | %6.0f | %2d | %5.0f | %6.1f | %5.0f | %3d | %4d (parked %d) | %2d | %s |",
                    candidate, pool, loaded ? "loaded" : "idle", delivered, total, elapsed, delivered / elapsed,
                    s.peak.get(), s.maxHoldNanos.get() / 1e6,
                    s.holdNanos.sum() / 1e6 / Math.max(1, delivered), s.maxWaitNanos.get() / 1e6,
                    s.acquireFailures.sum(), attempts, parked, rig.errors.get(), requests));
        }
    }

    /** Requests alone (no relay) and requests that project inside their own transaction (candidate 4). */
    private void requestsOnly(int pool, boolean projectInTransaction) {
        prepareDatabase();
        try (var rig = new Rig("requests", pool)) {
            var load = new RequestLoad(rig, LOAD_FACTOR * pool, projectInTransaction);
            rig.metered.reset();
            load.start();
            sleep(TimeUnit.SECONDS.toMillis(LOAD_ONLY_SECONDS));
            var summary = load.stop();
            var views = count("query.compliance_risk_view");
            var pending = jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'PENDING'")
                    .query(Integer.class).single();
            line(String.format(Locale.ROOT, "| %-22s | %2d | %s; views %d, outbox pending %d, request peak conns %d |",
                    projectInTransaction ? "4-in-write-tx" : "requests alone, no relay", pool, summary, views,
                    pending, rig.metered.request.peak.get()));
        }
    }

    /** Two relays at once on separate pools: every event delivered, how many projections were repeats. */
    private void twoRelays(String candidate) {
        prepareDatabase();
        seed(PROFILES, VERSIONS);
        var counters = new Counters();
        try (var a = new Rig("relayA", 10); var b = new Rig("relayB", 10)) {
            var relayA = build(candidate, a, counters, () -> { });
            var relayB = build(candidate, b, counters, () -> { });
            var began = System.nanoTime();
            var threads = List.of(drainLoop(relayA), drainLoop(relayB));
            threads.forEach(RelayPressureSpike::join);
            var elapsed = (System.nanoTime() - began) / 1e9;
            var delivered = jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'DELIVERED'")
                    .query(Integer.class).single();
            line(String.format(Locale.ROOT,
                    "| %-10s | %4d/%d delivered | %5.1f s | applied %d, gated (repeat or stale) %d, failed %d | errors %d |",
                    candidate, delivered, PROFILES * VERSIONS, elapsed, counters.applied.sum(), counters.gated.sum(),
                    counters.failed.sum(), a.errors.get() + b.errors.get()));
        }
    }

    private Thread drainLoop(Relay relay) {
        var thread = new Thread(() -> {
            CATEGORY.set(Category.RELAY);
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_CAP_SECONDS);
            while (pendingSeeded() > 0 && System.nanoTime() < deadline) {
                if (relay.runOnce() == 0) {
                    sleep(20);
                }
            }
        });
        thread.start();
        return thread;
    }

    /** The process dies between claim and mark: its connections are cut by the server while it is projecting. */
    private void crash(String candidate) {
        prepareDatabase();
        seed(100, 2);
        var counters = new Counters();
        var arrived = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        Runnable block = () -> {
            arrived.countDown();
            try {
                proceed.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try (var a = new Rig("relayA", 10)) {
            var relayA = build(candidate, a, counters, block);
            var dying = new Thread(() -> {
                CATEGORY.set(Category.RELAY);
                try {
                    relayA.runOnce();
                } catch (RuntimeException expected) {
                    a.errors.incrementAndGet();
                }
            });
            dying.start();
            awaitLatch(arrived);
            var claimedByA = jdbc.sql("""
                    SELECT count(*) FROM command.outbox
                    WHERE status = 'PENDING' AND (locked_until > now() OR locked_by IS NOT NULL)
                    """).query(Integer.class).single();
            jdbc.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'relayA'")
                    .query(Boolean.class).list();
            var killedAt = System.nanoTime();
            // the process is gone: its pool shuts down so nothing of A can run after the kill
            var shutdown = new Thread(a.pool::close);
            shutdown.start();
            sleep(300);
            proceed.countDown();
            join(dying);
            join(shutdown);
            var claimableAfterKill = jdbc.sql("""
                    SELECT count(*) FROM command.outbox
                    WHERE status = 'PENDING' AND (locked_until IS NULL OR locked_until < now())
                    """).query(Integer.class).single();
            try (var b = new Rig("relayB", 10)) {
                var relayB = build(candidate, b, counters, () -> { });
                var thread = drainLoop(relayB);
                join(thread);
                var recovery = (System.nanoTime() - killedAt) / 1e9;
                var stuck = jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'PENDING'")
                        .query(Integer.class).single();
                line(String.format(Locale.ROOT,
                        "| %-10s | rows leased at crash %3d | claimable by B right after the kill %3d/200 | recovered all in %4.1f s | still pending %d | gated repeats %d |",
                        candidate, claimedByA, claimableAfterKill, recovery, stuck, counters.gated.sum()));
            }
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("relay never reached the projector");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A row whose projection fails inside the database (a CHECK the spike adds), in the middle of a healthy batch. */
    private void poison(String candidate) {
        prepareDatabase();
        jdbc.sql("ALTER TABLE query.compliance_risk_view ADD CONSTRAINT ck_spike_poison CHECK (customer_number <> 'B-POISON')")
                .update();
        seed(10, 2);
        jdbc.sql("""
                INSERT INTO command.outbox (event_id, profile_id, tenant_id, customer_number, aggregate_version,
                                            event_type, payload, view, status, attempts, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), :tenant, 'B-POISON', 1, 'CustomerRiskEvaluated',
                        '{}'::jsonb, CAST(:view AS jsonb), 'PENDING', 0, TIMESTAMPTZ '2026-11-07 11:00:00.005+00')
                """).param("tenant", DE_CENTRE).param("view", seedView).update();
        var counters = new Counters();
        try (var rig = new Rig("relay", 10)) {
            var relay = build(candidate, rig, counters, () -> { });
            CATEGORY.set(Category.RELAY);
            for (var round = 0; round < 5; round++) {
                relay.runOnce();
            }
            CATEGORY.set(Category.NONE);
            var healthy = jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'DELIVERED' AND customer_number <> 'B-POISON'")
                    .query(Integer.class).single();
            var poisonState = jdbc.sql("SELECT status || ' after ' || attempts || ' attempts' FROM command.outbox WHERE customer_number = 'B-POISON'")
                    .query(String.class).single();
            var rolledBackBatch = jdbc.sql("SELECT coalesce(max(attempts), 0) FROM command.outbox WHERE customer_number <> 'B-POISON'")
                    .query(Integer.class).single();
            line(String.format(Locale.ROOT, "| %-17s | %2d/20 healthy delivered after 5 rounds | poison row: %s | relay exceptions %d | max attempts on healthy rows %d |",
                    candidate, healthy, poisonState, rig.errors.get(), rolledBackBatch));
        }
    }

    /** Candidate 4 with the same failing projection: the command must fail, and nothing of it may survive. */
    private void poisonInWriteTransaction() {
        prepareDatabase();
        jdbc.sql("ALTER TABLE query.compliance_risk_view ADD CONSTRAINT ck_spike_poison CHECK (customer_number <> 'B-POISON')")
                .update();
        try (var rig = new Rig("requests", 10)) {
            var projector = rig.projector(new Counters(), () -> { }, false);
            var evaluate = rig.evaluate();
            var healthy = Objects.requireNonNull(rig.tx.execute(status -> {
                var written = evaluate.execute(ONBOARDING_IN_DE, employed("B-OK"));
                return written.isSuccess() && projectOwnRows(rig, projector, "B-OK", status);
            }));
            var poisoned = Objects.requireNonNull(rig.tx.execute(status -> {
                var written = evaluate.execute(ONBOARDING_IN_DE, employed("B-POISON"));
                return written.isSuccess() && projectOwnRows(rig, projector, "B-POISON", status);
            }));
            line(String.format(Locale.ROOT,
                    "| 4-in-write-tx     | healthy command accepted: %s | poisoned command accepted: %s | profile rows left for the poisoned customer: %d | outbox rows left: %d |",
                    healthy, poisoned,
                    jdbc.sql("SELECT count(*) FROM command.risk_profile WHERE customer_number = 'B-POISON'")
                            .query(Integer.class).single(),
                    jdbc.sql("SELECT count(*) FROM command.outbox WHERE customer_number = 'B-POISON'")
                            .query(Integer.class).single()));
        }
    }

    /** Candidate 4: the process dies after the write and the projection, before the commit. */
    private void crashInWriteTransaction() {
        prepareDatabase();
        try (var rig = new Rig("requests", 10)) {
            var projector = rig.projector(new Counters(), () -> { }, false);
            var evaluate = rig.evaluate();
            try {
                rig.tx.executeWithoutResult(status -> {
                    evaluate.execute(ONBOARDING_IN_DE, employed("B-CRASH"));
                    projectOwnRows(rig, projector, "B-CRASH", status);
                    throw new IllegalStateException("process died before commit");
                });
            } catch (IllegalStateException expected) {
                // the transaction rolled back
            }
            line(String.format(Locale.ROOT,
                    "| 4-in-write-tx     | after a crash before commit: profile rows %d, outbox rows %d, view rows %d (all or nothing) |",
                    count("command.risk_profile"), count("command.outbox"), count("query.compliance_risk_view")));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // =============================================================================== the run

    @Test
    void compareTheFourCandidates() throws Exception {
        line("# Relay connection pressure: measured comparison");
        line("");
        line(String.format(Locale.ROOT,
                "Backlog %d events (%d profiles x %d versions), batch %d, connection timeout %d ms, request hold %d ms, %d request threads per pool slot, lease %.0f s. B = max(2, pool/2).",
                PROFILES * VERSIONS, PROFILES, VERSIONS, BATCH, CONNECTION_TIMEOUT_MS, REQUEST_HOLD_MS, LOAD_FACTOR,
                LEASE_SECONDS));
        line("");
        drain("0-baseline", 10, false);   // warm-up, discarded below
        report.setLength(0);
        line("## 1. Draining the backlog (idle = no other traffic; loaded = request threads on the same pool)");
        line("");
        line("| candidate  | pool | mode | delivered | secs | ev/s | peak conns | longest hold ms | conn-ms/event | max wait ms | acquire timeouts | attempts burnt | relay exc | requests during the drain |");
        line("|---|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|");
        for (var pool : List.of(10, 3)) {
            for (var loaded : List.of(false, true)) {
                for (var candidate : CANDIDATES) {
                    for (var repetition = 0; repetition < (loaded ? LOADED_REPETITIONS : 1); repetition++) {
                        drain(candidate, pool, loaded);
                    }
                }
            }
        }
        line("");
        line("## 1b. Candidate 4 and the request-only reference (no backlog exists: the view is written with the command)");
        line("");
        for (var pool : List.of(10, 3)) {
            requestsOnly(pool, false);
            requestsOnly(pool, true);
        }
        line("");
        line("## 2. Two relays at once (pool 10 each)");
        line("");
        for (var candidate : CANDIDATES) {
            twoRelays(candidate);
        }
        line("");
        line("## 3. Crash between claim and mark (relay A is cut off by the server while projecting, relay B takes over)");
        line("");
        for (var candidate : CANDIDATES) {
            crash(candidate);
        }
        crashInWriteTransaction();
        line("");
        line("## 4. One row that fails inside the database, in the middle of a healthy batch");
        line("");
        for (var candidate : List.of("0-baseline", "1-bounded", "2-lease", "2b-chunked", "3-claimers", "3-claimers-joined")) {
            poison(candidate);
        }
        poisonInWriteTransaction();
        Files.writeString(Path.of("target", "relay-pressure-results.md"), report.toString());
    }
}
