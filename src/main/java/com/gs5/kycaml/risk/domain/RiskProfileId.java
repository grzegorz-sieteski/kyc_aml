package com.gs5.kycaml.risk.domain;

import java.util.UUID;

/**
 * The internal identity of a risk profile. Generated in the domain through
 * {@link RiskProfileIdGenerator}; it never leaves the service (D-05).
 */
public record RiskProfileId(UUID value) { }
