package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-23 (stage 6 re-review, CR-2): only a transient failure is released without counting. A persistent
 * database failure (here bad SQL after a column was renamed) is the event's failure: it counts, so ten
 * of them park the row and the trouble is visible. Every release without counting is logged, naming
 * only the exception type.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxFailureVisibilityIT extends RelaySupport {

    @Test
    @DisplayName("a persistent database failure while projecting counts an attempt")
    void shouldCountAPersistentFailure() {
        seed(1, 1);
        jdbc.sql("ALTER TABLE query.compliance_risk_view RENAME COLUMN projection_version TO projection_version_gone").update();
        var rig = new Rig();

        runQuietly(rig.relay(rig.projector(), 100, 2, LONG_LEASE));

        assertThat(jdbc.sql("SELECT attempts FROM command.outbox").query(Integer.class).single())
                .as("bad SQL is not transient: it must show in the attempts").isEqualTo(1);
    }

    @Test
    @DisplayName("a chunk released after a transient failure is logged with the exception type only")
    void shouldLogATransientRelease(CapturedOutput output) throws Exception {
        seed(1, 1);
        var rig = new Rig();
        assertThat(rig.relay(rig.projector(), 100, 2, LONG_LEASE).runOnce()).isEqualTo(1);
        evaluateAt(NOW.plusSeconds(60)).execute(ONBOARDING_IN_DE, employed("P-01")).toOptional().orElseThrow();
        var database = jdbc.sql("SELECT current_database()").query(String.class).single();
        jdbc.sql("ALTER DATABASE " + database + " SET lock_timeout = '300ms'").update();

        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM query.compliance_risk_view WHERE customer_number = 'P-01' FOR UPDATE");
            }
            runQuietly(rig.relay(rig.projector(), 100, 2, LONG_LEASE));
            holder.rollback();
        }

        var warnings = output.getAll().lines()
                .filter(line -> line.contains("Outbox chunk released without counting an attempt")).toList();
        assertThat(warnings).as("W-4: exactly the type, nothing else on the line").isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .endsWith("Outbox chunk released without counting an attempt (CannotAcquireLockException)"));
        assertThat(output.getAll()).as("no exception text and no stack trace from the release")
                .doesNotContain("CannotAcquireLockException:").doesNotContain("at org.springframework.jdbc")
                .doesNotContain("P-01");
    }
}
