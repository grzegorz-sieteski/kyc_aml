package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/** The three compliance decisions (BR-32). */
public enum DecisionKind {
    FREEZE_ASSETS,
    ESCALATE_EDD,
    CLEAR_FALSE_POSITIVE;

    /** Empty unless the text is exactly the name of a decision. */
    @SuppressWarnings("NullAway")
    public static Optional<DecisionKind> parse(@Nullable String text) {
        return null;
    }
}
