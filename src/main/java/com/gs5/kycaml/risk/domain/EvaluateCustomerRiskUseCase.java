package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;

import java.time.Clock;

/** Evaluates a customer's risk and records the result (command 1). */
public final class EvaluateCustomerRiskUseCase {

    private final RiskProfileRepository repository;
    private final BookingCentres bookingCentres;
    private final RiskProfileIdGenerator idGenerator;
    private final Clock clock;

    public EvaluateCustomerRiskUseCase(RiskProfileRepository repository,
                                       BookingCentres bookingCentres,
                                       RiskProfileIdGenerator idGenerator,
                                       Clock clock) {
        this.repository = repository;
        this.bookingCentres = bookingCentres;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Success returns the new version of the customer's risk profile. The first evaluation of a
     * customer in a booking centre creates the profile at version 1 (BR-29).
     */
    public Result<RiskProfileVersion, EvaluateCustomerRiskError> execute(
            ActorContext actor, EvaluateCustomerRiskCommand command) {
        return bookingCentres.jurisdictionOf(actor.tenantId())
                .map(jurisdiction -> evaluateIn(jurisdiction, actor, command))
                .orElseGet(() -> Result.failure(new EvaluateCustomerRiskError.BookingCentreUnknown()));
    }

    private Result<RiskProfileVersion, EvaluateCustomerRiskError> evaluateIn(
            Jurisdiction jurisdiction, ActorContext actor, EvaluateCustomerRiskCommand command) {
        var evaluation = Evaluation.of(command, jurisdiction);
        var now = clock.instant();
        var eventId = idGenerator.nextEventId();
        var outcome = repository.findOwn(actor.tenantId(), command.customerNumber())
                .map(snapshot -> CustomerRiskProfile.reconstitute(snapshot)
                        .reevaluate(command, evaluation, actor.actorId(), now, eventId))
                .orElseGet(() -> CustomerRiskProfile.create(idGenerator.nextProfileId(), actor.tenantId(),
                        command, evaluation, actor.actorId(), now, eventId));
        return repository.save(outcome.profile().toSnapshot(), outcome.fact(), outcome.events())
                .mapError(_ -> new EvaluateCustomerRiskError.RiskProfileChangedConcurrently());
    }
}
