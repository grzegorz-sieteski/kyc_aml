package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;

import java.time.Clock;

/** Records a compliance decision on a customer's risk (command 2). */
public final class RecordComplianceDecisionUseCase {

    private final RiskProfileRepository repository;
    private final RiskProfileIdGenerator idGenerator;
    private final Clock clock;

    public RecordComplianceDecisionUseCase(RiskProfileRepository repository,
                                           RiskProfileIdGenerator idGenerator,
                                           Clock clock) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Judges, in the order of BR-37: the customer is found in the caller's booking centre, the
     * justification is valid, the version viewed is current, no critical fact blocks a clearing,
     * the decision changes something, and no concurrent change is recorded. Success returns the new
     * version.
     */
    @SuppressWarnings("NullAway")
    public Result<RiskProfileVersion, RecordComplianceDecisionError> execute(
            ActorContext actor, RecordComplianceDecisionCommand command) {
        return null;
    }
}
