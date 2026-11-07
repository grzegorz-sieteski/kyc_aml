package com.gs5.kycaml.risk.domain;

/** A country together with its geographic risk class, supplied with the evaluation (BR-09, A-09). */
public record RatedCountry(CountryCode code, GeographicRiskClass riskClass) { }
