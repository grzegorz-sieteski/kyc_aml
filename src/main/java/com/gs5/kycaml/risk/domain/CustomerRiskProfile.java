package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The risk profile of one customer in one booking centre: <strong>package-private</strong>, with a
 * private constructor and named factories, so nothing outside this package can reach past the use
 * cases.
 *
 * <p>Immutable: every change returns the new state together with the fact it records and the events
 * it announces. {@code version} is the version this state was loaded at (null for a profile never
 * stored); the persistence adapter moves it forward (BR-29, BR-30). The aggregate does no I/O, reads
 * no clock and makes no random id: all three arrive as arguments.
 */
final class CustomerRiskProfile {

    /** A new state, the fact that caused it and the events it announces. */
    record Outcome(CustomerRiskProfile profile, RecordedFact fact, List<RiskDomainEvent> events) { }

    private static final long FIRST_VERSION = 1L;

    private final RiskProfileId id;
    private final UUID tenantId;
    private final CustomerNumber customerNumber;
    private final @Nullable Long version;
    private final Evaluation latestEvaluation;
    private final Instant evaluatedAt;
    private final @Nullable RiskTrack decidedTrack;
    private final @Nullable DecisionRecord latestDecision;
    private final Instant updatedAt;

    private CustomerRiskProfile(RiskProfileId id, UUID tenantId, CustomerNumber customerNumber,
                                @Nullable Long version, Evaluation latestEvaluation, Instant evaluatedAt,
                                @Nullable RiskTrack decidedTrack, @Nullable DecisionRecord latestDecision,
                                Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerNumber = customerNumber;
        this.version = version;
        this.latestEvaluation = latestEvaluation;
        this.evaluatedAt = evaluatedAt;
        this.decidedTrack = decidedTrack;
        this.latestDecision = latestDecision;
        this.updatedAt = updatedAt;
    }

    /** The first evaluation of a customer in a booking centre (BR-29). */
    static Outcome create(RiskProfileId id, UUID tenantId, EvaluateCustomerRiskCommand inputs,
                          Evaluation evaluation, String actorId, Instant now, UUID eventId) {
        var created = new CustomerRiskProfile(id, tenantId, inputs.customerNumber(), null, evaluation, now,
                null, null, now);
        return created.evaluated(inputs, evaluation, actorId, now, eventId);
    }

    /** Rebuilds state loaded from the database, version included. */
    static CustomerRiskProfile reconstitute(RiskProfileSnapshot snapshot) {
        return new CustomerRiskProfile(new RiskProfileId(snapshot.id()), snapshot.tenantId(),
                snapshot.customerNumber(), snapshot.version(), snapshot.latestEvaluation(),
                snapshot.evaluatedAt(), snapshot.decidedTrack(), snapshot.latestDecision(), snapshot.updatedAt());
    }

    /**
     * A later evaluation replaces the evaluation and keeps a decided track (BR-13, BR-31): an
     * evaluation never lowers a track compliance staff set.
     */
    Outcome reevaluate(EvaluateCustomerRiskCommand inputs, Evaluation evaluation, String actorId, Instant now,
                       UUID eventId) {
        return new CustomerRiskProfile(id, tenantId, customerNumber, version, evaluation, now, decidedTrack,
                latestDecision, now).evaluated(inputs, evaluation, actorId, now, eventId);
    }

    /**
     * Records a compliance decision. Judged in the order of BR-37: the version viewed is current
     * (BR-33), no critical fact blocks a clearing (BR-36), and the decision changes something
     * (BR-35). The evaluation, its score and its explanation are untouched (BR-38).
     */
    Result<Outcome, RecordComplianceDecisionError> decide(DecisionKind kind, Justification justification,
                                                          RiskProfileVersion versionViewed, String actorId,
                                                          Instant now, UUID eventId) {
        if (version == null || version != versionViewed.value()) {
            return Result.failure(new RecordComplianceDecisionError.RiskProfileChangedSinceViewed());
        }
        if (kind == DecisionKind.CLEAR_FALSE_POSITIVE && !latestEvaluation.explanation().criticalFacts().isEmpty()) {
            return Result.failure(new RecordComplianceDecisionError.CriticalFactInForce());
        }
        var refusal = decisionThatChangesNothing(kind);
        if (refusal != null) {
            return Result.failure(refusal);
        }

        var newDecidedTrack = switch (kind) {
            case FREEZE_ASSETS -> RiskTrack.RED;
            case ESCALATE_EDD -> RiskTrack.ORANGE;
            case CLEAR_FALSE_POSITIVE -> null;
        };
        var decision = new DecisionRecord(kind, actorId, now, justification.value(), versionViewed.value());
        var next = new CustomerRiskProfile(id, tenantId, customerNumber, version, latestEvaluation,
                evaluatedAt, newDecidedTrack, decision, now);
        var effective = next.effectiveTrack();
        var event = new RiskDomainEvent.ComplianceDecisionRecorded(eventId, tenantId, customerNumber,
                next.nextVersion(), kind, newDecidedTrack, effective, AccountRestriction.of(effective),
                actorId, now);
        return Result.success(new Outcome(next, new RecordedFact.DecisionFact(decision), List.of(event)));
    }

    /** What the persistence adapter stores; the version stays exactly as loaded. */
    RiskProfileSnapshot toSnapshot() {
        return new RiskProfileSnapshot(id.value(), tenantId, customerNumber, latestEvaluation, evaluatedAt,
                decidedTrack, latestDecision, version, updatedAt);
    }

    private Outcome evaluated(EvaluateCustomerRiskCommand inputs, Evaluation evaluation, String actorId,
                              Instant now, UUID eventId) {
        var effective = effectiveTrack();
        var event = new RiskDomainEvent.CustomerRiskEvaluated(eventId, tenantId, customerNumber, nextVersion(),
                evaluation, decidedTrack, effective, AccountRestriction.of(effective), now);
        return new Outcome(this, new RecordedFact.EvaluationFact(inputs, evaluation, actorId, now), List.of(event));
    }

    private @Nullable RecordComplianceDecisionError decisionThatChangesNothing(DecisionKind kind) {
        return switch (kind) {
            case FREEZE_ASSETS -> decidedTrack == RiskTrack.RED
                    ? new RecordComplianceDecisionError.AlreadyFrozen() : null;
            case ESCALATE_EDD -> decidedTrack != null && decidedTrack.compareTo(RiskTrack.ORANGE) >= 0
                    ? new RecordComplianceDecisionError.AlreadyEscalated() : null;
            case CLEAR_FALSE_POSITIVE -> decidedTrack == null
                    ? new RecordComplianceDecisionError.NothingToClear() : null;
        };
    }

    /** BR-31: the decided track while one exists and is not lower than the evaluated track. */
    private RiskTrack effectiveTrack() {
        var evaluated = latestEvaluation.evaluatedTrack();
        return decidedTrack == null || decidedTrack.compareTo(evaluated) < 0 ? evaluated : decidedTrack;
    }

    private RiskProfileVersion nextVersion() {
        return new RiskProfileVersion(version == null ? FIRST_VERSION : version + 1L);
    }
}
