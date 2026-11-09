package com.gs5.kycaml.risk.adapters.events;

import com.gs5.kycaml.risk.adapters.projection.Projection;
import com.gs5.kycaml.risk.adapters.projection.ProjectionFailure;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-14 (stage 6, CR-8): a relay whose lease was taken over by another relay changes nothing on those
 * rows. Neither its attempt count nor its delivery mark may land on a row it no longer owns.
 */
class OutboxLeaseOwnershipIT extends RelaySupport {

    /** Waits until released, then reports that the event itself could not be projected. */
    private final class LateFailingProjector extends ViewProjector {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        LateFailingProjector(Rig rig) {
            super(rig.jdbc, json, rig.tx, clockAt(NOW));
        }

        @Override
        public Result<Boolean, ProjectionFailure> project(Projection projection) {
            entered.countDown();
            OutboxLeaseClaimIT.await(release);
            return Result.failure(new ProjectionFailure());
        }
    }

    @Test
    @DisplayName("a relay that lost its lease does not count an attempt on the row another relay delivered")
    void shouldNotCountAnAttemptOnARowItNoLongerOwns() throws Exception {
        seed(1, 1);
        var late = new Rig();
        var lateProjector = new LateFailingProjector(late);
        var rescuer = new Rig();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var lateRound = executor.submit(() -> late.relay(lateProjector, 100, 2, LONG_LEASE).runOnce());
            assertThat(lateProjector.entered.await(10, TimeUnit.SECONDS)).isTrue();

            expireAllLeases();
            assertThat(rescuer.relay(rescuer.projector(), 100, 2, LONG_LEASE).runOnce())
                    .as("the rescuer takes the row once the lease has run out").isEqualTo(1);

            lateProjector.release.countDown();
            lateRound.get(30, TimeUnit.SECONDS);
        }

        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(1);
        assertThat(outboxWhere("attempts > 0"))
                .as("the late relay's failure must not be counted on a row it no longer owns").isZero();
    }

    /** R-25: waits until released, then projects normally (a success). */
    private final class LateSucceedingProjector extends ViewProjector {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        LateSucceedingProjector(Rig rig) {
            super(rig.jdbc, json, rig.tx, clockAt(NOW));
        }

        @Override
        public Result<Boolean, ProjectionFailure> project(Projection projection) {
            entered.countDown();
            OutboxLeaseClaimIT.await(release);
            return super.project(projection);
        }
    }

    @Test
    @DisplayName("R-25: a relay that lost its lease does not mark delivered again: it reports 0 and keeps the rescuer's time")
    void shouldNotMarkDeliveredARowItNoLongerOwns() throws Exception {
        seed(1, 1);
        var late = new Rig();
        var lateProjector = new LateSucceedingProjector(late);
        var rescuer = new Rig();
        var lateRelay = new OutboxRelay(late.jdbc, lateProjector, late.tx, clockAt(NOW.plusSeconds(3_600)), 100, 2,
                LONG_LEASE);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var lateRound = executor.submit(lateRelay::runOnce);
            assertThat(lateProjector.entered.await(10, TimeUnit.SECONDS)).isTrue();

            expireAllLeases();
            assertThat(rescuer.relay(rescuer.projector(), 100, 2, LONG_LEASE).runOnce()).isEqualTo(1);
            var deliveredAt = jdbc.sql("SELECT delivered_at FROM command.outbox")
                    .query(java.time.OffsetDateTime.class).single();

            lateProjector.release.countDown();
            assertThat(lateRound.get(30, TimeUnit.SECONDS))
                    .as("the late relay delivered nothing it still owned").isZero();
            assertThat(jdbc.sql("SELECT delivered_at FROM command.outbox").query(java.time.OffsetDateTime.class).single())
                    .as("the rescuer's delivery time stands").isEqualTo(deliveredAt);
        }
    }
}
