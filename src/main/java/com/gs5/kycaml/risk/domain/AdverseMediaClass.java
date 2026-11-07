package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/** What adverse-media screening found, and its points (BR-11, BR-16, A-13). */
public enum AdverseMediaClass {

    NONE(0),
    UNCONFIRMED_FINANCIAL_CRIME(50),
    RADICALISATION_OR_PARAMILITARY(95);

    private final int points;

    AdverseMediaClass(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }

    /** Empty unless the text is exactly the name of a class. */
    public static Optional<AdverseMediaClass> parse(@Nullable String text) {
        return Arrays.stream(values()).filter(constant -> constant.name().equals(text)).findFirst();
    }
}
