package com.gs5.kycaml.shared.kernel;

import module java.base;

import org.jspecify.annotations.Nullable;

/**
 * Renders caller-supplied text inert before it reaches a log line or an RFC 7807
 * {@code ProblemDetail}, without redacting any of it.
 *
 * <p><strong>Writing an unescaped control character into a log line is log injection.</strong> A
 * value containing NEWLINE, written raw, forges what reads as an additional log entry; one
 * containing NUL truncates an entry; one containing a terminal escape sequence can manipulate
 * whoever reads the log. That hazard is reachable precisely where such values are being
 * <em>refused</em> — the refusal path is the one place they are guaranteed to appear — so the
 * escaping has to live on the path that reports them.
 *
 * <p><strong>This is escaping, not masking.</strong> Nothing is hidden and nothing is dropped: a
 * TAB becomes the six readable characters {@code \u0009}, which is both inert and strictly more
 * informative than an invisible character. That is what lets a number stay loggable in full
 * (BR-23) while a refusal about its characters (BR-29) cannot be turned into log forgery. A value
 * that must not be disclosed at all is masked instead, at its own record, and the two mechanisms
 * are deliberately separate.
 *
 * <p>It lives in the shared kernel because {@code domain/}, {@code query/} and {@code adapters/}
 * all need it and {@code query/} may not import {@code domain/}. Escaping belongs to no one module,
 * and three private copies of it would be three places for the category to drift.
 */
public final class LogSafeText {

    private LogSafeText() {
    }

    /**
     * {@code text} with every code point of Unicode general category {@code Cc} replaced by
     * {@code \ uXXXX}, and every other code point left exactly as supplied.
     *
     * <p>Category {@code Cc} is tested with {@link Character#getType(int)}, which is precisely
     * U+0000–U+001F plus U+007F–U+009F. {@code Character.isWhitespace} and a {@code \s} regex are
     * both wrong for this: they disagree with the category over most of the upper range, and SPACE
     * (U+0020) is category {@code Zs} and must be left alone.
     *
     * @param text the value to render inert; {@code null} passes through unchanged, so a caller may
     *             interpolate the result of this method directly into a {@code toString()}
     * @return the escaped text, or {@code null} when {@code text} was {@code null}
     */
    public static @Nullable String escapeControlCharacters(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return text.codePoints()
                .mapToObj(LogSafeText::renderCodePoint)
                .collect(Collectors.joining());
    }

    private static String renderCodePoint(int codePoint) {
        return ControlCharacters.isControl(codePoint)
                ? String.format(Locale.ROOT, "\\u%04X", codePoint)
                : Character.toString(codePoint);
    }
}
