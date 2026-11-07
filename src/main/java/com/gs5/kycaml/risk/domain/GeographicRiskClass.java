package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/** The risk class of a country and its points (BR-14, A-13). */
public enum GeographicRiskClass {

    LOW(0),
    FATF_GREY_LIST(50),
    FATF_BLACK_LIST_OR_CONFLICT_ZONE(100);

    private final int points;

    GeographicRiskClass(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }

    /** Empty unless the text is exactly the name of a class. */
    public static Optional<GeographicRiskClass> parse(@Nullable String text) {
        return Arrays.stream(values()).filter(constant -> constant.name().equals(text)).findFirst();
    }
}
