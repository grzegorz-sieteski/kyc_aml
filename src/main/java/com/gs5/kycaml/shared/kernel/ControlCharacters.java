package com.gs5.kycaml.shared.kernel;

import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The single definition of Unicode general category {@code Cc}: U+0000âU+001F and U+007FâU+009F.
 *
 * <p>Defined once here because {@code domain/}, {@code query/} and {@code adapters/} all need it and
 * {@code query/} may not import {@code domain/}. Three private copies would be three places for the
 * category to drift.
 * */
public final class ControlCharacters {

    private ControlCharacters() {
    }

    /**
     * The first character of category {@code Cc} in {@code text}, with its code point and its
     * zero-based index counted in <strong>code points</strong>, or empty when there is none.
     */
    public static Optional<Found> firstIn(String text) {
        var codePoints = text.codePoints().toArray();
        return IntStream.range(0, codePoints.length)
                .filter(index -> isControl(codePoints[index]))
                .mapToObj(index -> new Found(codePoints[index], index))
                .findFirst();
    }

    /**
     * True for exactly category {@code Cc}. {@code Character.isWhitespace} and a {@code \s} regex
     * both disagree with the category over most of U+007F–U+009F and wrongly include SPACE.
     */
    public static boolean isControl(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL;
    }

    /**
     * An offending character: its code point as a number, never the character itself, so that an
     * error record carrying it cannot forge a log line.
     *
     * @param codePoint the offending code point
     * @param index     its zero-based index in code points
     */
    public record Found(int codePoint, int index) {
    }
}
