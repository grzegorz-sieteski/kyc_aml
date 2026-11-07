package com.gs5.kycaml.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-06: the escaping mechanism had zero tests, so {@code verify} passed with its body replaced
 * by {@code return text;}. These pin the mechanism itself, independently of any record using it.
 */
class LogSafeTextTest {

    @ParameterizedTest
    @CsvSource({
        "0,   \\u0000",   // NUL — truncates a log line
        "9,   \\u0009",   // TAB
        "10,  \\u000A",   // NEWLINE — forges a log line
        "13,  \\u000D",   // CARRIAGE RETURN
        "27,  \\u001B",   // ESC — terminal escape sequences
        "31,  \\u001F",   // last of the lower range
        "127, \\u007F",   // first of the upper range
        "133, \\u0085",   // NEXT LINE — what isWhitespace() and \\s get wrong
        "159, \\u009F",   // last of the upper range
    })
    @DisplayName("every control character (category Cc) is escaped as \\uXXXX, uppercase hex")
    void every_control_character_is_escaped(int codePoint, String escaped) {
        var text = "A" + Character.toString(codePoint) + "B";

        assertThat(LogSafeText.escapeControlCharacters(text)).isEqualTo("A" + escaped + "B");
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 126, 160, 8232})
    @DisplayName("characters outside category Cc pass through unchanged: SPACE, TILDE, "
            + "NO-BREAK SPACE, and LINE SEPARATOR (U+2028, category Zl, not Cc)")
    void characters_outside_the_category_are_unchanged(int codePoint) {
        var text = "A" + Character.toString(codePoint) + "B";

        assertThat(LogSafeText.escapeControlCharacters(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("a supplementary character survives intact as one code point, not as two "
            + "escaped surrogates")
    void a_supplementary_character_survives_intact() {
        var text = "A😀B";

        assertThat(LogSafeText.escapeControlCharacters(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null passes through as null, so a toString() may interpolate it directly")
    void null_passes_through() {
        assertThat(LogSafeText.escapeControlCharacters(null)).isNull();
    }

    @Test
    @DisplayName("the hostile log-forging reference becomes one inert line")
    void a_forged_log_line_is_rendered_inert() {
        assertThat(LogSafeText.escapeControlCharacters("S-1\n2026-11-02 INFO admin login ok"))
                .isEqualTo("S-1\\u000A2026-11-02 INFO admin login ok")
                .doesNotContain("\n");
    }
}
