package com.gs5.kycaml.risk.adapters.config;

import com.gs5.kycaml.risk.domain.RiskProfileId;
import com.gs5.kycaml.risk.domain.RiskProfileIdGenerator;

import java.util.UUID;

/** Random identities for profiles and events. A profile id never leaves the service (D-05). */
final class RandomRiskProfileIdGenerator implements RiskProfileIdGenerator {

    @Override
    public RiskProfileId nextProfileId() {
        return new RiskProfileId(UUID.randomUUID());
    }

    @Override
    public UUID nextEventId() {
        return UUID.randomUUID();
    }
}
