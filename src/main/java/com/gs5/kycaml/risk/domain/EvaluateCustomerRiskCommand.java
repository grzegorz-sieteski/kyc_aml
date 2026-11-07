package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;

import java.util.List;

/**
 * A request to evaluate a customer's risk. It carries parsed values only. The booking centre comes
 * from the caller and the moment from the clock, never from here.
 */
public record EvaluateCustomerRiskCommand(
        CustomerNumber customerNumber,
        CustomerProfileClass profileClass,
        RatedCountry residence,
        List<RatedCountry> citizenships,
        RatedCountry birth,
        CountryCode loginCountry,
        OnboardingChannel channel,
        boolean highRiskProductRequest,
        AdverseMediaClass adverseMedia,
        CriticalFacts criticalFacts) {

    /**
     * Judges the fields in the order of the specification's catalogue: customer number, profile
     * class, each country (residence, citizenships, birth, login), no citizenship, each geographic
     * class, channel, product request, adverse media, then each critical fact. The first failure
     * is the one returned.
     */
    @SuppressWarnings("NullAway")
    public static Result<EvaluateCustomerRiskCommand, EvaluateCustomerRiskError> parse(
            RawEvaluateCustomerRisk raw) {
        return null;
    }

    /** Confidential input (BR-43): never printed. */
    @Override
    public String toString() {
        return "EvaluateCustomerRiskCommand[***]";
    }
}
