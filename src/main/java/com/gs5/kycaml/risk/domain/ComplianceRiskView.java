package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What compliance staff see of a customer's risk (query 1). It carries what the store holds, as the
 * store holds it, and no internal identity (D-05). The version lets the deciding person tell whether
 * the view is current (BR-33). Confidential: never printed (BR-43).
 */
public record ComplianceRiskView(
        String customerNumber,
        JurisdictionPolicy policy,
        BigDecimal totalScore,
        RiskTrack evaluatedTrack,
        @Nullable RiskTrack decidedTrack,
        RiskTrack effectiveTrack,
        AccountRestriction restriction,
        List<CriticalFactField> criticalFacts,
        Explanation explanation,
        @Nullable DecisionRecord latestDecision,
        Instant evaluatedAt,
        long version) {

    @Override
    public String toString() {
        return "ComplianceRiskView[***]";
    }
}
