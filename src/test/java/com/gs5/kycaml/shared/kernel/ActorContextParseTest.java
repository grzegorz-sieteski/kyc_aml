package com.gs5.kycaml.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActorContext#parse}: missing or malformed claims yield nothing, and an actor id is checked
 * against the width of {@code registered_by VARCHAR(255)} and against category Cc.
 */
class ActorContextParseTest {

    private static final String TENANT = "0a000000-0000-0000-0000-00000000000a";
    private static final String ACTOR = "uw-01";

    @ParameterizedTest(name = "tenant {0} is not a canonical UUID, so nothing is parsed")
    @NullSource
    @ValueSource(strings = {
        "",
        "1-1-1-1-1",
        "0a00000000000000000000000000000000a0",
        "zz000000-0000-0000-0000-00000000000a",
        " 0a000000-0000-0000-0000-00000000000a",
    })
    @DisplayName("a missing or non-canonical tenant yields no caller, never a guessed identity")
    void shouldReturnEmptyWhenTenantIsMissingOrNotCanonicalUuid(String tenant) {
        // When
        var actor = ActorContext.parse(tenant, ACTOR, ActorRole.UNDERWRITER);

        // Then
        assertThat(actor).isEmpty();
    }

    @ParameterizedTest(name = "actor id {0} is absent or blank, so nothing is parsed")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("an absent or blank actor id yields no caller")
    void shouldReturnEmptyWhenActorIdIsMissingOrBlank(String actorId) {
        var actor = ActorContext.parse(TENANT, actorId, ActorRole.UNDERWRITER);

        assertThat(actor).isEmpty();
    }

    @Test
    @DisplayName("a missing role yields no caller")
    void shouldReturnEmptyWhenRoleIsMissing() {
        var actor = ActorContext.parse(TENANT, ACTOR, null);

        assertThat(actor).isEmpty();
    }

    @Test
    @DisplayName("an actor id one code point over the column width is refused")
    void shouldReturnEmptyWhenActorIdExceedsMaximumLength() {
        var actor = ActorContext.parse(TENANT, "a".repeat(256), ActorRole.UNDERWRITER);

        assertThat(actor).isEmpty();
    }

    @Test
    @DisplayName("an actor id of exactly the column width is accepted")
    void shouldAcceptActorIdWhenExactlyMaximumLength() {
        var actor = ActorContext.parse(TENANT, "a".repeat(255), ActorRole.UNDERWRITER);

        assertThat(actor).isPresent();
    }

    @Test
    @DisplayName("the actor id length is counted in code points, so 255 emoji are accepted and 256 "
            + "are not, though they are 510 and 512 UTF-16 units")
    void shouldCountActorIdLengthInCodePointsWhenSupplementaryCharacters() {
        var emoji = "😀";

        assertThat(ActorContext.parse(TENANT, emoji.repeat(255), ActorRole.UNDERWRITER))
                .isPresent();
        assertThat(ActorContext.parse(TENANT, emoji.repeat(256), ActorRole.UNDERWRITER))
                .isEmpty();
    }

    @ParameterizedTest(name = "actor id with control character {0} is refused")
    @ValueSource(strings = {"uw\t01", "uw\u000001", "uw\n01", "uw\u0085"})
    @DisplayName("an actor id containing a control character is refused, because it is written to "
            + "the audit field and to logs")
    void shouldReturnEmptyWhenActorIdContainsControlCharacter(String actorId) {
        var actor = ActorContext.parse(TENANT, actorId, ActorRole.UNDERWRITER);

        assertThat(actor).isEmpty();
    }

    @Test
    @DisplayName("a well-formed claim set yields a caller with the values as supplied")
    void shouldYieldCallerWhenAllComponentsAreWellFormed() {
        // When
        var parsed = ActorContext.parse(TENANT, ACTOR, ActorRole.UNDERWRITER);

        // Then
        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().actorId()).isEqualTo(ACTOR);
        assertThat(parsed.orElseThrow().tenantId().toString()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("an actor id is not trimmed: leading and trailing spaces are kept (BR-07 applies "
            + "the same principle to identities)")
    void shouldKeepActorIdExactlyWhenSurroundedBySpaces() {
        var parsed = ActorContext.parse(TENANT, " uw-01 ", ActorRole.UNDERWRITER);

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().actorId()).isEqualTo(" uw-01 ");
    }

    @Test
    @DisplayName("the parsed caller does not print the actor id")
    void shouldNotPrintActorIdWhenCallerIsPrinted() {
        var parsed = ActorContext.parse(TENANT, ACTOR, ActorRole.UNDERWRITER);

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().toString()).doesNotContain(ACTOR);
    }
}
