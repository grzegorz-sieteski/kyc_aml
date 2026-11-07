package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.ControlCharacters;
import com.gs5.kycaml.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * The text explaining a compliance decision (BR-34, A-25): 20 to 2 000 code points; a control
 * character is allowed only as a line break (LF, or CR immediately followed by LF).
 */
public final class Justification {

    /** BR-34 — measured in code points. */
    public static final int MIN_LENGTH = 20;

    /** BR-34, A-25 — measured in code points. */
    public static final int MAX_LENGTH = 2_000;

    private static final int LINE_FEED = 0x0A;
    private static final int CARRIAGE_RETURN = 0x0D;

    private final String value;

    private Justification(String value) {
        this.value = value;
    }

    /** Presence, then length, then content (BR-34). */
    public static Result<Justification, JustificationError> parse(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return Result.failure(new JustificationError.Missing());
        }
        var length = text.codePointCount(0, text.length());
        if (length < MIN_LENGTH) {
            return Result.failure(new JustificationError.TooShort(MIN_LENGTH, length));
        }
        if (length > MAX_LENGTH) {
            return Result.failure(new JustificationError.TooLong(MAX_LENGTH, length));
        }
        return firstControlCharacterOtherThanALineBreak(text)
                .<Result<Justification, JustificationError>>map(found -> Result.failure(
                        new JustificationError.ControlCharacter(found.codePoint(), found.index())))
                .orElseGet(() -> Result.success(new Justification(text)));
    }

    private static Optional<ControlCharacters.Found> firstControlCharacterOtherThanALineBreak(String text) {
        var codePoints = text.codePoints().toArray();
        for (var index = 0; index < codePoints.length; index++) {
            var codePoint = codePoints[index];
            var isLineFeed = codePoint == LINE_FEED;
            var isCarriageReturnOfALineBreak = codePoint == CARRIAGE_RETURN
                    && index + 1 < codePoints.length && codePoints[index + 1] == LINE_FEED;
            if (ControlCharacters.isControl(codePoint) && !isLineFeed && !isCarriageReturnOfALineBreak) {
                return Optional.of(new ControlCharacters.Found(codePoint, index));
            }
        }
        return Optional.empty();
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
