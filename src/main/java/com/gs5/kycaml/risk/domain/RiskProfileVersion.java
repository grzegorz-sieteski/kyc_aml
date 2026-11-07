package com.gs5.kycaml.risk.domain;

/** The version of a risk profile: 1 when created, one more with every recorded change (BR-29). */
public record RiskProfileVersion(long value) { }
