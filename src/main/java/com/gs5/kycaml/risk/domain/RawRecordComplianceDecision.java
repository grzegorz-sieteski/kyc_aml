package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

/**
 * A decision request exactly as a client sent it; nothing is checked yet. The only door from here
 * to a command is {@link RecordComplianceDecisionCommand#parse}.
 */
public record RawRecordComplianceDecision(
        @Nullable String customerNumber,
        @Nullable String kind,
        @Nullable String justification,
        @Nullable Long versionViewed) {

    /** Confidential input (BR-43): never printed. */
    @Override
    public String toString() {
        return "RawRecordComplianceDecision[***]";
    }
}
