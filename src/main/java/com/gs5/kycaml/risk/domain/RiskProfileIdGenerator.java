package com.gs5.kycaml.risk.domain;

import java.util.UUID;

/**
 * Outbound port: the only source of randomness the write side has, so the aggregate and the use
 * cases stay pure functions of their inputs and a test can pin both ids.
 */
public interface RiskProfileIdGenerator {

    RiskProfileId nextProfileId();

    UUID nextEventId();
}
