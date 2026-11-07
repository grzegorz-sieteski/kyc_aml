package com.gs5.kycaml.risk.domain;

import java.time.Instant;

/** What goes into the append-only history with each new version (BR-29). */
public sealed interface RecordedFact {

    /** An evaluation, with the inputs it was made from. */
    record EvaluationFact(
            EvaluateCustomerRiskCommand inputs,
            Evaluation evaluation,
            String actorId,
            Instant recordedAt) implements RecordedFact {

        @Override
        public String toString() {
            return "EvaluationFact[***]";
        }
    }

    /** A compliance decision. */
    record DecisionFact(DecisionRecord decision) implements RecordedFact { }
}
