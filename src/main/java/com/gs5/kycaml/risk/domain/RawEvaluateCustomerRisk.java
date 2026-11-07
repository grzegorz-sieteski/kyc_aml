package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * An evaluation request exactly as a client sent it; nothing is checked yet. The only door from
 * here to a command is {@link EvaluateCustomerRiskCommand#parse}.
 */
public record RawEvaluateCustomerRisk(
        @Nullable String customerNumber,
        @Nullable String profileClass,
        @Nullable RawRatedCountry residence,
        @Nullable List<RawRatedCountry> citizenships,
        @Nullable RawRatedCountry birth,
        @Nullable String loginCountry,
        @Nullable String channel,
        @Nullable Boolean highRiskProductRequest,
        @Nullable String adverseMediaClass,
        @Nullable Boolean sanctionsMatch,
        @Nullable Boolean terroristLink,
        @Nullable Boolean forgedIdentity) {

    /** Confidential input (BR-43): never printed. */
    @Override
    public String toString() {
        return "RawEvaluateCustomerRisk[***]";
    }
}
