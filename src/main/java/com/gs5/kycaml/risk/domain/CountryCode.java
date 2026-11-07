package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** A country as an ISO 3166-1 alpha-2 code, upper case, exactly as the standard writes it (BR-08). */
public final class CountryCode {

    /** The codes the JDK knows as assigned ISO 3166-1 alpha-2 codes; no code of our own. */
    private static final Set<String> ISO_CODES = Set.of(Locale.getISOCountries());

    private final String value;

    private CountryCode(String value) {
        this.value = value;
    }

    /** Empty when the text is not an ISO 3166-1 alpha-2 code. */
    public static Optional<CountryCode> parse(@Nullable String text) {
        if (text == null || !ISO_CODES.contains(text)) {
            return Optional.empty();
        }
        return Optional.of(new CountryCode(text));
    }

    public String value() {
        return value;
    }
}
