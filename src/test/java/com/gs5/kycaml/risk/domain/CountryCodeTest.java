package com.gs5.kycaml.risk.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CountryCodeTest {

    @ParameterizedTest(name = "{0} is an ISO 3166-1 alpha-2 code")
    @ValueSource(strings = {"DE", "US", "GB", "FR", "PL", "AE", "SY", "IR"})
    @DisplayName("an assigned ISO 3166-1 alpha-2 code is accepted exactly as written (BR-08)")
    void shouldAcceptAnAssignedCode(String code) {
        assertThat(CountryCode.parse(code).map(CountryCode::value)).contains(code);
    }

    @ParameterizedTest(name = "[{0}] is not a country code")
    @NullAndEmptySource
    @ValueSource(strings = {"de", "De", "XX", "ZZ", "DEU", "D", "D E", " DE", "DE ", "1A", "UK"})
    @DisplayName("anything else is refused: lower case, unassigned, wrong length, spaces, UK (BR-08)")
    void shouldRefuseAnythingElse(String code) {
        assertThat(CountryCode.parse(code)).isEmpty();
    }
}
