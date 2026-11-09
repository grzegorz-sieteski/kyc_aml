package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-13 (stage 6, CR-7): a transient database failure while projecting (here a real lock timeout on the
 * view row) is not the event's own failure. The row stays pending, no attempt is counted, and the next
 * round delivers it. Ten such failures must never park a healthy row.
 */
class OutboxTransientFailureIT extends RelaySupport {

    @Test
    @DisplayName("a lock timeout on the view row counts no attempt, and the row is delivered once the lock is gone")
    void shouldNotCountALockTimeoutAsAnAttempt() throws Exception {
        seed(2, 1);
        var rig = new Rig();
        assertThat(rig.relay(rig.projector(), 100, 2, LONG_LEASE).runOnce()).isEqualTo(2);
        evaluateAt(NOW.plusSeconds(60)).execute(ONBOARDING_IN_DE, employed("P-01")).toOptional().orElseThrow();
        var database = jdbc.sql("SELECT current_database()").query(String.class).single();
        jdbc.sql("ALTER DATABASE " + database + " SET lock_timeout = '300ms'").update();

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM query.compliance_risk_view WHERE customer_number = 'P-01' FOR UPDATE");
            }

            for (var round = 0; round < 12; round++) {
                runQuietly(rig.relay(rig.projector(), 100, 2, LONG_LEASE));
                expireAllLeases();
            }

            assertThat(jdbc.sql("SELECT attempts FROM command.outbox WHERE status = 'PENDING'")
                    .query(Integer.class).single())
                    .as("a lock timeout is transient: it is not the event's failure").isZero();
            holder.rollback();
        }

        assertThat(rig.relay(rig.projector(), 100, 2, LONG_LEASE).runOnce()).isEqualTo(1);
        assertThat(outboxWhere("status = 'DELIVERED'")).isEqualTo(3);
    }
}
