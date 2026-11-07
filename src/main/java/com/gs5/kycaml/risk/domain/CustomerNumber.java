package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ControlCharacters;
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

    /**
     * Presence, then length, then content (BR-06). {@code isEmpty()}, never {@code isBlank()}: a
     * number of spaces is present and valid, because trimming is not a decision anybody took.
     */
    public static Result<CustomerNumber, CustomerNumberError> parse(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return Result.failure(new CustomerNumberError.Missing());
        }
        var length = text.codePointCount(0, text.length());
        if (length > MAX_LENGTH) {
            return Result.failure(new CustomerNumberError.TooLong(MAX_LENGTH, length));
        }
        return ControlCharacters.firstIn(text)
                .<Result<CustomerNumber, CustomerNumberError>>map(found -> Result.failure(
                        new CustomerNumberError.ControlCharacter(found.codePoint(), found.index())))
                .orElseGet(() -> Result.success(new CustomerNumber(text)));
    }

    /** The number exactly as supplied. */
    public String value() {
        return value;
    }
}
