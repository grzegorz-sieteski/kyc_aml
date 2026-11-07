package com.gs5.kycaml.risk.adapters.persistence;

import com.gs5.kycaml.risk.adapters.RiskDatabaseSupport;
import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.risk.domain.DecisionRecord;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskError;
import com.gs5.kycaml.risk.domain.Evaluation;
import com.gs5.kycaml.risk.domain.Jurisdiction;
import com.gs5.kycaml.risk.domain.RecordedFact;
import com.gs5.kycaml.risk.domain.RiskProfileConcurrentChange;
import com.gs5.kycaml.risk.domain.RiskProfileSnapshot;
import com.gs5.kycaml.risk.domain.RiskProfileVersion;
import com.gs5.kycaml.risk.domain.RiskTrack;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write side against a real PostgreSQL: what is stored, what is read back, what the database
 * refuses on its own, and who wins a race (BR-05, BR-29, BR-30, BR-42).
 */
class RiskProfileSqlAdapterIT extends RiskDatabaseSupport {

    private RiskProfileSnapshot newSnapshot(String number) {
        var command = exposed(number);
        return new RiskProfileSnapshot(UUID.randomUUID(), DE_CENTRE, number(number),
                Evaluation.of(command, Jurisdiction.DE), NOW, null, null, null, NOW);
    }

    private RecordedFact.EvaluationFact evaluationFact(String number) {
        var command = exposed(number);
        return new RecordedFact.EvaluationFact(command, Evaluation.of(command, Jurisdiction.DE), "onboarding-01", NOW);
    }

    @Test
    @DisplayName("an evaluation is stored at version 1 and read back exactly, scale and all (BR-29)")
    void shouldStoreTheFirstEvaluationAtVersionOneAndReadItBack() {
        var command = exposed("C-1");

        var result = evaluateAt(NOW).execute(ONBOARDING_IN_DE, command);

        assertThat(result.toOptional().map(RiskProfileVersion::value)).contains(1L);
        var stored = profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow();
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.latestEvaluation()).isEqualTo(Evaluation.of(command, Jurisdiction.DE));
        assertThat(stored.latestEvaluation().totalScore().scale()).isEqualTo(2);
        assertThat(stored.evaluatedAt()).isEqualTo(NOW);
        assertThat(stored.updatedAt()).isEqualTo(NOW);
        assertThat(stored.decidedTrack()).isNull();
        assertThat(stored.latestDecision()).isNull();
        assertThat(stored.customerNumber().value()).isEqualTo("C-1");
    }

    @Test
    @DisplayName("a later evaluation moves to version 2, keeps the identity and appends to the history (BR-29)")
    void shouldMoveToTheNextVersionAndAppendHistory() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        var firstId = profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow().id();

        var result = evaluateAt(NOW.plusSeconds(60)).execute(ONBOARDING_IN_DE, employed("C-1"));

        assertThat(result.toOptional().map(RiskProfileVersion::value)).contains(2L);
        var stored = profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow();
        assertThat(stored.id()).isEqualTo(firstId);
        assertThat(stored.version()).isEqualTo(2L);
        assertThat(stored.latestEvaluation().evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
        assertThat(jdbc.sql("SELECT version FROM command.risk_profile_history WHERE profile_id = :id ORDER BY version")
                .param("id", firstId).query(Long.class).list()).containsExactly(1L, 2L);
        assertThat(count("command.risk_profile")).isEqualTo(1);
    }

    @Test
    @DisplayName("a decision is stored with its justification and appended to the history (BR-38)")
    void shouldStoreTheDecisionAndItsHistory() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));

        var result = decideAt(NOW.plusSeconds(5)).execute(L2_IN_DE, decision("C-1", DecisionKind.FREEZE_ASSETS, 1L));

        assertThat(result.toOptional().map(RiskProfileVersion::value)).contains(2L);
        var stored = profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow();
        assertThat(stored.decidedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(stored.latestDecision()).isEqualTo(
                new DecisionRecord(DecisionKind.FREEZE_ASSETS, "l2-01", NOW.plusSeconds(5), REASON, 1L));
        assertThat(stored.evaluatedAt()).isEqualTo(NOW);
        assertThat(jdbc.sql("SELECT fact_kind FROM command.risk_profile_history WHERE profile_id = :id ORDER BY version")
                .param("id", stored.id()).query(String.class).list()).containsExactly("EVALUATION", "DECISION");
    }

    @Test
    @DisplayName("a snapshot read at an older version is refused and nothing at all is written (BR-30, BR-42)")
    void shouldRefuseAStaleVersionAndWriteNothing() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        var stale = profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow();
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, employed("C-1"));
        var historyBefore = count("command.risk_profile_history");
        var outboxBefore = count("command.outbox");

        var result = profiles.save(stale, evaluationFact("C-1"), List.of());

        assertThat(result).isEqualTo(Result.failure(new RiskProfileConcurrentChange()));
        assertThat(count("command.risk_profile_history")).isEqualTo(historyBefore);
        assertThat(count("command.outbox")).isEqualTo(outboxBefore);
        assertThat(profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow().version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("eight first evaluations of one customer at once: exactly one is recorded, the rest are refused (BR-09, BR-30)")
    void shouldLetExactlyOneFirstEvaluationWin() throws Exception {
        var contenders = 8;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Result<RiskProfileVersion, RiskProfileConcurrentChange>>();
        try (var pool = Executors.newFixedThreadPool(contenders)) {
            var futures = new ArrayList<java.util.concurrent.Future<Result<RiskProfileVersion, RiskProfileConcurrentChange>>>();
            for (var index = 0; index < contenders; index++) {
                futures.add(pool.submit(() -> {
                    var snapshot = newSnapshot("C-RACE");
                    start.await();
                    return profiles.save(snapshot, evaluationFact("C-RACE"), List.of());
                }));
            }
            start.countDown();
            for (var future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results.stream().filter(Result::isSuccess)).hasSize(1);
        assertThat(results.stream().filter(Result::isFailure)).hasSize(contenders - 1);
        assertThat(count("command.risk_profile")).isEqualTo(1);
        assertThat(count("command.risk_profile_history")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same customer number in two booking centres is two profiles, and each sees only its own (BR-04, BR-05)")
    void shouldKeepBookingCentresApart() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        evaluateAt(NOW).execute(ONBOARDING_IN_US, employed("C-1"));

        assertThat(count("command.risk_profile")).isEqualTo(2);
        assertThat(profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow().latestEvaluation().policy().jurisdiction())
                .isEqualTo(Jurisdiction.DE);
        assertThat(profiles.findOwn(US_CENTRE, number("C-1")).orElseThrow().latestEvaluation().policy().jurisdiction())
                .isEqualTo(Jurisdiction.US);
        assertThat(profiles.findOwn(UUID.randomUUID(), number("C-1"))).isEmpty();
        assertThat(profiles.findOwn(DE_CENTRE, number("C-UNKNOWN"))).isEmpty();
    }

    @Test
    @DisplayName("an unknown booking centre records nothing at all (BR-01, BR-42)")
    void shouldRecordNothingForAnUnknownBookingCentre() {
        var unknown = new com.gs5.kycaml.shared.kernel.ActorContext(UUID.randomUUID(), "x",
                com.gs5.kycaml.shared.kernel.ActorRole.ONBOARDING_SYSTEM);

        var result = evaluateAt(NOW).execute(unknown, exposed("C-1"));

        assertThat(result).isEqualTo(Result.failure(new EvaluateCustomerRiskError.BookingCentreUnknown()));
        assertThat(count("command.risk_profile")).isZero();
        assertThat(count("command.outbox")).isZero();
    }

    @Test
    @DisplayName("history can neither be changed nor removed, even by the table owner (BR-44)")
    void shouldRefuseToChangeOrRemoveHistory() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));

        assertThatThrownBy(() -> jdbc.sql("UPDATE command.risk_profile_history SET actor_id = 'someone-else'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM command.risk_profile_history").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(count("command.risk_profile_history")).isEqualTo(1);
    }

    @Test
    @DisplayName("each event is an outbox row written with the profile: pending, keyed by customer, without the internal id (D-05)")
    void shouldWriteOneOutboxRowPerEventInTheSameTransaction() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        var profileId = profiles.findOwn(DE_CENTRE, number("C-1")).orElseThrow().id();

        var row = jdbc.sql("""
                SELECT event_type, aggregate_version, status, attempts, tenant_id, customer_number, profile_id,
                       payload::text AS payload, view::text AS view
                FROM command.outbox
                """).query((rs, rowNumber) -> new String[]{
                rs.getString("event_type"), rs.getString("aggregate_version"), rs.getString("status"),
                rs.getString("attempts"), rs.getString("tenant_id"), rs.getString("customer_number"),
                rs.getString("profile_id"), rs.getString("payload"), rs.getString("view")}).single();

        assertThat(row[0]).isEqualTo("CustomerRiskEvaluated");
        assertThat(row[1]).isEqualTo("1");
        assertThat(row[2]).isEqualTo("PENDING");
        assertThat(row[3]).isEqualTo("0");
        assertThat(row[4]).isEqualTo(DE_CENTRE.toString());
        assertThat(row[5]).isEqualTo("C-1");
        assertThat(row[6]).isEqualTo(profileId.toString());
        assertThat(row[7]).doesNotContain(profileId.toString()).contains("C-1");
        assertThat(row[8]).doesNotContain(profileId.toString());
    }

    @Test
    @DisplayName("the announced decision carries no justification; the view feeding compliance does (A-31)")
    void shouldKeepTheJustificationOutOfThePayloadButInTheView() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        decideAt(NOW).execute(L2_IN_DE, decision("C-1", DecisionKind.FREEZE_ASSETS, 1L));

        var row = jdbc.sql("SELECT payload::text AS payload, view::text AS view FROM command.outbox "
                        + "WHERE event_type = 'ComplianceDecisionRecorded'")
                .query((rs, rowNumber) -> new String[]{rs.getString("payload"), rs.getString("view")}).single();

        assertThat(row[0]).doesNotContain(REASON).doesNotContain("justification");
        assertThat(row[1]).contains(REASON);
    }

    @Test
    @DisplayName("a stored row that can no longer be read back is not found, never a half-built profile")
    void shouldTreatAnUnreadableRowAsNotFound() {
        evaluateAt(NOW).execute(ONBOARDING_IN_DE, exposed("C-1"));
        jdbc.sql("UPDATE command.risk_profile SET evaluation = '\"broken\"'::jsonb").update();

        assertThat(profiles.findOwn(DE_CENTRE, number("C-1"))).isEmpty();
    }

    @Test
    @DisplayName("the database itself refuses a customer number the domain would refuse (BR-06)")
    void shouldRefuseABadCustomerNumberAtTheDatabase() {
        assertThat(insertRaw("C-OK", null)).isEqualTo(1);
        for (var bad : new String[]{"", "n".repeat(65), "ab\tc", "ab\u0085c"}) {
            assertThatThrownBy(() -> insertRaw(bad, null)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertRaw("C-GREEN", "GREEN")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw("C-OK", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private int insertRaw(String customerNumber, String decidedTrack) {
        return jdbc.sql("""
                INSERT INTO command.risk_profile
                    (id, tenant_id, customer_number, evaluation, evaluated_at, decided_track, latest_decision,
                     updated_at, version)
                VALUES (:id, :tenant, :number, '{}'::jsonb, now(), :decided, NULL, now(), 1)
                """)
                .param("id", UUID.randomUUID()).param("tenant", DE_CENTRE).param("number", customerNumber)
                .param("decided", decidedTrack).update();
    }
}
