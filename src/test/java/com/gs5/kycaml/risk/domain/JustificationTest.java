package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JustificationTest {

    private static final String EMOJI = "😀";

    @Test
    @DisplayName("an absent or empty justification is missing (BR-34)")
    void shouldRefuseAMissingJustification() {
        assertThat(Justification.parse(null)).isEqualTo(Result.failure(new JustificationError.Missing()));
        assertThat(Justification.parse("")).isEqualTo(Result.failure(new JustificationError.Missing()));
    }

    @Test
    @DisplayName("19 code points are too short and 20 are accepted (BR-34)")
    void shouldRequireAtLeastTwentyCodePoints() {
        assertThat(Justification.parse("x".repeat(19)))
                .isEqualTo(Result.failure(new JustificationError.TooShort(20, 19)));
        assertThat(Justification.parse("x".repeat(20)).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("2000 code points are accepted and 2001 are too long (BR-34, A-25)")
    void shouldAllowAtMostTwoThousandCodePoints() {
        assertThat(Justification.parse("x".repeat(2000)).isSuccess()).isTrue();
        assertThat(Justification.parse("x".repeat(2001)))
                .isEqualTo(Result.failure(new JustificationError.TooLong(2000, 2001)));
    }

    @Test
    @DisplayName("length is counted in code points, not UTF-16 units (BR-34)")
    void shouldCountCodePoints() {
        assertThat(Justification.parse(EMOJI.repeat(20)).isSuccess()).isTrue();
        assertThat(Justification.parse(EMOJI.repeat(19)))
                .isEqualTo(Result.failure(new JustificationError.TooShort(20, 19)));
        assertThat(Justification.parse(EMOJI.repeat(2000)).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a line feed and a carriage return followed by a line feed are line breaks, so they are allowed (BR-34)")
    void shouldAllowLineBreaks() {
        assertThat(Justification.parse("First line of the reason\nsecond line of it").isSuccess()).isTrue();
        assertThat(Justification.parse("First line of the reason\r\nsecond line of it").isSuccess()).isTrue();
        assertThat(Justification.parse("A reason that ends with a break\n").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a lone carriage return, a tab or NUL is a control character other than a line break (BR-34)")
    void shouldRefuseOtherControlCharacters() {
        var base = "A reason of sufficient length";
        assertThat(Justification.parse(base + "\rmore"))
                .isEqualTo(Result.failure(new JustificationError.ControlCharacter(0x0D, base.length())));
        assertThat(Justification.parse(base + "\tmore"))
                .isEqualTo(Result.failure(new JustificationError.ControlCharacter(0x09, base.length())));
        assertThat(Justification.parse(base + "\u0000more"))
                .isEqualTo(Result.failure(new JustificationError.ControlCharacter(0x00, base.length())));
    }

    @Test
    @DisplayName("a justification that is too long and holds a tab is too long (length before content)")
    void shouldJudgeLengthBeforeContent() {
        assertThat(Justification.parse("x".repeat(2000) + "\t"))
                .isEqualTo(Result.failure(new JustificationError.TooLong(2000, 2001)));
    }

    @Test
    @DisplayName("a justification is confidential: its toString shows none of it (BR-43)")
    void shouldNeverPrintTheJustification() {
        var text = "Secret analyst reasoning about the customer";
        var justification = Justification.parse(text).toOptional().orElseThrow();
        assertThat(justification.toString()).doesNotContain("Secret").doesNotContain(text);
    }
}
