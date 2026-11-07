package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerNumberTest {

    private static final String EMOJI = "😀";

    @Test
    @DisplayName("a customer number is kept exactly as supplied: case and surrounding spaces survive (BR-06)")
    void shouldKeepTheNumberExactlyWhenItIsAccepted() {
        assertThat(CustomerNumber.parse(" aBc-1 ").toOptional().map(CustomerNumber::value)).contains(" aBc-1 ");
    }

    @Test
    @DisplayName("a number of spaces is present and valid, because only an empty number is missing (BR-06)")
    void shouldAcceptANumberMadeOnlyOfSpaces() {
        assertThat(CustomerNumber.parse("   ").toOptional().map(CustomerNumber::value)).contains("   ");
    }

    @Test
    @DisplayName("an absent or empty number is missing (BR-06)")
    void shouldRefuseAMissingNumber() {
        assertThat(CustomerNumber.parse(null)).isEqualTo(Result.failure(new CustomerNumberError.Missing()));
        assertThat(CustomerNumber.parse("")).isEqualTo(Result.failure(new CustomerNumberError.Missing()));
    }

    @Test
    @DisplayName("64 code points are accepted and 65 are too long, counted in code points not UTF-16 units (BR-06)")
    void shouldCountLengthInCodePoints() {
        assertThat(CustomerNumber.parse("a".repeat(64)).isSuccess()).isTrue();
        assertThat(CustomerNumber.parse(EMOJI.repeat(64)).isSuccess()).isTrue();
        assertThat(CustomerNumber.parse("a".repeat(65)))
                .isEqualTo(Result.failure(new CustomerNumberError.TooLong(64, 65)));
        assertThat(CustomerNumber.parse(EMOJI.repeat(65)))
                .isEqualTo(Result.failure(new CustomerNumberError.TooLong(64, 65)));
    }

    @Test
    @DisplayName("a number that is both too long and holds a control character is too long (length before content)")
    void shouldJudgeLengthBeforeContent() {
        assertThat(CustomerNumber.parse("a".repeat(64) + "\t"))
                .isEqualTo(Result.failure(new CustomerNumberError.TooLong(64, 65)));
    }

    @Test
    @DisplayName("a control character is refused with its code point and its index in code points (BR-06)")
    void shouldNameTheControlCharacterAndItsIndex() {
        assertThat(CustomerNumber.parse("ab\tcd"))
                .isEqualTo(Result.failure(new CustomerNumberError.ControlCharacter(0x09, 2)));
        assertThat(CustomerNumber.parse(EMOJI + "\n"))
                .isEqualTo(Result.failure(new CustomerNumberError.ControlCharacter(0x0A, 1)));
    }

    @ParameterizedTest(name = "U+{0} is a control character")
    @ValueSource(ints = {0x00, 0x01, 0x1F, 0x7F, 0x80, 0x85, 0x9F})
    @DisplayName("every character of category Cc is refused, NUL and NEL included (BR-06)")
    void shouldRefuseEveryControlCharacter(int codePoint) {
        var text = "A" + new String(Character.toChars(codePoint)) + "B";
        assertThat(CustomerNumber.parse(text))
                .isEqualTo(Result.failure(new CustomerNumberError.ControlCharacter(codePoint, 1)));
    }

    @Test
    @DisplayName("SPACE and NO-BREAK SPACE are not control characters, so they are accepted (BR-06)")
    void shouldAcceptSpaceAndNoBreakSpace() {
        assertThat(CustomerNumber.parse("A B C").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("two numbers that differ in letter case are different numbers, never normalised (BR-06)")
    void shouldNeverNormaliseTheNumber() {
        assertThat(CustomerNumber.parse("abc").toOptional().map(CustomerNumber::value)).contains("abc");
        assertThat(CustomerNumber.parse("ABC").toOptional().map(CustomerNumber::value)).contains("ABC");
    }
}
