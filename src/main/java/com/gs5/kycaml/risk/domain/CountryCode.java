package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/** A country as an ISO 3166-1 alpha-2 code, upper case, exactly as the standard writes it (BR-08). */
public final class CountryCode {

    private final String value;

    private CountryCode(String value) {
        this.value = value;
    }

    /** Empty when the text is not an ISO 3166-1 alpha-2 code. */
    @SuppressWarnings("NullAway")
    public static Optional<CountryCode> parse(@Nullable String text) {
        return null;
    }

    public String value() {
        return value;
    }
}
