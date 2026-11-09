package com.gs5.kycaml.risk.domain;

import java.math.BigDecimal;

/** One category's share of the static score: its points, its weight, and points times weight. */
public record CategoryContribution(
        FactorCategory category,
        int points,
        BigDecimal weight,
        BigDecimal contribution) {

    /** Confidential (BR-43): never printed. */
    @Override
    public String toString() {
        return "CategoryContribution[***]";
    }
}
