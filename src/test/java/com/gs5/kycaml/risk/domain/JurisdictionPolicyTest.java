package com.gs5.kycaml.risk.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The numbers of the specification, pinned for each jurisdiction in scope (A-13, A-17, A-18, D-06). */
class JurisdictionPolicyTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Jurisdiction.class)
    @DisplayName("category weights are 30%, 25%, 25% and 20% and add up to one (BR-18)")
    void shouldPinTheCategoryWeights(Jurisdiction jurisdiction) {
        var policy = JurisdictionPolicy.of(jurisdiction);

        assertThat(policy.jurisdiction()).isEqualTo(jurisdiction);
        assertThat(policy.geographicWeight()).isEqualTo(new BigDecimal("0.30"));
        assertThat(policy.profileWeight()).isEqualTo(new BigDecimal("0.25"));
        assertThat(policy.mediaWeight()).isEqualTo(new BigDecimal("0.25"));
        assertThat(policy.productsWeight()).isEqualTo(new BigDecimal("0.20"));
        assertThat(policy.geographicWeight().add(policy.profileWeight()).add(policy.mediaWeight())
                .add(policy.productsWeight())).isEqualByComparingTo(BigDecimal.ONE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Jurisdiction.class)
    @DisplayName("the onboarding weights are 1.00 for the static score and 0.00 for the behavioural one (BR-20)")
    void shouldPinTheOnboardingComponentWeights(Jurisdiction jurisdiction) {
        var policy = JurisdictionPolicy.of(jurisdiction);

        assertThat(policy.staticWeight()).isEqualTo(new BigDecimal("1.00"));
        assertThat(policy.behaviouralWeight()).isEqualTo(new BigDecimal("0.00"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Jurisdiction.class)
    @DisplayName("the stricter multiplier for non-residents is 1.25 and every in-scope policy applies it (BR-21, A-18)")
    void shouldPinTheNonResidentMultiplier(Jurisdiction jurisdiction) {
        var policy = JurisdictionPolicy.of(jurisdiction);

        assertThat(policy.strictMultiplier()).isEqualTo(new BigDecimal("1.25"));
        assertThat(policy.strictForNonResidents()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Jurisdiction.class)
    @DisplayName("the track thresholds are 31.00, 61.00 and 85.00 (BR-24)")
    void shouldPinTheTrackThresholds(Jurisdiction jurisdiction) {
        var policy = JurisdictionPolicy.of(jurisdiction);

        assertThat(policy.yellowThreshold()).isEqualTo(new BigDecimal("31.00"));
        assertThat(policy.orangeThreshold()).isEqualTo(new BigDecimal("61.00"));
        assertThat(policy.redThreshold()).isEqualTo(new BigDecimal("85.00"));
    }
}
