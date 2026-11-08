package com.gs5.kycaml.risk.adapters.events;

import com.gs5.kycaml.risk.adapters.RiskDatabaseSupport;
import com.gs5.kycaml.risk.adapters.projection.Projection;
import com.gs5.kycaml.risk.adapters.projection.ProjectionFailure;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/**
 * Shared rig of the outbox-relay specification (ADR addendum "relay connection pressure", candidate 2b).
 *
 * <p><b>Contract the tests pin on {@link OutboxRelay}</b> (stage 4 implements it):
 * <ul>
 *   <li>constructor {@code OutboxRelay(JdbcClient, ViewProjector, TransactionTemplate, Clock, int batchSize,
 *       int connectionBudget, Duration lease)}; the old five-argument constructor may disappear;</li>
 *   <li>{@code public static int defaultConnectionBudget(int poolSize)} = {@code max(2, poolSize / 2)};</li>
 *   <li>{@code app.outbox.relay.lease} in {@code application.yml} (an ISO-8601 duration);
 *       {@code app.outbox.relay.connection-budget} overrides the default budget when present;</li>
 *   <li>migration {@code V3__outbox_lease.sql}: nullable {@code locked_until TIMESTAMPTZ} and
 *       {@code locked_by UUID} on {@code command.outbox};</li>
 *   <li>{@code runOnce()} still returns the number of events delivered by that call.</li>
 * </ul>
 * Where the new constructor does not exist yet, {@link #relay} falls back to the shipped one (ignoring budget
 * and lease), so the behaviour tests fail on behaviour; {@code OutboxRelayContractTest} pins the constructor.
 *
 * <p>The relay under test runs on a metered {@link DataSource} that counts connections, records open/close
 * order and commits, and injects pool-acquire timeouts and commit failures. It is not a pool: it opens a
 * real connection per acquire, so "peak" is exactly the number of connections the relay held at once.
 */
abstract class RelaySupport extends RiskDatabaseSupport {

    static final Duration LONG_LEASE = Duration.ofMinutes(1);

    // ------------------------------------------------------------------ metering

    static final class Meter extends DelegatingDataSource {
        final AtomicInteger held = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();
        /** Commits per opened connection, in the order the connections were opened (index 0 = first). */
        final List<AtomicInteger> commits = new CopyOnWriteArrayList<>();
        /** "open n" / "close n" in real order, n counted from 1. */
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        /** Threads that asked for a connection while already holding one. */
        final List<String> waitedWhileHolding = new CopyOnWriteArrayList<>();
        private final ThreadLocal<Integer> heldByThread = ThreadLocal.withInitial(() -> 0);
        /** Receives the 1-based number of the acquire attempt (failed attempts count); true = pool timeout. */
        volatile IntPredicate failAcquire = attempt -> false;
        /** Receives the 1-based number of the opened connection; true = its commit fails and its work is rolled back. */
        volatile IntPredicate failCommit = connection -> false;

        Meter(DataSource target) {
            super(target);
        }

        int opened() {
            return commits.size();
        }

        void reset() {
            held.set(0);
            peak.set(0);
            attempts.set(0);
            commits.clear();
            events.clear();
            waitedWhileHolding.clear();
        }

        @Override
        public Connection getConnection() throws SQLException {
            var attempt = attempts.incrementAndGet();
            if (heldByThread.get() > 0) {
                waitedWhileHolding.add(Thread.currentThread().getName());
            }
            if (failAcquire.test(attempt)) {
                throw new SQLTransientConnectionException("Connection is not available, request timed out after 250ms");
            }
            var real = super.getConnection();
            var mine = new AtomicInteger();
            int number;
            synchronized (this) {
                commits.add(mine);
                number = commits.size();
                events.add("open " + number);
            }
            peak.accumulateAndGet(held.incrementAndGet(), Math::max);
            heldByThread.set(heldByThread.get() + 1);
            var closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commit")) {
                            mine.incrementAndGet();
                            if (failCommit.test(number)) {
                                real.rollback();
                                throw new SQLException("commit failed on connection " + number);
                            }
                        }
                        if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                            held.decrementAndGet();
                            heldByThread.set(heldByThread.get() - 1);
                            events.add("close " + number);
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException failed) {
                            throw failed.getCause();
                        }
                    });
        }
    }

    /** A projector that records what reached it, and can be made to wait. */
    static final class RecordingProjector extends ViewProjector {
        /** "customer@version" to how many times it was projected. */
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        /** customer to the versions in the order they were projected. */
        final Map<String, List<Long>> versions = new ConcurrentHashMap<>();
        /** customer to the threads that projected it. */
        final Map<String, Set<Long>> threads = new ConcurrentHashMap<>();
        volatile Runnable before = () -> { };

        RecordingProjector(JdbcClient jdbc, JsonMapper json, TransactionTemplate transaction, Clock clock) {
            super(jdbc, json, transaction, clock);
        }

        @Override
        public Result<Boolean, ProjectionFailure> project(Projection projection) {
            before.run();
            calls.computeIfAbsent(projection.customerNumber() + "@" + projection.version(), k -> new AtomicInteger())
                    .incrementAndGet();
            versions.computeIfAbsent(projection.customerNumber(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(projection.version());
            threads.computeIfAbsent(projection.customerNumber(), k -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().threadId());
            return super.project(projection);
        }
    }

    final class Rig {
        final Meter meter = new Meter(dataSource);
        final JdbcClient jdbc = JdbcClient.create(meter);
        final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(meter));

        /** The shipped wiring of the projector: the plain (REQUIRED) template. It must be safe by itself. */
        RecordingProjector projector() {
            return new RecordingProjector(jdbc, json, tx, clockAt(NOW));
        }

        OutboxRelay relay(ViewProjector projector, int batchSize, int budget, Duration lease) {
            return RelaySupport.this.relay(jdbc, projector, tx, batchSize, budget, lease);
        }
    }

    // ------------------------------------------------------------------ contract access

    OutboxRelay relay(JdbcClient jdbc, ViewProjector projector, TransactionTemplate tx, int batchSize, int budget,
                      Duration lease) {
        try {
            return OutboxRelay.class.getConstructor(JdbcClient.class, ViewProjector.class, TransactionTemplate.class,
                            Clock.class, int.class, int.class, Duration.class)
                    .newInstance(jdbc, projector, tx, clockAt(NOW), batchSize, budget, lease);
        } catch (NoSuchMethodException absent) {
            return new OutboxRelay(jdbc, projector, tx, clockAt(NOW), batchSize);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException broken) {
            throw new AssertionError("the OutboxRelay constructor could not be called", broken);
        }
    }

    /** Runs a round and swallows a failure of the round itself: the specification does not say whether it throws. */
    static int runQuietly(OutboxRelay relay) {
        try {
            return relay.runOnce();
        } catch (RuntimeException failedRound) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ fixtures and queries

    /** {@code profiles} customers "P-01"..., each with {@code versions} evaluations: one outbox row per version. */
    void seed(int profiles, int versions) {
        var tick = 0;
        for (var version = 1; version <= versions; version++) {
            for (var profile = 1; profile <= profiles; profile++) {
                var number = "P-%02d".formatted(profile);
                var command = version % 2 == 1 ? exposed(number) : employed(number);
                evaluateAt(NOW.plusSeconds(++tick)).execute(ONBOARDING_IN_DE, command).toOptional().orElseThrow();
            }
        }
    }

    int outboxWhere(String condition) {
        return jdbc.sql("SELECT count(*) FROM command.outbox WHERE " + condition).query(Integer.class).single();
    }

    /** Simulates the passing of the lease time without waiting for it. */
    void expireAllLeases() {
        jdbc.sql("UPDATE command.outbox SET locked_until = now() - interval '1 second' WHERE locked_until IS NOT NULL")
                .update();
    }
}
