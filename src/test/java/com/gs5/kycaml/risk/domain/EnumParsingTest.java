package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Every closed set of values is read by exact name only, and carries the points of the specification. */
class EnumParsingTest {

    private static <E extends Enum<E>> void assertParsesExactNamesOnly(
            Class<E> type, Function<@Nullable String, Optional<E>> parse) {
        for (var constant : type.getEnumConstants()) {
            assertThat(parse.apply(constant.name())).contains(constant);
            assertThat(parse.apply(constant.name().toLowerCase())).isEmpty();
            assertThat(parse.apply(" " + constant.name())).isEmpty();
            assertThat(parse.apply(constant.name() + " ")).isEmpty();
        }
        assertThat(parse.apply(null)).isEmpty();
        assertThat(parse.apply("")).isEmpty();
        assertThat(parse.apply("NOT_A_VALUE")).isEmpty();
    }

    @Test
    @DisplayName("geographic risk classes are read by exact name (BR-09)")
    void shouldParseGeographicRiskClassByExactName() {
        assertParsesExactNamesOnly(GeographicRiskClass.class, GeographicRiskClass::parse);
    }

    @Test
    @DisplayName("customer profile classes are read by exact name (BR-07)")
    void shouldParseCustomerProfileClassByExactName() {
        assertParsesExactNamesOnly(CustomerProfileClass.class, CustomerProfileClass::parse);
    }

    @Test
    @DisplayName("adverse media classes are read by exact name (BR-11)")
    void shouldParseAdverseMediaClassByExactName() {
        assertParsesExactNamesOnly(AdverseMediaClass.class, AdverseMediaClass::parse);
    }

    @Test
    @DisplayName("onboarding channels are read by exact name (BR-10)")
    void shouldParseOnboardingChannelByExactName() {
        assertParsesExactNamesOnly(OnboardingChannel.class, OnboardingChannel::parse);
    }

    @Test
    @DisplayName("decision kinds are read by exact name (BR-32)")
    void shouldParseDecisionKindByExactName() {
        assertParsesExactNamesOnly(DecisionKind.class, DecisionKind::parse);
    }

    @Test
    @DisplayName("geographic points are 0, 50 and 100 (BR-14, A-13)")
    void shouldPinGeographicPoints() {
        assertThat(GeographicRiskClass.LOW.points()).isZero();
        assertThat(GeographicRiskClass.FATF_GREY_LIST.points()).isEqualTo(50);
        assertThat(GeographicRiskClass.FATF_BLACK_LIST_OR_CONFLICT_ZONE.points()).isEqualTo(100);
    }

    @Test
    @DisplayName("customer profile points are 10, 30, 70, 80 and 85 (BR-15, A-13)")
    void shouldPinProfilePoints() {
        assertThat(CustomerProfileClass.INDIVIDUAL_EMPLOYED.points()).isEqualTo(10);
        assertThat(CustomerProfileClass.INDIVIDUAL_STUDENT_OR_NO_INCOME.points()).isEqualTo(30);
        assertThat(CustomerProfileClass.POLITICALLY_EXPOSED.points()).isEqualTo(70);
        assertThat(CustomerProfileClass.COMPLEX_OFFSHORE_OR_TRUST.points()).isEqualTo(80);
        assertThat(CustomerProfileClass.CROSS_BORDER_NON_PROFIT.points()).isEqualTo(85);
    }

    @Test
    @DisplayName("adverse media points are 0, 50 and 95 (BR-16, A-13)")
    void shouldPinAdverseMediaPoints() {
        assertThat(AdverseMediaClass.NONE.points()).isZero();
        assertThat(AdverseMediaClass.UNCONFIRMED_FINANCIAL_CRIME.points()).isEqualTo(50);
        assertThat(AdverseMediaClass.RADICALISATION_OR_PARAMILITARY.points()).isEqualTo(95);
    }

    @Test
    @DisplayName("channel points are 10 for a branch and 40 for remote onboarding (BR-17, A-13)")
    void shouldPinChannelPoints() {
        assertThat(OnboardingChannel.BRANCH.points()).isEqualTo(10);
        assertThat(OnboardingChannel.REMOTE.points()).isEqualTo(40);
    }

    @Test
    @DisplayName("the tracks are ordered from Green to Red, so a higher track compares higher (BR-31)")
    void shouldOrderTracksFromGreenToRed() {
        assertThat(RiskTrack.values()).containsExactly(
                RiskTrack.GREEN, RiskTrack.YELLOW, RiskTrack.ORANGE, RiskTrack.RED);
    }

    @Test
    @DisplayName("each track maps to its account restriction (BR-28, A-22)")
    void shouldMapEachTrackToItsRestriction() {
        assertThat(AccountRestriction.of(RiskTrack.GREEN)).isEqualTo(AccountRestriction.ACTIVE);
        assertThat(AccountRestriction.of(RiskTrack.YELLOW)).isEqualTo(AccountRestriction.SOFT_LIMITS);
        assertThat(AccountRestriction.of(RiskTrack.ORANGE)).isEqualTo(AccountRestriction.EDD_HOLD);
        assertThat(AccountRestriction.of(RiskTrack.RED)).isEqualTo(AccountRestriction.ASSETS_FROZEN);
    }

    @Test
    @DisplayName("the in-scope jurisdictions are the United States, the United Kingdom and Germany (D-06)")
    void shouldPinTheJurisdictionsInScope() {
        assertThat(Jurisdiction.values()).containsExactly(Jurisdiction.US, Jurisdiction.GB, Jurisdiction.DE);
        assertThat(Jurisdiction.US.countryCode()).isEqualTo("US");
        assertThat(Jurisdiction.GB.countryCode()).isEqualTo("GB");
        assertThat(Jurisdiction.DE.countryCode()).isEqualTo("DE");
    }
}
