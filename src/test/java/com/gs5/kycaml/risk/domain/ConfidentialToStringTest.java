package com.gs5.kycaml.risk.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-11 (stage 6, SR-05; BR-43): the records that carry critical facts, rated countries, drivers and
 * scores print nothing of them. The records around them already mask; these four are handed out by
 * their accessors and would otherwise print it all.
 */
class ConfidentialToStringTest {

    private static Evaluation sanctionedExposedPerson() {
        var high = RiskFixtures.rated("AF", "FATF_BLACK_LIST_OR_CONFLICT_ZONE");
        var raw = new RawEvaluateCustomerRisk("C-1", "POLITICALLY_EXPOSED", high, List.of(high), high, "AE",
                "REMOTE", true, "UNCONFIRMED_FINANCIAL_CRIME", true, true, false);
        return Evaluation.of(RiskFixtures.parsed(raw), Jurisdiction.DE);
    }

    @Test
    @DisplayName("critical facts print as CriticalFacts[***]")
    void shouldMaskCriticalFacts() {
        assertThat(new CriticalFacts(true, true, false)).hasToString("CriticalFacts[***]");
    }

    @Test
    @DisplayName("a rated country prints as RatedCountry[***]")
    void shouldMaskARatedCountry() {
        var country = CountryCode.parse("AF").orElseThrow();

        assertThat(new RatedCountry(country, GeographicRiskClass.FATF_BLACK_LIST_OR_CONFLICT_ZONE)).hasToString("RatedCountry[***]");
    }

    @Test
    @DisplayName("an explanation and each of its category contributions print masked")
    void shouldMaskTheExplanationAndItsContributions() {
        var explanation = sanctionedExposedPerson().explanation();

        assertThat(explanation).hasToString("Explanation[***]");
        assertThat(explanation.categories()).isNotEmpty()
                .allSatisfy(category -> assertThat(category).hasToString("CategoryContribution[***]"));
    }
}
