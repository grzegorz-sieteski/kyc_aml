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
    @SuppressWarnings("NullAway")
    public Result<RiskProfileVersion, EvaluateCustomerRiskError> execute(
            ActorContext actor, EvaluateCustomerRiskCommand command) {
        return null;
    }
}
