package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

/**
 * The customer's core-banking number (BR-06, A-06): 1 to 64 code points, no control character,
 * compared exactly as supplied. The only identifier of a customer that leaves the service (D-05).
 */
public final class CustomerNumber {

    /** BR-06 — measured in code points. */
    public static final int MAX_LENGTH = 64;

    private final String value;

    private CustomerNumber(String value) {
        this.value = value;
    }

    /** Presence, then length, then content (BR-06). */
    @SuppressWarnings("NullAway")
    public static Result<CustomerNumber, CustomerNumberError> parse(@Nullable String text) {
        return null;
    }

    /** The number exactly as supplied. */
    public String value() {
        return value;
    }
}
