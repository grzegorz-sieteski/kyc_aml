package com.gs5.kycaml.risk.domain;

/**
 * Every reason recording a compliance decision can be refused, except the two the access gate
 * decides (caller not identified, role). Exactly the specification's catalogue for command 2.
 * No variant carries a score, a track or a justification (BR-41, BR-43).
 */
public sealed interface RecordComplianceDecisionError {

    record DecisionKindNotRecognised() implements RecordComplianceDecisionError { }

    record CustomerNotFound() implements RecordComplianceDecisionError { }

    record JustificationMissing() implements RecordComplianceDecisionError { }

    record JustificationTooShort(int minLength, int actualLength) implements RecordComplianceDecisionError { }

    record JustificationTooLong(int maxLength, int actualLength) implements RecordComplianceDecisionError { }

    record JustificationControlCharacter(int codePoint, int index) implements RecordComplianceDecisionError { }

    record RiskProfileChangedSinceViewed() implements RecordComplianceDecisionError { }

    record CriticalFactInForce() implements RecordComplianceDecisionError { }

    record AlreadyFrozen() implements RecordComplianceDecisionError { }

    record AlreadyEscalated() implements RecordComplianceDecisionError { }

    record NothingToClear() implements RecordComplianceDecisionError { }

    record RiskProfileChangedConcurrently() implements RecordComplianceDecisionError { }
}
