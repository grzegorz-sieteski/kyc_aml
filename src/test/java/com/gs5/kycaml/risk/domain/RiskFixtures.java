package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.ActorRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Valid inputs for the tests. A test starts from a valid evaluation and changes the one thing it is
 * about, so a failure names what changed.
 */
final class RiskFixtures {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    static final ActorContext ONBOARDING_IN_DE =
            new ActorContext(StaticBookingCentres.DE_CENTRE, "onboarding-01", ActorRole.ONBOARDING_SYSTEM);
    static final ActorContext L1_IN_DE =
            new ActorContext(StaticBookingCentres.DE_CENTRE, "l1-01", ActorRole.AML_ANALYST_L1);
    static final ActorContext L2_IN_DE =
            new ActorContext(StaticBookingCentres.DE_CENTRE, "l2-01", ActorRole.AML_ANALYST_L2);
    static final ActorContext MLRO_IN_DE =
            new ActorContext(StaticBookingCentres.DE_CENTRE, "mlro-01", ActorRole.MLRO);
    static final ActorContext ONBOARDING_IN_US =
            new ActorContext(StaticBookingCentres.US_CENTRE, "onboarding-02", ActorRole.ONBOARDING_SYSTEM);
    static final ActorContext ONBOARDING_IN_UNKNOWN_CENTRE =
            new ActorContext(StaticBookingCentres.UNKNOWN_CENTRE, "onboarding-03", ActorRole.ONBOARDING_SYSTEM);

    private RiskFixtures() {
    }

    static RawRatedCountry rated(String country, String riskClass) {
        return new RawRatedCountry(country, riskClass);
    }

    /** E1: an individual in employment, everything in Germany, low risk, branch, nothing found. */
    static RawEvaluateCustomerRisk validRaw() {
        return new RawEvaluateCustomerRisk("C-1000", "INDIVIDUAL_EMPLOYED",
                rated("DE", "LOW"), List.of(rated("DE", "LOW")), rated("DE", "LOW"),
                "DE", "BRANCH", false, "NONE", false, false, false);
    }

    static EvaluateCustomerRiskCommand valid() {
        return parsed(validRaw());
    }

    static EvaluateCustomerRiskCommand parsed(RawEvaluateCustomerRisk raw) {
        return EvaluateCustomerRiskCommand.parse(raw).toOptional().orElseThrow();
    }

    static CustomerNumber number(String text) {
        return CustomerNumber.parse(text).toOptional().orElseThrow();
    }

    static Justification justification(String text) {
        return Justification.parse(text).toOptional().orElseThrow();
    }

    static final String REASON = "Analyst reviewed the alert and the documents";

    static RawRecordComplianceDecision rawDecision(String customerNumber, String kind, long versionViewed) {
        return new RawRecordComplianceDecision(customerNumber, kind, REASON, versionViewed);
    }

    static RecordComplianceDecisionCommand decision(String customerNumber, DecisionKind kind, long versionViewed) {
        return RecordComplianceDecisionCommand.parse(rawDecision(customerNumber, kind.name(), versionViewed))
                .toOptional().orElseThrow();
    }

    static UUID tenantOf(ActorContext actor) {
        return actor.tenantId();
    }
}
