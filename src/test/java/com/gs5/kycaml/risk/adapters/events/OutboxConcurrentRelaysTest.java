package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Behaviour 7: two relays at once deliver every event exactly once and never project the same row twice;
 * and no event is lost when a relay is cut off between claim and mark (the other takes the rows once the
 * lease has expired).
 */
class OutboxConcurrentRelaysTest extends RelaySupport {

    @Test
    @DisplayName("two relays running at once deliver every event exactly once, and project no row twice")
    void shouldDeliverEveryEventExactlyOnce() throws Exception {
        seed(20, 3);                                              // 60 events
        var rig = new Rig();
        var projector = rig.projector();                          // shared, so it sees every projection
        var total = new AtomicInteger();
        var barrier = new CyclicBarrier(2);
        var failures = new ArrayList<Throwable>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var drains = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                var relay = rig.relay(projector, 5, 2, LONG_LEASE);
                barrier.await();
                int batch;
                do {
                    batch = relay.runOnce();
                    total.addAndGet(batch);
                } while (batch > 0);
                return null;
            })).toList();
            for (var drain : drains) {
                try {
                    drain.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException failed) {
                    failures.add(failed.getCause());
                }
            }
        }

        assertThat(failures).isEmpty();
        assertThat(total.get()).isEqualTo(60);
        assertThat(projector.calls).hasSize(60);
        assertThat(projector.calls.values()).allSatisfy(times -> assertThat(times.get()).isEqualTo(1));
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(60);
        assertThat(count("query.compliance_risk_view")).isEqualTo(20);
    }

    @Test
    @DisplayName("while one relay holds a batch, the other delivers all the rest and none of the held rows")
    void shouldLetTheOtherRelayWorkAroundALeasedBatch() throws Exception {
        seed(20, 1);
        var holder = new Rig();
        var held = holder.projector();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        held.before = () -> {
            started.countDown();
            OutboxLeaseClaimTest.await(release);
        };
        var other = new Rig();
        var otherProjector = other.projector();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var round = executor.submit(() -> holder.relay(held, 5, 2, LONG_LEASE).runOnce());
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            int delivered = 0;
            int batch;
            do {
                batch = other.relay(otherProjector, 5, 2, LONG_LEASE).runOnce();
                delivered += batch;
            } while (batch > 0);

            assertThat(delivered).isEqualTo(15);
            assertThat(otherProjector.calls.keySet()).doesNotContainAnyElementsOf(held.calls.keySet());
            release.countDown();
            assertThat(round.get(30, TimeUnit.SECONDS)).isEqualTo(5);
        }
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(20);
    }

    @Test
    @DisplayName("a relay cut off between claim and mark loses nothing: the other takes the rows once the lease expires")
    void shouldLoseNothingWhenARelayIsCutOff() throws Exception {
        seed(10, 1);
        var crashing = new Rig();
        var stuck = crashing.projector();
        var never = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        stuck.before = () -> {
            started.countDown();
            OutboxLeaseClaimTest.await(never);                    // A is "cut off": it never marks anything
        };
        var rescuer = new Rig();
        var rescuerProjector = rescuer.projector();
        var lease = Duration.ofSeconds(2);

        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> crashing.relay(stuck, 100, 2, lease).runOnce());
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var relayB = rescuer.relay(rescuerProjector, 100, 2, lease);

            assertThat(relayB.runOnce()).as("A's lease has not expired: B must not touch its rows").isZero();
            assertThat(rescuerProjector.calls).isEmpty();

            var delivered = new AtomicInteger();
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                delivered.addAndGet(relayB.runOnce());
                assertThat(delivered.get()).as("B takes everything after the lease has expired").isEqualTo(10);
            });

            assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(10);
            assertThat(count("query.compliance_risk_view")).isEqualTo(10);
            never.countDown();                                    // let A's thread finish; its late work is harmless
        }
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(10);
        assertThat(count("query.compliance_risk_view")).isEqualTo(10);
    }
}
