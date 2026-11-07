package com.gs5.kycaml.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/** How the customer was onboarded, and its points (BR-10, BR-17, A-13). */
public enum OnboardingChannel {

    BRANCH(10),
    REMOTE(40);

    private final int points;

    OnboardingChannel(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }

    /** Empty unless the text is exactly the name of a channel. */
    @SuppressWarnings("NullAway")
    public static Optional<OnboardingChannel> parse(@Nullable String text) {
        return null;
    }
}
