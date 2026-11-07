package com.gs5.kycaml.risk.domain;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Pins both ids so a test can assert them. Event ids count up so two events differ. */
public final class FixedRiskProfileIdGenerator implements RiskProfileIdGenerator {

    static final UUID PROFILE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AtomicInteger eventCounter = new AtomicInteger();

    @Override
    public RiskProfileId nextProfileId() {
        return new RiskProfileId(PROFILE_UUID);
    }

    @Override
    public UUID nextEventId() {
        return new UUID(2L, eventCounter.incrementAndGet());
    }

    static UUID eventId(int sequence) {
        return new UUID(2L, sequence);
    }
}
