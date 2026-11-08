package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour 1: the relay claims a batch with a lease in its own short transaction
 * ({@code UPDATE ... WHERE event_id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING}); a row with an
 * unexpired lease belongs to its owner, a row with an expired lease is up for grabs again.
 */
class OutboxLeaseClaimIT extends RelaySupport {

    @Test
    @DisplayName("migration V3 adds a nullable locked_until (timestamptz) and locked_by (uuid) to the outbox")
    void shouldAddTheLeaseColumns() {
        var columns = jdbc.sql("""
                        SELECT column_name || ':' || data_type || ':' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'command' AND table_name = 'outbox'
                          AND column_name IN ('locked_until', 'locked_by')
                        ORDER BY column_name
                        """)
                .query(String.class).list();

        assertThat(columns).containsExactly("locked_by:uuid:YES", "locked_until:timestamp with time zone:YES");
    }

    @Test
    @DisplayName("while a chunk is being projected the claimed rows carry a committed lease of the configured length")
    void shouldLeaseTheClaimedBatchInItsOwnCommittedTransaction() throws Exception {
        seed(10, 1);
        var rig = new Rig();
        var projector = rig.projector();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        projector.before = () -> {
            started.countDown();
            await(release);
        };
        var relay = rig.relay(projector, 4, 2, LONG_LEASE);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var round = executor.submit(relay::runOnce);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            // Seen from another connection: the claim has committed, the rows are not yet delivered.
            assertThat(outboxWhere("locked_by IS NOT NULL")).isEqualTo(4);
            assertThat(jdbc.sql("SELECT count(DISTINCT locked_by) FROM command.outbox").query(Integer.class).single())
                    .isEqualTo(1);
            assertThat(outboxWhere("locked_by IS NOT NULL AND locked_until BETWEEN now() + interval '55 seconds'"
                    + " AND now() + interval '65 seconds'")).isEqualTo(4);
            assertThat(outboxWhere("status = 'PENDING' AND locked_by IS NULL AND locked_until IS NULL")).isEqualTo(6);
            assertThat(outboxWhere("status = 'DELIVERED'")).isZero();

            release.countDown();
            assertThat(round.get(30, TimeUnit.SECONDS)).isEqualTo(4);
        }
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(4);
    }

    @Test
    @DisplayName("the lease length is the configured one, not a constant")
    void shouldUseTheConfiguredLeaseLength() throws Exception {
        seed(3, 1);
        var rig = new Rig();
        var projector = rig.projector();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        projector.before = () -> {
            started.countDown();
            await(release);
        };
        var relay = rig.relay(projector, 10, 2, Duration.ofMinutes(10));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var round = executor.submit(relay::runOnce);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(outboxWhere("locked_until BETWEEN now() + interval '9 minutes' AND now() + interval '11 minutes'"))
                    .isEqualTo(3);

            release.countDown();
            round.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("rows whose lease has not expired are not claimed by another relay, and keep their owner")
    void shouldNotClaimRowsOfAnUnexpiredLease() {
        seed(5, 1);
        jdbc.sql("UPDATE command.outbox SET locked_by = gen_random_uuid(), locked_until = now() + interval '1 hour'")
                .update();
        var owners = jdbc.sql("SELECT locked_by::text FROM command.outbox ORDER BY event_id").query(String.class).list();
        var rig = new Rig();
        var projector = rig.projector();

        var delivered = rig.relay(projector, 10, 2, LONG_LEASE).runOnce();

        assertThat(delivered).isZero();
        assertThat(projector.calls).isEmpty();
        assertThat(outboxWhere("status = 'PENDING'")).isEqualTo(5);
        assertThat(jdbc.sql("SELECT locked_by::text FROM command.outbox ORDER BY event_id").query(String.class).list())
                .isEqualTo(owners);
    }

    @Test
    @DisplayName("rows whose lease has expired (the owner crashed) are claimed again and delivered")
    void shouldReclaimRowsOfAnExpiredLease() {
        seed(5, 1);
        jdbc.sql("UPDATE command.outbox SET locked_by = gen_random_uuid(), locked_until = now() - interval '1 second'")
                .update();
        var rig = new Rig();

        var delivered = rig.relay(rig.projector(), 10, 2, LONG_LEASE).runOnce();

        assertThat(delivered).isEqualTo(5);
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(5);
        assertThat(count("query.compliance_risk_view")).isEqualTo(5);
    }

    @Test
    @DisplayName("a row that already failed ten times is no longer claimed, even without a lease")
    void shouldLeaveParkedRowsAlone() {
        seed(3, 1);
        jdbc.sql("UPDATE command.outbox SET attempts = 10 WHERE event_id = (SELECT event_id FROM command.outbox"
                + " ORDER BY created_at LIMIT 1)").update();
        var rig = new Rig();

        var delivered = rig.relay(rig.projector(), 10, 2, LONG_LEASE).runOnce();

        assertThat(delivered).isEqualTo(2);
        assertThat(outboxWhere("status = 'PENDING' AND attempts = 10 AND locked_by IS NULL")).isEqualTo(1);
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
