package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * The state of a risk profile as the persistence adapter stores it. {@code version} is null for a
 * profile never stored; the adapter assigns 1 on insert and one more on every update.
 */
public record RiskProfileSnapshot(
        UUID id,
        UUID tenantId,
        CustomerNumber customerNumber,
        Evaluation latestEvaluation,
        Instant evaluatedAt,
        @Nullable RiskTrack decidedTrack,
        @Nullable DecisionRecord latestDecision,
        @Nullable Long version,
        Instant updatedAt) {

    /** Confidential (BR-43): never printed. */
    @Override
    public String toString() {
        return "RiskProfileSnapshot[***]";
    }
}
