package com.gs5.kycaml.risk.adapters.events;

import com.gs5.kycaml.risk.adapters.projection.Projection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour 5: {@code ViewProjector} runs in a savepoint ({@code PROPAGATION_NESTED}), so a row that fails
 * inside the database (here a CHECK constraint) does not abort the transaction of its chunk.
 *
 * <p>The projector is built with the plain template on purpose: it must be safe by itself, not by how the
 * Spring configuration happens to wire it.
 */
class OutboxSavepointTest extends RelaySupport {

    private static final String POISON = "P-03";

    private void poisonCustomer() {
        jdbc.sql("ALTER TABLE query.compliance_risk_view ADD CONSTRAINT ck_test_poison"
                + " CHECK (customer_number <> '" + POISON + "')").update();
    }

    private Projection projectionOf(String customer) {
        return new Projection(DE_CENTRE, customer, 1L, jdbc.sql("SELECT view::text FROM command.outbox WHERE customer_number = :c")
                .param("c", customer).query(String.class).single());
    }

    @Test
    @DisplayName("inside an open transaction a failing projection is a failure value and the transaction stays usable")
    void shouldKeepTheEnclosingTransactionAliveAfterAFailedProjection() {
        seed(5, 1);
        poisonCustomer();
        var projector = projector();

        var outcomes = transaction.execute(unused -> java.util.List.of(
                projector.project(projectionOf("P-01")),
                projector.project(projectionOf(POISON)),
                projector.project(projectionOf("P-04"))));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).isFailure()).isFalse();
        assertThat(outcomes.get(1).isFailure()).as("the poisoned row fails as a value").isTrue();
        assertThat(outcomes.get(2).isFailure()).as("the row after it is not collateral damage").isFalse();
        assertThat(jdbc.sql("SELECT customer_number FROM query.compliance_risk_view ORDER BY 1")
                .query(String.class).list()).containsExactly("P-01", "P-04");
    }

    @Test
    @DisplayName("one bad row in a chunk leaves the rest of the chunk delivered; the bad row stays pending with its attempt counted")
    void shouldDeliverTheRestOfTheChunkAroundABadRow() {
        seed(6, 1);
        poisonCustomer();
        var rig = new Rig();
        var relay = rig.relay(rig.projector(), 100, 1, LONG_LEASE);     // one budget: every row shares one chunk

        var delivered = relay.runOnce();

        assertThat(delivered).isEqualTo(5);
        assertThat(rig.meter.peak.get()).as("serial: claim, then the single chunk").isEqualTo(1);
        assertThat(jdbc.sql("SELECT customer_number FROM command.outbox WHERE status = 'DELIVERED' ORDER BY 1")
                .query(String.class).list()).containsExactly("P-01", "P-02", "P-04", "P-05", "P-06");
        assertThat(jdbc.sql("SELECT status || ':' || attempts FROM command.outbox WHERE customer_number = :c")
                .param("c", POISON).query(String.class).single()).isEqualTo("PENDING:1");
        assertThat(count("query.compliance_risk_view")).isEqualTo(5);
    }

    @Test
    @DisplayName("the same bad row is retried on the next round and counts each time, while healthy rows are not touched again")
    void shouldCountEachRetryOfTheBadRow() {
        seed(6, 1);
        poisonCustomer();
        var rig = new Rig();
        var projector = rig.projector();
        var relay = rig.relay(projector, 100, 1, LONG_LEASE);

        relay.runOnce();
        expireAllLeases();
        var second = relay.runOnce();

        assertThat(second).isZero();
        assertThat(jdbc.sql("SELECT attempts FROM command.outbox WHERE customer_number = :c")
                .param("c", POISON).query(Integer.class).single()).isEqualTo(2);
        assertThat(projector.calls.get("P-01@1").get()).as("a delivered row is not projected again").isEqualTo(1);
    }
}
