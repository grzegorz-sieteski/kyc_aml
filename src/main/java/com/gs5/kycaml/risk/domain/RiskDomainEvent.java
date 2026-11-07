package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * The facts this module announces (spec section 5). Every event names its customer by the customer
 * number and carries the profile version; none carries the internal identity (D-05). They are
 * delivered to compliance consumers only (A-30). No {@code toString} prints risk information
 * (BR-43).
 */
public sealed interface RiskDomainEvent {

    UUID eventId();

    UUID tenantId();

    CustomerNumber customerNumber();

    RiskProfileVersion version();

    Instant occurredAt();

    /** A customer's risk was evaluated. */
    record CustomerRiskEvaluated(
            UUID eventId,
            UUID tenantId,
            CustomerNumber customerNumber,
            RiskProfileVersion version,
            Evaluation evaluation,
            @Nullable RiskTrack decidedTrack,
            RiskTrack effectiveTrack,
            AccountRestriction restriction,
            Instant occurredAt) implements RiskDomainEvent {

        @Override
        public String toString() {
            return "CustomerRiskEvaluated[eventId=" + eventId + ", ***]";
        }
    }

    /** A compliance decision was recorded. It does not carry the justification (A-31). */
    record ComplianceDecisionRecorded(
            UUID eventId,
            UUID tenantId,
            CustomerNumber customerNumber,
            RiskProfileVersion version,
            DecisionKind kind,
            @Nullable RiskTrack decidedTrack,
            RiskTrack effectiveTrack,
            AccountRestriction restriction,
            String decidedBy,
            Instant occurredAt) implements RiskDomainEvent {

        @Override
        public String toString() {
            return "ComplianceDecisionRecorded[eventId=" + eventId + ", ***]";
        }
    }
}
