package com.gs5.kycaml.shared.kernel;

import module java.base;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Canonical UUID text only: 36 characters, hexadecimal, grouped 8-4-4-4-12.
 *
 * <p>{@code UUID.fromString} is lenient: it accepts {@code "1-1-1-1-1"} and returns a real UUID,
 * which could name an existing record. The strict pattern runs first, and {@code fromString} is
 * called only on text already known to be canonical.
 *
 * <p>No JDK API and no dependency in {@code pom.xml} validates canonical UUID text, so the pattern
 * is hand-written here. Re-check this before adding a library.
 */
public final class CanonicalUuid {

    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private CanonicalUuid() {
    }

    /** The UUID the text denotes, or empty when the text is not canonical. */
    public static Optional<UUID> parse(@Nullable String text) {
        if (text == null || !CANONICAL.matcher(text).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(text));
    }
}
