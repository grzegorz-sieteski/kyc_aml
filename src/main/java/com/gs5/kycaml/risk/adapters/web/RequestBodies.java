package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.domain.RawEvaluateCustomerRisk;
import com.gs5.kycaml.risk.domain.RawRatedCountry;
import com.gs5.kycaml.risk.domain.RawRecordComplianceDecision;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Turns a strictly read body into the raw input of a command, field by field (design addendum A step
 * 5). Each field is read on its own and by its JSON type, so a field of the wrong type is absent and
 * refused as itself by the command's parse, never as a neighbouring field (R-07), and the kind of a
 * decision never depends on any other field (R-02). Unknown fields are ignored.
 */
final class RequestBodies {

    private RequestBodies() {
    }

    static RawEvaluateCustomerRisk evaluation(String customerNumber, JsonFields body) {
        return new RawEvaluateCustomerRisk(customerNumber,
                body.text("profileClass").orElse(null),
                ratedCountry(body.object("residence")),
                body.objects("citizenships")
                        .map(countries -> countries.stream().map(RequestBodies::ratedCountryOrBlank).toList())
                        .orElse(null),
                ratedCountry(body.object("birth")),
                body.text("loginCountry").orElse(null),
                body.text("channel").orElse(null),
                body.yesOrNo("highRiskProductRequest").orElse(null),
                body.text("adverseMediaClass").orElse(null),
                body.yesOrNo("sanctionsMatch").orElse(null),
                body.yesOrNo("terroristLink").orElse(null),
                body.yesOrNo("forgedIdentity").orElse(null));
    }

    static RawRecordComplianceDecision decision(String customerNumber, JsonFields body) {
        return new RawRecordComplianceDecision(customerNumber,
                body.text("kind").orElse(null),
                body.text("justification").orElse(null),
                body.wholeNumber("versionViewed").orElse(null));
    }

    private static @Nullable RawRatedCountry ratedCountry(Optional<JsonFields> country) {
        return country.map(RequestBodies::ratedCountryOf).orElse(null);
    }

    /** A citizenship that is not an object keeps its place as a country with nothing given. */
    private static RawRatedCountry ratedCountryOrBlank(Optional<JsonFields> country) {
        return country.map(RequestBodies::ratedCountryOf).orElseGet(() -> new RawRatedCountry(null, null));
    }

    private static RawRatedCountry ratedCountryOf(JsonFields country) {
        return new RawRatedCountry(country.text("country").orElse(null), country.text("riskClass").orElse(null));
    }
}
