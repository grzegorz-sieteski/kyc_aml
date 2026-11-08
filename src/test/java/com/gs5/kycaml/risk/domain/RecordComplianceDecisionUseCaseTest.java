package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.ActorRole;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.gs5.kycaml.risk.domain.RiskFixtures.L1_IN_DE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.L2_IN_DE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.MLRO_IN_DE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.NOW;
import static com.gs5.kycaml.risk.domain.RiskFixtures.ONBOARDING_IN_DE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.REASON;
import static com.gs5.kycaml.risk.domain.RiskFixtures.decision;
import static com.gs5.kycaml.risk.domain.RiskFixtures.rated;
import static org.assertj.core.api.Assertions.assertThat;

class RecordComplianceDecisionUseCaseTest {

    private static final RawRatedCountry DE_LOW = rated("DE", "LOW");

    private InMemoryRiskProfileRepository repository;
    private EvaluateCustomerRiskUseCase evaluate;
    private RecordComplianceDecisionUseCase decide;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRiskProfileRepository();
        var ids = new FixedRiskProfileIdGenerator();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        evaluate = new EvaluateCustomerRiskUseCase(repository, new StaticBookingCentres(), ids, clock);
        decide = new RecordComplianceDecisionUseCase(repository, ids, clock);
    }

    /** E3: 51.00, Yellow. */
    private void exposed(String number) {
        evaluate(new RawEvaluateCustomerRisk(number, "POLITICALLY_EXPOSED", DE_LOW,
                List.of(rated("AE", "LOW")), DE_LOW, "AE", "REMOTE", true, "UNCONFIRMED_FINANCIAL_CRIME",
                false, false, false));
    }

    /** E1: 4.50, Green. */
    private void employed(String number) {
        evaluate(new RawEvaluateCustomerRisk(number, "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE",
                "BRANCH", false, "NONE", false, false, false));
    }

    /** A sanctions match: 100.00, Red, a critical fact in force. */
    private void sanctioned(String number) {
        evaluate(new RawEvaluateCustomerRisk(number, "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE",
                "BRANCH", false, "NONE", true, false, false));
    }

    /** E6: 91.56, Red by score alone, no critical fact. */
    private void redByScore(String number) {
        var black = rated("IR", "FATF_BLACK_LIST_OR_CONFLICT_ZONE");
        evaluate(new RawEvaluateCustomerRisk(number, "INDIVIDUAL_STUDENT_OR_NO_INCOME", black, List.of(black), black,
                "DE", "REMOTE", true, "RADICALISATION_OR_PARAMILITARY", false, false, false));
    }

    private void evaluate(RawEvaluateCustomerRisk raw) {
        evaluate.execute(ONBOARDING_IN_DE, RiskFixtures.parsed(raw));
    }

    private Result<RiskProfileVersion, RecordComplianceDecisionError> decide(
            ActorContext actor, String number, DecisionKind kind, long versionViewed) {
        return decide.execute(actor, decision(number, kind, versionViewed));
    }

    private RiskProfileSnapshot stored(String number) {
        return repository.stored(StaticBookingCentres.DE_CENTRE, number).orElseThrow();
    }

    // ------------------------------------------------------------------ the decisions

    @Test
    @DisplayName("F1: freezing sets the decided and effective track to Red and the assets frozen, one version higher (BR-32, BR-28)")
    void shouldFreeze() {
        exposed("C-1");

        var result = decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);

        assertThat(result).isEqualTo(Result.success(new RiskProfileVersion(2L)));
        assertThat(stored("C-1").decidedTrack()).isEqualTo(RiskTrack.RED);
        var event = (RiskDomainEvent.ComplianceDecisionRecorded) repository.announcedEvents().get(1);
        assertThat(event.kind()).isEqualTo(DecisionKind.FREEZE_ASSETS);
        assertThat(event.decidedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.RED);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.ASSETS_FROZEN);
    }

    @Test
    @DisplayName("escalating sets the decided and effective track to Orange and an enhanced-due-diligence hold (BR-32)")
    void shouldEscalate() {
        exposed("C-1");

        decide(L1_IN_DE, "C-1", DecisionKind.ESCALATE_EDD, 1L);

        assertThat(stored("C-1").decidedTrack()).isEqualTo(RiskTrack.ORANGE);
        var event = (RiskDomainEvent.ComplianceDecisionRecorded) repository.announcedEvents().get(1);
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.ORANGE);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.EDD_HOLD);
    }

    @Test
    @DisplayName("the effective track is the higher of the decided and the evaluated track (BR-31)")
    void shouldKeepTheHigherOfDecidedAndEvaluated() {
        redByScore("C-1");

        decide(L1_IN_DE, "C-1", DecisionKind.ESCALATE_EDD, 1L);

        var event = (RiskDomainEvent.ComplianceDecisionRecorded) repository.announcedEvents().get(1);
        assertThat(event.decidedTrack()).isEqualTo(RiskTrack.ORANGE);
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.RED);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.ASSETS_FROZEN);
    }

    @Test
    @DisplayName("F4: clearing removes the decided track, so the effective track is the latest evaluation's (BR-32)")
    void shouldClear() {
        exposed("C-1");
        decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);

        var result = decide(L2_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 2L);

        assertThat(result).isEqualTo(Result.success(new RiskProfileVersion(3L)));
        assertThat(stored("C-1").decidedTrack()).isNull();
        var event = (RiskDomainEvent.ComplianceDecisionRecorded) repository.announcedEvents().get(2);
        assertThat(event.decidedTrack()).isNull();
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.YELLOW);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.SOFT_LIMITS);
    }

    @Test
    @DisplayName("F7a: freezing a customer who is Red by score alone is allowed, and the freeze then survives a Green evaluation (BR-31, BR-35)")
    void shouldAllowFreezingACustomerWhoIsAlreadyRedByScore() {
        redByScore("C-1");

        var result = decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);
        employed("C-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(stored("C-1").decidedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(stored("C-1").latestEvaluation().evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
    }

    @Test
    @DisplayName("F3: a Green evaluation never lowers a decided Red; the evaluation is still recorded (BR-31)")
    void shouldNotLetAnEvaluationLowerADecidedTrack() {
        exposed("C-1");
        decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);

        employed("C-1");

        var profile = stored("C-1");
        assertThat(profile.version()).isEqualTo(3L);
        assertThat(profile.decidedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(profile.latestEvaluation().evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
        var event = (RiskDomainEvent.CustomerRiskEvaluated) repository.announcedEvents().get(2);
        assertThat(event.decidedTrack()).isEqualTo(RiskTrack.RED);
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.RED);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.ASSETS_FROZEN);
    }

    @Test
    @DisplayName("a decision records who decided, when, which, why and on which version, and changes neither score nor explanation (BR-38)")
    void shouldRecordTheDecisionDetails() {
        exposed("C-1");
        var before = stored("C-1").latestEvaluation();

        decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);

        var profile = stored("C-1");
        assertThat(profile.latestEvaluation()).isEqualTo(before);
        assertThat(profile.latestDecision()).isEqualTo(
                new DecisionRecord(DecisionKind.FREEZE_ASSETS, "l2-01", NOW, REASON, 1L));
        var fact = (RecordedFact.DecisionFact) repository.recordedFacts().get(1);
        assertThat(fact.decision()).isEqualTo(profile.latestDecision());
    }

    @Test
    @DisplayName("a decision moves the update moment but not the moment of the latest evaluation (spec query 1)")
    void shouldKeepTheMomentOfTheLatestEvaluationWhenADecisionIsRecorded() {
        exposed("C-1");
        var later = NOW.plusSeconds(3_600);
        var laterDecide = new RecordComplianceDecisionUseCase(repository, new FixedRiskProfileIdGenerator(),
                Clock.fixed(later, ZoneOffset.UTC));

        laterDecide.execute(L2_IN_DE, decision("C-1", DecisionKind.FREEZE_ASSETS, 1L));

        var profile = stored("C-1");
        assertThat(profile.evaluatedAt()).isEqualTo(NOW);
        assertThat(profile.updatedAt()).isEqualTo(later);
        assertThat(profile.latestDecision().decidedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("the decision event carries the decider but no justification field at all (A-31, BR-43)")
    void shouldNotCarryTheJustificationInTheEvent() {
        exposed("C-1");
        decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);

        var event = (RiskDomainEvent.ComplianceDecisionRecorded) repository.announcedEvents().get(1);
        assertThat(event.decidedBy()).isEqualTo("l2-01");
        assertThat(event.eventId()).isEqualTo(FixedRiskProfileIdGenerator.eventId(2));
        assertThat(java.util.Arrays.stream(RiskDomainEvent.ComplianceDecisionRecorded.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).noneMatch(name -> name.contains("ustification"));
        assertThat(event.toString()).doesNotContain("C-1").doesNotContain("l2-01").doesNotContain("RED");
    }

    // ---------------------------------------------------------------------- refusals

    @Test
    @DisplayName("F5: clearing when there is no decided track is refused (BR-35)")
    void shouldRefuseNothingToClear() {
        exposed("C-1");

        assertThat(decide(L2_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 1L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.NothingToClear()));
    }

    @Test
    @DisplayName("F6: clearing while a critical fact is in force is refused, with or without a decided track (BR-36)")
    void shouldRefuseClearingWhileACriticalFactIsInForce() {
        sanctioned("C-1");
        assertThat(decide(MLRO_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 1L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.CriticalFactInForce()));

        decide(MLRO_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);
        assertThat(decide(MLRO_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 2L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.CriticalFactInForce()));
        assertThat(stored("C-1").decidedTrack()).isEqualTo(RiskTrack.RED);
    }

    @Test
    @DisplayName("F7: freezing twice, or escalating once frozen or escalated, changes nothing and is refused (BR-35)")
    void shouldRefuseADecisionThatChangesNothing() {
        exposed("C-1");
        decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L);

        assertThat(decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 2L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.AlreadyFrozen()));
        assertThat(decide(L1_IN_DE, "C-1", DecisionKind.ESCALATE_EDD, 2L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.AlreadyEscalated()));

        exposed("C-2");
        decide(L1_IN_DE, "C-2", DecisionKind.ESCALATE_EDD, 1L);
        assertThat(decide(L1_IN_DE, "C-2", DecisionKind.ESCALATE_EDD, 2L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.AlreadyEscalated()));
        assertThat(decide(L2_IN_DE, "C-2", DecisionKind.FREEZE_ASSETS, 2L).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("F2: a decision taken on a version that is no longer current is refused (BR-33)")
    void shouldRefuseAStaleVersion() {
        exposed("C-1");
        employed("C-1");

        assertThat(decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.RiskProfileChangedSinceViewed()));
        assertThat(decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 3L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.RiskProfileChangedSinceViewed()));
        assertThat(stored("C-1").version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a missing version viewed is version 0, which is not a valid version (BR-33, D-12)")
    void shouldRefuseAMissingVersion() {
        exposed("C-1");
        var command = RecordComplianceDecisionCommand.parse(
                new RawRecordComplianceDecision("C-1", "FREEZE_ASSETS", REASON, null)).toOptional().orElseThrow();

        assertThat(decide.execute(L2_IN_DE, command))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.VersionViewedNotValid()));
    }

    @Test
    @DisplayName("an unknown customer and another booking centre's customer are the same refusal (BR-04)")
    void shouldNotFindAnotherBookingCentresCustomer() {
        exposed("C-1");
        var notFound = Result.<RiskProfileVersion, RecordComplianceDecisionError>failure(
                new RecordComplianceDecisionError.CustomerNotFound());

        assertThat(decide(L2_IN_DE, "C-UNKNOWN", DecisionKind.FREEZE_ASSETS, 1L)).isEqualTo(notFound);
        var inUs = new ActorContext(StaticBookingCentres.US_CENTRE, "l2-us", ActorRole.AML_ANALYST_L2);
        assertThat(decide(inUs, "C-1", DecisionKind.FREEZE_ASSETS, 1L)).isEqualTo(notFound);
        assertThat(stored("C-1").decidedTrack()).isNull();
    }

    @Test
    @DisplayName("the justification errors carry their numbers and never the text (BR-34)")
    void shouldMapEveryJustificationError() {
        exposed("C-1");
        assertThat(decideRaw(new RawRecordComplianceDecision("C-1", "FREEZE_ASSETS", null, 1L)))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.JustificationMissing()));
        assertThat(decideRaw(new RawRecordComplianceDecision("C-1", "FREEZE_ASSETS", "too short", 1L)))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.JustificationTooShort(20, 9)));
        assertThat(decideRaw(new RawRecordComplianceDecision("C-1", "FREEZE_ASSETS", "x".repeat(2001), 1L)))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.JustificationTooLong(2000, 2001)));
        assertThat(decideRaw(new RawRecordComplianceDecision("C-1", "FREEZE_ASSETS", REASON + "\tmore", 1L)))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.JustificationControlCharacter(
                        0x09, REASON.length())));
    }

    private Result<RiskProfileVersion, RecordComplianceDecisionError> decideRaw(RawRecordComplianceDecision raw) {
        return decide.execute(L2_IN_DE, RecordComplianceDecisionCommand.parse(raw).toOptional().orElseThrow());
    }

    @Test
    @DisplayName("BR-37: the customer is judged before the justification, which is judged before the version")
    void shouldJudgeInTheOrderOfBr37() {
        exposed("C-1");
        var shortReason = "short";

        // customer before justification
        assertThat(decideRaw(new RawRecordComplianceDecision("C-UNKNOWN", "FREEZE_ASSETS", shortReason, 99L)))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.CustomerNotFound()));
        // justification before version
        assertThat(decideRaw(new RawRecordComplianceDecision("C-1", "FREEZE_ASSETS", shortReason, 99L)))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.JustificationTooShort(20, 5)));
    }

    @Test
    @DisplayName("BR-37: the version is judged before the critical fact, which is judged before 'nothing to clear'")
    void shouldJudgeVersionBeforeCriticalFactBeforeNothingToClear() {
        sanctioned("C-1");

        assertThat(decide(MLRO_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 99L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.RiskProfileChangedSinceViewed()));
        assertThat(decide(MLRO_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 1L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.CriticalFactInForce()));
    }

    @Test
    @DisplayName("a refused decision records nothing and announces nothing (BR-42)")
    void shouldRecordNothingWhenRefused() {
        exposed("C-1");
        var factsBefore = repository.recordedFacts().size();
        var eventsBefore = repository.announcedEvents().size();

        decide(L2_IN_DE, "C-1", DecisionKind.CLEAR_FALSE_POSITIVE, 1L);
        decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 99L);
        decide(L2_IN_DE, "C-UNKNOWN", DecisionKind.FREEZE_ASSETS, 1L);

        assertThat(repository.recordedFacts()).hasSize(factsBefore);
        assertThat(repository.announcedEvents()).hasSize(eventsBefore);
        assertThat(stored("C-1").version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a change recorded after the profile was read refuses the decision (BR-30)")
    void shouldRefuseWhenTheProfileChangedMeanwhile() {
        exposed("C-1");
        repository.afterNextRead(() -> repository.simulateConcurrentChange(StaticBookingCentres.DE_CENTRE, "C-1"));

        assertThat(decide(L2_IN_DE, "C-1", DecisionKind.FREEZE_ASSETS, 1L))
                .isEqualTo(Result.failure(new RecordComplianceDecisionError.RiskProfileChangedConcurrently()));
        assertThat(stored("C-1").decidedTrack()).isNull();
        assertThat(repository.recordedFacts()).hasSize(1);
    }

}
