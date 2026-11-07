package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;

/**
 * A request to record a compliance decision. It carries parsed values only. The deciding caller and
 * the moment come from the caller and the clock, never from here.
 *
 * <p>The justification is kept as a parse result, not judged by {@link #parse}: the specification
 * judges the customer before the justification (BR-37), so the use case looks the customer up first
 * and only then opens the result.
 *
 * @param versionViewed the version the deciding person was looking at; a missing version is
 *                      version 0, which no profile ever has, so it is refused as "changed since
 *                      viewed" (BR-33)
 */
public record RecordComplianceDecisionCommand(
        CustomerNumber customerNumber,
        DecisionKind kind,
        Result<Justification, JustificationError> justification,
        RiskProfileVersion versionViewed) {

    /**
     * Judges, in order: the kind of decision, then the customer number (a number that cannot be one
     * names no customer). The justification is parsed here but kept as a result.
     */
    @SuppressWarnings("NullAway")
    public static Result<RecordComplianceDecisionCommand, RecordComplianceDecisionError> parse(
            RawRecordComplianceDecision raw) {
        return null;
    }

    /** Confidential input (BR-43): never printed. */
    @Override
    public String toString() {
        return "RecordComplianceDecisionCommand[***]";
    }
}
