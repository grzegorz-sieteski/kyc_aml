package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.gs5.kycaml.risk.domain.RiskFixtures.NOW;
import static com.gs5.kycaml.risk.domain.RiskFixtures.ONBOARDING_IN_DE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.ONBOARDING_IN_UNKNOWN_CENTRE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.ONBOARDING_IN_US;
import static com.gs5.kycaml.risk.domain.RiskFixtures.rated;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluateCustomerRiskUseCaseTest {

    private static final RawRatedCountry DE_LOW = rated("DE", "LOW");

    private InMemoryRiskProfileRepository repository;
    private EvaluateCustomerRiskUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRiskProfileRepository();
        useCase = new EvaluateCustomerRiskUseCase(repository, new StaticBookingCentres(),
                new FixedRiskProfileIdGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RawEvaluateCustomerRisk employed(String number, boolean sanctions) {
        return new RawEvaluateCustomerRisk(number, "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), DE_LOW, "DE",
                "BRANCH", false, "NONE", sanctions, false, false);
    }

    private static RawEvaluateCustomerRisk exposed(String number) {
        return new RawEvaluateCustomerRisk(number, "POLITICALLY_EXPOSED", DE_LOW, List.of(rated("AE", "LOW")),
                DE_LOW, "AE", "REMOTE", true, "UNCONFIRMED_FINANCIAL_CRIME", false, false, false);
    }

    private Result<RiskProfileVersion, EvaluateCustomerRiskError> evaluate(
            ActorContext actor, RawEvaluateCustomerRisk raw) {
        return useCase.execute(actor, RiskFixtures.parsed(raw));
    }

    @Test
    @DisplayName("the first evaluation of a customer creates the risk profile at version 1 (BR-29)")
    void shouldCreateTheProfileAtVersionOne() {
        var raw = employed("C-1000", false);
        var command = RiskFixtures.parsed(raw);

        var result = useCase.execute(ONBOARDING_IN_DE, command);

        assertThat(result).isEqualTo(Result.success(new RiskProfileVersion(1L)));
        var stored = repository.stored(StaticBookingCentres.DE_CENTRE, "C-1000").orElseThrow();
        assertThat(stored.id()).isEqualTo(FixedRiskProfileIdGenerator.PROFILE_UUID);
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.latestEvaluation()).isEqualTo(Evaluation.of(command, Jurisdiction.DE));
        assertThat(stored.decidedTrack()).isNull();
        assertThat(stored.latestDecision()).isNull();
        assertThat(stored.updatedAt()).isEqualTo(NOW);
        assertThat(stored.evaluatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an evaluation records the fact with its inputs, who evaluated and when (BR-29)")
    void shouldRecordTheFactWithItsInputs() {
        var command = RiskFixtures.parsed(employed("C-1000", false));

        useCase.execute(ONBOARDING_IN_DE, command);

        assertThat(repository.recordedFacts()).hasSize(1);
        var fact = (RecordedFact.EvaluationFact) repository.recordedFacts().getFirst();
        assertThat(fact.inputs()).isSameAs(command);
        assertThat(fact.evaluation()).isEqualTo(Evaluation.of(command, Jurisdiction.DE));
        assertThat(fact.actorId()).isEqualTo("onboarding-01");
        assertThat(fact.recordedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an evaluation announces CustomerRiskEvaluated once, with the new version, the tracks and the restriction (BR-29)")
    void shouldAnnounceTheEvaluation() {
        var command = RiskFixtures.parsed(exposed("C-1000"));

        useCase.execute(ONBOARDING_IN_DE, command);

        assertThat(repository.announcedEvents()).hasSize(1);
        var event = (RiskDomainEvent.CustomerRiskEvaluated) repository.announcedEvents().getFirst();
        assertThat(event.eventId()).isEqualTo(FixedRiskProfileIdGenerator.eventId(1));
        assertThat(event.tenantId()).isEqualTo(StaticBookingCentres.DE_CENTRE);
        assertThat(event.customerNumber().value()).isEqualTo("C-1000");
        assertThat(event.version()).isEqualTo(new RiskProfileVersion(1L));
        assertThat(event.evaluation()).isEqualTo(Evaluation.of(command, Jurisdiction.DE));
        assertThat(event.decidedTrack()).isNull();
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.YELLOW);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.SOFT_LIMITS);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a critical fact freezes the account in the announced fact: Red and assets frozen (BR-23, BR-28)")
    void shouldAnnounceARedFrozenAccountForACriticalFact() {
        useCase.execute(ONBOARDING_IN_DE, RiskFixtures.parsed(employed("C-1000", true)));

        var event = (RiskDomainEvent.CustomerRiskEvaluated) repository.announcedEvents().getFirst();
        assertThat(event.evaluation().totalScore()).isEqualTo(new BigDecimal("100.00"));
        assertThat(event.effectiveTrack()).isEqualTo(RiskTrack.RED);
        assertThat(event.restriction()).isEqualTo(AccountRestriction.ASSETS_FROZEN);
    }

    @Test
    @DisplayName("a re-evaluation records version 2, keeps the profile identity and replaces every input (BR-13, BR-29)")
    void shouldReevaluateAtTheNextVersion() {
        evaluate(ONBOARDING_IN_DE, exposed("C-1000"));
        var second = RiskFixtures.parsed(employed("C-1000", false));

        var result = useCase.execute(ONBOARDING_IN_DE, second);

        assertThat(result).isEqualTo(Result.success(new RiskProfileVersion(2L)));
        var stored = repository.stored(StaticBookingCentres.DE_CENTRE, "C-1000").orElseThrow();
        assertThat(stored.id()).isEqualTo(FixedRiskProfileIdGenerator.PROFILE_UUID);
        assertThat(stored.latestEvaluation()).isEqualTo(Evaluation.of(second, Jurisdiction.DE));
        assertThat(stored.latestEvaluation().evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
        assertThat(repository.recordedFacts()).hasSize(2);
        assertThat(repository.announcedEvents()).hasSize(2);
        var event = (RiskDomainEvent.CustomerRiskEvaluated) repository.announcedEvents().get(1);
        assertThat(event.version()).isEqualTo(new RiskProfileVersion(2L));
        assertThat(event.eventId()).isEqualTo(FixedRiskProfileIdGenerator.eventId(2));
    }

    @Test
    @DisplayName("the same customer number in two booking centres is two separate profiles (BR-05)")
    void shouldKeepProfilesOfDifferentBookingCentresApart() {
        var inDe = evaluate(ONBOARDING_IN_DE, employed("C-1000", false));
        var inUs = evaluate(ONBOARDING_IN_US, employed("C-1000", false));

        assertThat(inDe).isEqualTo(Result.success(new RiskProfileVersion(1L)));
        assertThat(inUs).isEqualTo(Result.success(new RiskProfileVersion(1L)));
        assertThat(repository.profileCount()).isEqualTo(2);
        assertThat(repository.stored(StaticBookingCentres.US_CENTRE, "C-1000").orElseThrow().latestEvaluation()
                .policy().jurisdiction()).isEqualTo(Jurisdiction.US);
    }

    @Test
    @DisplayName("the policy of the caller's booking centre is applied: a German resident is a non-resident of a US centre (BR-19, BR-21)")
    void shouldApplyThePolicyOfTheCallersBookingCentre() {
        evaluate(ONBOARDING_IN_DE, employed("C-1000", false));
        evaluate(ONBOARDING_IN_US, employed("C-2000", false));

        assertThat(repository.stored(StaticBookingCentres.DE_CENTRE, "C-1000").orElseThrow().latestEvaluation()
                .explanation().multiplier()).isEqualTo(new BigDecimal("1.00"));
        assertThat(repository.stored(StaticBookingCentres.US_CENTRE, "C-2000").orElseThrow().latestEvaluation()
                .explanation().multiplier()).isEqualTo(new BigDecimal("1.25"));
    }

    @Test
    @DisplayName("a caller of an unknown booking centre is not identified, and nothing is recorded or announced (BR-01, BR-42)")
    void shouldRefuseAnUnknownBookingCentre() {
        var result = evaluate(ONBOARDING_IN_UNKNOWN_CENTRE, employed("C-1000", false));

        assertThat(result).isEqualTo(Result.failure(new EvaluateCustomerRiskError.BookingCentreUnknown()));
        assertThat(repository.profileCount()).isZero();
        assertThat(repository.recordedFacts()).isEmpty();
        assertThat(repository.announcedEvents()).isEmpty();
    }

    @Test
    @DisplayName("a change recorded after the profile was read refuses the evaluation and records nothing (BR-30)")
    void shouldRefuseWhenTheProfileChangedMeanwhile() {
        evaluate(ONBOARDING_IN_DE, employed("C-1000", false));
        repository.afterNextRead(() -> repository.simulateConcurrentChange(StaticBookingCentres.DE_CENTRE, "C-1000"));

        var result = evaluate(ONBOARDING_IN_DE, exposed("C-1000"));

        assertThat(result).isEqualTo(Result.failure(new EvaluateCustomerRiskError.RiskProfileChangedConcurrently()));
        assertThat(repository.stored(StaticBookingCentres.DE_CENTRE, "C-1000").orElseThrow().version()).isEqualTo(2L);
        assertThat(repository.recordedFacts()).hasSize(1);
        assertThat(repository.announcedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("two first evaluations of one customer: the one recorded first stands, the other is refused (BR-30)")
    void shouldRefuseTheSecondOfTwoFirstEvaluations() {
        repository.afterNextRead(() -> evaluate(ONBOARDING_IN_DE, employed("C-1000", false)));

        var result = evaluate(ONBOARDING_IN_DE, exposed("C-1000"));

        assertThat(result).isEqualTo(Result.failure(new EvaluateCustomerRiskError.RiskProfileChangedConcurrently()));
        assertThat(repository.profileCount()).isEqualTo(1);
        assertThat(repository.stored(StaticBookingCentres.DE_CENTRE, "C-1000").orElseThrow().latestEvaluation()
                .evaluatedTrack()).isEqualTo(RiskTrack.GREEN);
        assertThat(repository.announcedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("an announced event shows no customer number, no score and no actor when printed (BR-43)")
    void shouldNeverPrintAnEvent() {
        useCase.execute(ONBOARDING_IN_DE, RiskFixtures.parsed(exposed("C-1000")));

        var printed = repository.announcedEvents().getFirst().toString();
        assertThat(printed).doesNotContain("C-1000").doesNotContain("51.00").doesNotContain("YELLOW")
                .doesNotContain("onboarding-01");
        assertThat(repository.recordedFacts().getFirst().toString()).doesNotContain("C-1000")
                .doesNotContain("onboarding-01");
    }
}
