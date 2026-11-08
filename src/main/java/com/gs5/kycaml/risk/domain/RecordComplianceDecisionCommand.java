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
 * @param versionViewed the version the deciding person was looking at, not yet judged: a missing
 *                      version is version 0, and the use case refuses any version below 1 as
 *                      "version viewed not valid" after the justification (BR-33, BR-37, D-12)
 */
public record RecordComplianceDecisionCommand(
        CustomerNumber customerNumber,
        DecisionKind kind,
        Result<Justification, JustificationError> justification,
        RiskProfileVersion versionViewed) {

    private static final long NO_VERSION_VIEWED = 0L;

    /**
     * Judges, in order: the kind of decision, then the customer number (a number that cannot be one
     * names no customer). The justification is parsed here but kept as a result.
     */
    public static Result<RecordComplianceDecisionCommand, RecordComplianceDecisionError> parse(
            RawRecordComplianceDecision raw) {
        var viewed = raw.versionViewed();
        var version = new RiskProfileVersion(viewed == null ? NO_VERSION_VIEWED : viewed);
        return Result.<DecisionKind, RecordComplianceDecisionError>fromOptional(
                        DecisionKind.parse(raw.kind()),
                        RecordComplianceDecisionError.DecisionKindNotRecognised::new)
                .flatMap(kind -> CustomerNumber.parse(raw.customerNumber())
                        .<RecordComplianceDecisionError>mapError(
                                _ -> new RecordComplianceDecisionError.CustomerNotFound())
                        .map(number -> new RecordComplianceDecisionCommand(
                                number, kind, Justification.parse(raw.justification()), version)));
    }

    /** Confidential input (BR-43): never printed. */
    @Override
    public String toString() {
        return "RecordComplianceDecisionCommand[***]";
    }
}
