package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/** The kind of customer and its points (BR-07, BR-15, A-13). */
public enum CustomerProfileClass {

    INDIVIDUAL_EMPLOYED(10),
    INDIVIDUAL_STUDENT_OR_NO_INCOME(30),
    POLITICALLY_EXPOSED(70),
    COMPLEX_OFFSHORE_OR_TRUST(80),
    CROSS_BORDER_NON_PROFIT(85);

    private final int points;

    CustomerProfileClass(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }

    /** Empty unless the text is exactly the name of a class. */
    public static Optional<CustomerProfileClass> parse(@Nullable String text) {
        return Arrays.stream(values()).filter(constant -> constant.name().equals(text)).findFirst();
    }
}
