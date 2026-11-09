package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviours 2 and 3: the claimed batch is split by profile into at most B chunks; each chunk is projected
 * and marked DELIVERED in ONE short transaction on ONE connection; nobody waits for a second connection
 * while holding one; the relay never has more than B connections at once.
 */
class OutboxChunkedProjectionIT extends RelaySupport {

    private static final int BUDGET = 3;

    @Test
    @DisplayName("one claim connection plus at most one connection per chunk, each committed exactly once")
    void shouldUseOneShortTransactionPerChunk() {
        seed(12, 2);
        var rig = new Rig();
        var relay = rig.relay(rig.projector(), 100, BUDGET, LONG_LEASE);

        var delivered = relay.runOnce();

        assertThat(delivered).isEqualTo(24);
        assertThat(rig.meter.opened()).as("claim + chunks").isBetween(2, 1 + BUDGET);
        assertThat(rig.meter.commits).extracting(commits -> commits.get())
                .as("every connection is one transaction: claim once, every chunk once")
                .containsOnly(1);
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(24);
    }

    @Test
    @DisplayName("the claim transaction is over and its connection released before any chunk takes a connection")
    void shouldReleaseTheClaimConnectionBeforeProjecting() {
        seed(12, 1);
        var rig = new Rig();

        rig.relay(rig.projector(), 100, BUDGET, LONG_LEASE).runOnce();

        var events = List.copyOf(rig.meter.events);
        assertThat(events.getFirst()).isEqualTo("open 1");
        assertThat(events.indexOf("close 1")).as("the claim connection closes before the second one opens")
                .isLessThan(events.indexOf("open 2"));
    }

    @Test
    @DisplayName("no relay thread asks for a connection while it already holds one")
    void shouldNeverWaitForASecondConnection() {
        seed(12, 2);
        var rig = new Rig();

        rig.relay(rig.projector(), 100, BUDGET, LONG_LEASE).runOnce();

        assertThat(rig.meter.waitedWhileHolding).isEmpty();
        assertThat(rig.meter.peak.get()).as("workers never queue for connections beyond the budget")
                .isLessThanOrEqualTo(BUDGET);
        assertThat(rig.meter.opened()).as("one connection per chunk, not one per event").isLessThanOrEqualTo(1 + BUDGET);
    }

    @Test
    @DisplayName("all versions of one profile are projected by one chunk, in version order")
    void shouldKeepAProfileInOneChunkInVersionOrder() {
        seed(12, 3);
        var rig = new Rig();
        var projector = rig.projector();

        rig.relay(projector, 100, BUDGET, LONG_LEASE).runOnce();

        assertThat(projector.versions).hasSize(12);
        projector.versions.forEach((customer, versions) ->
                assertThat(versions).as(customer).containsExactly(1L, 2L, 3L));
        projector.threads.forEach((customer, threads) -> assertThat(threads).as(customer).hasSize(1));
        assertThat(projector.threads.values().stream().flatMap(java.util.Set::stream).distinct().count())
                .as("at most B chunks").isLessThanOrEqualTo(BUDGET);
    }

    @Test
    @DisplayName("projection and marking happen in the same transaction: a chunk that cannot commit leaves no view and no delivered row")
    void shouldCommitProjectionAndMarkingTogether() {
        seed(6, 1);
        var rig = new Rig();
        rig.meter.failCommit = connection -> connection == 2;   // the first chunk's commit
        var relay = rig.relay(rig.projector(), 100, 1, LONG_LEASE);

        var delivered = runQuietly(relay);

        assertThat(delivered).isZero();
        assertThat(count("query.compliance_risk_view")).as("projection rolled back with the mark").isZero();
        assertThat(outboxWhere("status = 'DELIVERED'")).isZero();
    }

    @Test
    @DisplayName("the relay never holds more connections at once than its budget, whatever the backlog")
    void shouldNeverExceedTheConnectionBudget() {
        seed(40, 1);
        var rig = new Rig();

        var delivered = rig.relay(rig.projector(), 100, 2, LONG_LEASE).runOnce();

        assertThat(delivered).isEqualTo(40);
        assertThat(rig.meter.peak.get()).isLessThanOrEqualTo(2);
        assertThat(rig.meter.waitedWhileHolding).isEmpty();
    }

    @Test
    @DisplayName("a larger budget is honoured as the ceiling, not exceeded")
    void shouldHonourAnotherBudget() {
        seed(40, 1);
        var rig = new Rig();

        var delivered = rig.relay(rig.projector(), 100, 5, LONG_LEASE).runOnce();

        assertThat(delivered).isEqualTo(40);
        assertThat(rig.meter.peak.get()).isLessThanOrEqualTo(5);
    }
}
