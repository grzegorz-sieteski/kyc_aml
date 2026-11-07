package com.gs5.kycaml.risk.domain;

import java.time.Instant;

/**
 * A recorded compliance decision (BR-38): which one, by whom, when, why, and on which version.
 *
 * @param versionTaken the version of the profile the decision was taken on
 */
public record DecisionRecord(
        DecisionKind kind,
        String decidedBy,
        Instant decidedAt,
        String justification,
        long versionTaken) {

    /** Confidential (BR-43): neither the justification nor the deciding person is printed. */
    @Override
    public String toString() {
        return "DecisionRecord[***]";
    }
}
