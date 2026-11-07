package com.gs5.kycaml.risk.domain;

/**
 * The only way saving a risk profile can fail: another change of the same profile was recorded
 * after this one read it, or two first evaluations of one customer raced (BR-30).
 */
public record RiskProfileConcurrentChange() { }
