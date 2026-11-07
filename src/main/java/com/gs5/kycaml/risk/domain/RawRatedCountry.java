package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

/** A country and its risk class exactly as a client sent them; not yet checked. */
public record RawRatedCountry(@Nullable String country, @Nullable String riskClass) { }
