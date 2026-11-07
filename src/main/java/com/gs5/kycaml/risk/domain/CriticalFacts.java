package com.gs5.kycaml.risk.domain;

/** The three critical facts, each yes or no, supplied with every evaluation (BR-12, A-11). */
public record CriticalFacts(boolean sanctionsMatch, boolean terroristLink, boolean forgedIdentity) { }
