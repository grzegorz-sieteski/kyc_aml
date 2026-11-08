package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour 6: when a chunk fails, the lease of its rows is released at once, not left to expire: another
 * relay (or the next round) can take them immediately, and nothing about the failure counts as an attempt.
 */
class OutboxLeaseReleaseTest extends RelaySupport {

    enum Failure { POOL_TIMEOUT_ON_THE_CHUNK, COMMIT_FAILS_ON_THE_CHUNK }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("a failed chunk gives its rows back at once, and a second relay delivers them without waiting for the lease")
    void shouldReleaseTheLeaseOfAFailedChunk(Failure failure) {
        seed(6, 1);
        var rig = new Rig();
        switch (failure) {
            case POOL_TIMEOUT_ON_THE_CHUNK -> rig.meter.failAcquire = attempt -> attempt == 2;
            case COMMIT_FAILS_ON_THE_CHUNK -> rig.meter.failCommit = connection -> connection == 2;
        }
        var first = rig.relay(rig.projector(), 100, 1, LONG_LEASE);   // one chunk: connection 2 is that chunk

        var delivered = runQuietly(first);

        assertThat(delivered).isZero();
        assertThat(outboxWhere("locked_until > now()")).as("no row is still leased (lease is a minute)").isZero();
        assertThat(outboxWhere("locked_by IS NOT NULL AND locked_until > now()")).isZero();
        assertThat(outboxWhere("attempts > 0")).as("a connection problem is not the event's fault").isZero();

        var second = new Rig();
        assertThat(second.relay(second.projector(), 100, 2, LONG_LEASE).runOnce())
                .as("no sleeping, no manual expiry: the rows are free").isEqualTo(6);
    }
}
