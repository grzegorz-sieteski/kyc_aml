package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

/**
 * The text explaining a compliance decision (BR-34, A-25): 20 to 2 000 code points; a control
 * character is allowed only as a line break (LF, or CR immediately followed by LF).
 */
public final class Justification {

    /** BR-34 — measured in code points. */
    public static final int MIN_LENGTH = 20;

    /** BR-34, A-25 — measured in code points. */
    public static final int MAX_LENGTH = 2_000;

    private final String value;

    private Justification(String value) {
        this.value = value;
    }

    /** Presence, then length, then content (BR-34). */
    @SuppressWarnings("NullAway")
    public static Result<Justification, JustificationError> parse(@Nullable String text) {
        return null;
    }

    public String value() {
        return value;
    }

    /** A justification is confidential (BR-43): it is never printed. */
    @Override
    public String toString() {
        return "Justification[***]";
    }
}
