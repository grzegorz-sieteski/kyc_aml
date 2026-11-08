package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour 4: a connection-pool failure (acquire timeout) is not an attempt. Only a projection failure of
 * the event itself counts toward the poison threshold of ten.
 */
class OutboxPoolFailureTest extends RelaySupport {

    @Test
    @DisplayName("a healthy row never reaches ten attempts because the pool keeps timing out")
    void shouldNotCountPoolTimeoutsAsAttempts() {
        seed(8, 1);
        var rig = new Rig();
        var relay = rig.relay(rig.projector(), 100, 2, LONG_LEASE);

        for (var round = 1; round <= 15; round++) {
            rig.meter.reset();
            rig.meter.failAcquire = attempt -> attempt > 1;     // the claim succeeds, every chunk times out
            runQuietly(relay);
            expireAllLeases();                                  // the leases run out, the rows come back
        }

        assertThat(outboxWhere("attempts > 0")).as("no attempt was counted").isZero();
        assertThat(outboxWhere("status = 'PENDING'")).isEqualTo(8);

        rig.meter.reset();
        rig.meter.failAcquire = attempt -> false;
        assertThat(rig.relay(rig.projector(), 100, 2, LONG_LEASE).runOnce())
                .as("the rows are still claimable once the pool recovers").isEqualTo(8);
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(8);
    }

    @Test
    @DisplayName("a pool timeout on one chunk leaves its rows pending without an attempt and delivers the other chunk")
    void shouldDeliverTheChunksThatGotAConnection() {
        seed(8, 1);
        var rig = new Rig();
        rig.meter.failAcquire = attempt -> attempt == 2;        // exactly one chunk's connection times out
        var relay = rig.relay(rig.projector(), 100, 2, LONG_LEASE);

        var delivered = runQuietly(relay);

        assertThat(delivered).isBetween(1, 7);
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(delivered);
        assertThat(outboxWhere("status = 'PENDING'")).isEqualTo(8 - delivered);
        assertThat(outboxWhere("attempts > 0")).isZero();
    }

    @Test
    @DisplayName("a row that fails inside the projection itself does count an attempt")
    void shouldCountTheAttemptsOfAFailingProjection() {
        seed(4, 1);
        jdbc.sql("ALTER TABLE query.compliance_risk_view ADD CONSTRAINT ck_test_poison"
                + " CHECK (customer_number <> 'P-03')").update();
        var rig = new Rig();
        var relay = rig.relay(rig.projector(), 100, 2, LONG_LEASE);

        var delivered = relay.runOnce();

        assertThat(delivered).isEqualTo(3);
        assertThat(jdbc.sql("SELECT attempts FROM command.outbox WHERE customer_number = 'P-03'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(outboxWhere("status = 'PENDING' AND customer_number = 'P-03'")).isEqualTo(1);
        assertThat(outboxWhere("status = 'DELIVERED' AND attempts = 0")).isEqualTo(3);
    }
}
