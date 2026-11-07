package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.adapters.RiskDatabaseSupport;
import com.gs5.kycaml.risk.domain.AccountRestriction;
import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.risk.domain.Evaluation;
import com.gs5.kycaml.risk.domain.FrontOfficeStatus;
import com.gs5.kycaml.risk.domain.Jurisdiction;
import com.gs5.kycaml.risk.domain.RiskTrack;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write side feeds the query side through the outbox: relayed in batches, projected under a
 * version gate, read back scoped to the booking centre, with nothing about risk in the front-office
 * table (BR-04, BR-40, BR-45).
 */
class ViewProjectionIT extends RiskDatabaseSupport {

    private ComplianceRiskView complianceView(java.util.UUID tenant, String number) {
        return new ComplianceRiskViewSqlReader(jdbc, json).find(tenant, number(number)).orElseThrow();
    }

    private java.util.Optional<FrontOfficeStatus> frontOffice(java.util.UUID tenant, String number) {
        return new FrontOfficeStatusSqlReader(jdbc).find(tenant, number(number));
    }

    private String statusOf(String eventType) {
        return jdbc.sql("SELECT status FROM command.outbox WHERE event_type = :type ORDER BY created_at LIMIT 1")
                .param("type", eventType).query(String.class).single();
    }

    // ------------------------------------------------------------------ projecting

    @Test
    @DisplayName("an evaluation reaches the compliance view and the front-office status once the outbox is relayed (BR-45)")
    void shouldProjectAnEvaluation() {
        var command = exposed("C-1");
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, command);

        var delivered = relay(10).runOnce();

        assertThat(delivered).isEqualTo(1);
        var view = complianceView(DE_CENTRE, "C-1");
        var expected = Evaluation.of(command, Jurisdiction.DE);
        assertThat(view.customerNumber()).isEqualTo("C-1");
        assertThat(view.totalScore()).isEqualTo(expected.totalScore());
        assertThat(view.evaluatedTrack()).isEqualTo(RiskTrack.YELLOW);
        assertThat(view.effectiveTrack()).isEqualTo(RiskTrack.YELLOW);
        assertThat(view.decidedTrack()).isNull();
        assertThat(view.restriction()).isEqualTo(AccountRestriction.SOFT_LIMITS);
        assertThat(view.explanation()).isEqualTo(expected.explanation());
        assertThat(view.policy()).isEqualTo(expected.policy());
        assertThat(view.version()).isEqualTo(1L);
        assertThat(view.evaluatedAt()).isEqualTo(NOW);
        assertThat(view.latestDecision()).isNull();
        assertThat(frontOffice(DE_CENTRE, "C-1")).contains(FrontOfficeStatus.VERIFICATION_PENDING);
    }

    @Test
    @DisplayName("a Green customer is active at the front office (BR-40)")
    void shouldShowAGreenCustomerAsActive() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, employed("C-1"));

        relay(10).runOnce();

        assertThat(frontOffice(DE_CENTRE, "C-1")).contains(FrontOfficeStatus.ACTIVE);
    }

    @Test
    @DisplayName("a decision reaches the view with who decided and why, at the next version; the front office only sees 'pending' (BR-38, BR-40)")
    void shouldProjectADecision() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, employed("C-1"));
        decideAt(NOW.plusSeconds(30)).execute(L2_IN_DE, decision("C-1", DecisionKind.FREEZE_ASSETS, 1L));

        relay(10).runOnce();

        var view = complianceView(DE_CENTRE, "C-1");
        assertThat(view.version()).isEqualTo(2L);
        assertThat(view.decidedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(view.evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
        assertThat(view.effectiveTrack()).isEqualTo(RiskTrack.RED);
        assertThat(view.restriction()).isEqualTo(AccountRestriction.ASSETS_FROZEN);
        assertThat(view.latestDecision().decidedBy()).isEqualTo("l2-01");
        assertThat(view.latestDecision().justification()).isEqualTo(REASON);
        assertThat(view.evaluatedAt()).isEqualTo(NOW);
        assertThat(frontOffice(DE_CENTRE, "C-1")).contains(FrontOfficeStatus.VERIFICATION_PENDING);
    }

    @Test
    @DisplayName("relayed rows are marked delivered once, and a second pass finds nothing to do")
    void shouldMarkRowsDeliveredOnce() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        decideAt(NOW).execute(L2_IN_DE, decision("C-1", DecisionKind.ESCALATE_EDD, 1L));

        assertThat(relay(10).runOnce()).isEqualTo(2);
        assertThat(relay(10).runOnce()).isZero();

        assertThat(jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'DELIVERED' AND delivered_at IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(complianceView(DE_CENTRE, "C-1").version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a batch smaller than the backlog is relayed in order, batch after batch")
    void shouldRelayInBatches() {
        for (var index = 1; index <= 7; index++) {
            evaluateAt(NOW.plusSeconds(index)).execute(ONBOARDING_IN_DE, employed("C-" + index));
        }

        assertThat(relay(3).runOnce()).isEqualTo(3);
        assertThat(relay(3).runOnce()).isEqualTo(3);
        assertThat(relay(3).runOnce()).isEqualTo(1);
        assertThat(relay(3).runOnce()).isZero();
        assertThat(count("query.compliance_risk_view")).isEqualTo(7);
    }

    // ------------------------------------------------------------------ the version gate

    @Test
    @DisplayName("a late projection of an older version leaves the newer view alone (out of order)")
    void shouldIgnoreAnOlderVersionArrivingLate() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        decideAt(NOW).execute(L2_IN_DE, decision("C-1", DecisionKind.FREEZE_ASSETS, 1L));
        var views = jdbc.sql("SELECT aggregate_version, view::text AS view FROM command.outbox ORDER BY aggregate_version")
                .query((rs, row) -> rs.getString("view")).list();
        var projector = projector();

        var newer = projector.project(new Projection(DE_CENTRE, "C-1", 2L, views.get(1)));
        var older = projector.project(new Projection(DE_CENTRE, "C-1", 1L, views.get(0)));

        assertThat(newer).isEqualTo(Result.success(true));
        assertThat(older).isEqualTo(Result.success(false));
        assertThat(complianceView(DE_CENTRE, "C-1").version()).isEqualTo(2L);
        assertThat(complianceView(DE_CENTRE, "C-1").effectiveTrack()).isEqualTo(RiskTrack.RED);
    }

    @Test
    @DisplayName("projecting the same version twice changes nothing the second time (idempotence)")
    void shouldBeIdempotent() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        var view = jdbc.sql("SELECT view::text FROM command.outbox").query(String.class).single();
        var projector = projector();

        assertThat(projector.project(new Projection(DE_CENTRE, "C-1", 1L, view))).isEqualTo(Result.success(true));
        assertThat(projector.project(new Projection(DE_CENTRE, "C-1", 1L, view))).isEqualTo(Result.success(false));
        assertThat(count("query.compliance_risk_view")).isEqualTo(1);
        assertThat(count("query.front_office_status")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unreadable view is a failure and writes nothing at all")
    void shouldRefuseAnUnreadableViewAndWriteNothing() {
        var result = projector().project(new Projection(DE_CENTRE, "C-1", 1L, "\"broken\""));

        assertThat(result).isEqualTo(Result.failure(new ProjectionFailure()));
        assertThat(count("query.compliance_risk_view")).isZero();
        assertThat(count("query.front_office_status")).isZero();
    }

    @Test
    @DisplayName("one unreadable row stays pending with its attempt counted, and does not hold back the others")
    void shouldKeepAFailingRowPendingAndDeliverTheRest() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        jdbc.sql("""
                INSERT INTO command.outbox (event_id, profile_id, tenant_id, customer_number, aggregate_version,
                                            event_type, payload, view, status, attempts, created_at)
                VALUES (:id, :profile, :tenant, 'C-BAD', 1, 'CustomerRiskEvaluated', '{}'::jsonb,
                        '"broken"'::jsonb, 'PENDING', 0, :at)
                """).param("id", UUID.randomUUID()).param("profile", UUID.randomUUID()).param("tenant", DE_CENTRE)
                .param("at", java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC)).update();
        evaluateAt(NOW.plusSeconds(1)).execute(ONBOARDING_IN_DE, employed("C-2"));

        var delivered = relay(10).runOnce();

        assertThat(delivered).isEqualTo(2);
        assertThat(complianceView(DE_CENTRE, "C-1").version()).isEqualTo(1L);
        assertThat(complianceView(DE_CENTRE, "C-2").version()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT status || ':' || attempts FROM command.outbox WHERE customer_number = 'C-BAD'")
                .query(String.class).single()).isEqualTo("PENDING:1");
    }

    // ------------------------------------------------------------------ concurrent relays

    @Test
    @DisplayName("two relays running at once never take the same row: every event is delivered exactly once (SKIP LOCKED)")
    void shouldNotDeliverTheSameRowTwice() throws Exception {
        for (var index = 1; index <= 30; index++) {
            evaluateAt(NOW.plusSeconds(index)).execute(ONBOARDING_IN_DE, employed("C-" + index));
        }
        var total = new AtomicInteger();
        var failures = new ArrayList<Throwable>();
        Runnable drain = () -> {
            try {
                int batch;
                do {
                    batch = relay(4).runOnce();
                    total.addAndGet(batch);
                } while (batch > 0);
            } catch (RuntimeException e) {
                synchronized (failures) {
                    failures.add(e);
                }
            }
        };
        var first = new Thread(drain);
        var second = new Thread(drain);
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(failures).isEmpty();
        assertThat(total.get()).isEqualTo(30);
        assertThat(count("query.compliance_risk_view")).isEqualTo(30);
        assertThat(jdbc.sql("SELECT count(*) FROM command.outbox WHERE status = 'PENDING'").query(Integer.class).single())
                .isZero();
    }

    // ------------------------------------------------------------------ reading

    @Test
    @DisplayName("a customer of another booking centre, or an unknown one, reads as absent (BR-04)")
    void shouldScopeReadsToTheBookingCentre() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        relay(10).runOnce();

        assertThat(new ComplianceRiskViewSqlReader(jdbc, json).find(US_CENTRE, number("C-1"))).isEmpty();
        assertThat(new ComplianceRiskViewSqlReader(jdbc, json).find(DE_CENTRE, number("C-UNKNOWN"))).isEmpty();
        assertThat(frontOffice(US_CENTRE, "C-1")).isEmpty();
        assertThat(frontOffice(DE_CENTRE, "C-UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("the same customer number in two booking centres projects to two separate views (BR-05)")
    void shouldProjectBookingCentresSeparately() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        evaluateAt(NOW).execute(ONBOARDING_IN_US, employed("C-1"));

        relay(10).runOnce();

        assertThat(complianceView(DE_CENTRE, "C-1").effectiveTrack()).isEqualTo(RiskTrack.YELLOW);
        assertThat(complianceView(US_CENTRE, "C-1").effectiveTrack()).isEqualTo(RiskTrack.GREEN);
    }

    @Test
    @DisplayName("the front-office table has no column that could hold risk information (BR-40)")
    void shouldHaveNoRiskColumnInTheFrontOfficeTable() {
        var columns = jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'query' AND table_name = 'front_office_status'
                ORDER BY ordinal_position
                """).query(String.class).list();

        assertThat(columns).containsExactly("tenant_id", "customer_number", "projection_version", "status",
                "projected_at");
    }

    @Test
    @DisplayName("the relay does not write the query schema itself: only the projector does")
    void shouldLeaveTheQuerySideEmptyUntilRelayed() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));

        assertThat(count("query.compliance_risk_view")).isZero();
        assertThat(statusOf("CustomerRiskEvaluated")).isEqualTo("PENDING");
        assertThat(List.of(count("query.front_office_status"))).containsExactly(0);
    }
}
