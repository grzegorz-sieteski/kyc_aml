package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.gs5.kycaml.risk.domain.RiskFixtures.L1_IN_DE;
import static com.gs5.kycaml.risk.domain.RiskFixtures.NOW;
import static com.gs5.kycaml.risk.domain.RiskFixtures.number;
import static org.assertj.core.api.Assertions.assertThat;

/** Both queries: scoped to the caller's booking centre, and the front-office view shows no risk (BR-04, BR-40). */
class QueryUseCasesTest {

    private static final UUID DE = StaticBookingCentres.DE_CENTRE;

    private static ComplianceRiskView viewOf(String customerNumber) {
        var evaluation = Evaluation.of(RiskFixtures.valid(), Jurisdiction.DE);
        return new ComplianceRiskView(customerNumber, evaluation.policy(), evaluation.totalScore(),
                evaluation.evaluatedTrack(), null, evaluation.evaluatedTrack(), AccountRestriction.ACTIVE,
                evaluation.explanation().criticalFacts(), evaluation.explanation(), null, NOW, 1L);
    }

    private static ComplianceRiskViewReader complianceReader(ComplianceRiskView stored) {
        return (tenantId, customerNumber) -> tenantId.equals(DE) && customerNumber.value().equals(stored.customerNumber())
                ? Optional.of(stored) : Optional.empty();
    }

    private static FrontOfficeStatusReader frontOfficeReader(Map<String, FrontOfficeStatus> byNumber) {
        return (tenantId, customerNumber) -> tenantId.equals(DE)
                ? Optional.ofNullable(byNumber.get(customerNumber.value())) : Optional.empty();
    }

    // ------------------------------------------------------------ compliance view

    @Test
    @DisplayName("the compliance view of a customer of the caller's booking centre is returned as stored (BR-39)")
    void shouldReturnTheComplianceView() {
        var stored = viewOf("C-1000");
        var useCase = new GetComplianceRiskViewUseCase(complianceReader(stored));

        var result = useCase.ask(L1_IN_DE, new ComplianceRiskViewQuery(number("C-1000")));

        assertThat(result).isEqualTo(Result.success(stored));
    }

    @Test
    @DisplayName("an unknown customer and another booking centre's customer are the same refusal (BR-04)")
    void shouldNotFindAnotherBookingCentresCustomer() {
        var useCase = new GetComplianceRiskViewUseCase(complianceReader(viewOf("C-1000")));
        var notFound = Result.<ComplianceRiskView, ComplianceRiskViewError>failure(
                new ComplianceRiskViewError.CustomerNotFound());
        var inUs = new com.gs5.kycaml.shared.kernel.ActorContext(StaticBookingCentres.US_CENTRE, "l1-us",
                com.gs5.kycaml.shared.kernel.ActorRole.AML_ANALYST_L1);

        assertThat(useCase.ask(L1_IN_DE, new ComplianceRiskViewQuery(number("C-UNKNOWN")))).isEqualTo(notFound);
        assertThat(useCase.ask(inUs, new ComplianceRiskViewQuery(number("C-1000")))).isEqualTo(notFound);
    }

    @Test
    @DisplayName("a text that cannot be a customer number names no customer (BR-04, BR-06)")
    void shouldTreatAnImpossibleNumberAsNotFound() {
        var notFound = Result.failure(new ComplianceRiskViewError.CustomerNotFound());

        for (var text : new String[]{null, "", "n".repeat(65), "ab\tc"}) {
            assertThat(ComplianceRiskViewQuery.parse(text)).isEqualTo(notFound);
        }
        assertThat(ComplianceRiskViewQuery.parse("C-1000").toOptional().map(q -> q.customerNumber().value()))
                .contains("C-1000");
    }

    @Test
    @DisplayName("the compliance view carries no internal identity and prints nothing (D-05, BR-43)")
    void shouldCarryNoInternalIdentityAndPrintNothing() {
        var names = Arrays.stream(ComplianceRiskView.class.getRecordComponents()).map(RecordComponent::getName);

        assertThat(names).noneMatch(name -> name.equals("id") || name.endsWith("Id") || name.contains("profileId"));
        assertThat(viewOf("C-1000").toString()).doesNotContain("C-1000").doesNotContain("GREEN");
    }

    // ------------------------------------------------------------ front-office status

    @Test
    @DisplayName("Green shows 'active' with the neutral message (BR-40, A-28)")
    void shouldShowActiveForGreen() {
        var useCase = new GetFrontOfficeStatusUseCase(frontOfficeReader(Map.of("C-1", FrontOfficeStatus.ACTIVE)));

        var result = useCase.ask(L1_IN_DE, new FrontOfficeStatusQuery(number("C-1")));

        assertThat(result).isEqualTo(Result.success(new FrontOfficeStatusView("C-1", FrontOfficeStatus.ACTIVE,
                "Account active – no operational restrictions.")));
    }

    @Test
    @DisplayName("every other state shows the same 'verification pending' view, so Yellow, Orange and Red cannot be told apart (BR-40)")
    void shouldShowTheSameViewForEveryRestrictedState() {
        var useCase = new GetFrontOfficeStatusUseCase(frontOfficeReader(Map.of(
                "C-Y", FrontOfficeStatus.VERIFICATION_PENDING, "C-O", FrontOfficeStatus.VERIFICATION_PENDING)));

        var yellow = useCase.ask(L1_IN_DE, new FrontOfficeStatusQuery(number("C-Y"))).toOptional().orElseThrow();
        var orange = useCase.ask(L1_IN_DE, new FrontOfficeStatusQuery(number("C-O"))).toOptional().orElseThrow();

        assertThat(yellow.status()).isEqualTo(FrontOfficeStatus.VERIFICATION_PENDING);
        assertThat(yellow.message()).isEqualTo("Standard administrative verification of documentation is in progress.");
        assertThat(yellow.status()).isEqualTo(orange.status());
        assertThat(yellow.message()).isEqualTo(orange.message());
    }

    @Test
    @DisplayName("the front-office view has exactly three fields and none can hold risk information (BR-40)")
    void shouldHaveNoFieldThatCouldHoldRisk() {
        assertThat(Arrays.stream(FrontOfficeStatusView.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("customerNumber", "status", "message");
        assertThat(FrontOfficeStatus.values()).containsExactly(
                FrontOfficeStatus.ACTIVE, FrontOfficeStatus.VERIFICATION_PENDING);
    }

    @Test
    @DisplayName("the front-office refusals are customer-not-found only, and name no risk (BR-41)")
    void shouldRefuseWithoutNamingRisk() {
        var useCase = new GetFrontOfficeStatusUseCase(frontOfficeReader(Map.of("C-1", FrontOfficeStatus.ACTIVE)));
        var notFound = Result.<FrontOfficeStatusView, FrontOfficeStatusError>failure(
                new FrontOfficeStatusError.CustomerNotFound());

        assertThat(useCase.ask(L1_IN_DE, new FrontOfficeStatusQuery(number("C-UNKNOWN")))).isEqualTo(notFound);
        assertThat(FrontOfficeStatusQuery.parse("ab\tc"))
                .isEqualTo(Result.failure(new FrontOfficeStatusError.CustomerNotFound()));
        assertThat(FrontOfficeStatusError.class.getPermittedSubclasses()).hasSize(1);
    }
}
