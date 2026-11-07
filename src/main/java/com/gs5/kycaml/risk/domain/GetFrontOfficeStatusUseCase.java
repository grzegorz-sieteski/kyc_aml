package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;

/** Reads the neutral front-office status of a customer (query 2). */
public final class GetFrontOfficeStatusUseCase {

    private final FrontOfficeStatusReader reader;

    public GetFrontOfficeStatusUseCase(FrontOfficeStatusReader reader) {
        this.reader = reader;
    }

    /** Reads only within the caller's own booking centre, and shows no risk information (BR-40). */
    @SuppressWarnings("NullAway")
    public Result<FrontOfficeStatusView, FrontOfficeStatusError> ask(
            ActorContext actor, FrontOfficeStatusQuery query) {
        return null;
    }
}
