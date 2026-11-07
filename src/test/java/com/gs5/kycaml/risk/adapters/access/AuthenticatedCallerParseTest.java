package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthenticatedCaller#parse}: the role is matched exactly by constant name, and anything
 * missing or malformed yields nothing. The tenant and actor rules are those of
 * {@code ActorContext.parse}, pinned there, and only one case is repeated here to show they apply.
 */
class AuthenticatedCallerParseTest {

    private static final String TENANT = "0a000000-0000-0000-0000-00000000000a";
    private static final String ACTOR = "uw-01";

    @ParameterizedTest(name = "role {0} is matched by its exact constant name")
    @EnumSource(ActorRole.class)
    @DisplayName("every role is recognised by its exact constant name and carried as that role")
    void shouldYieldCallerWhenRoleIsExactConstantName(ActorRole role) {
        var caller = AuthenticatedCaller.parse(TENANT, ACTOR, role.name());

        assertThat(caller).isPresent();
        assertThat(caller.orElseThrow().role()).isEqualTo(role);
    }

    @ParameterizedTest(name = "role {0} is not an exact constant name, so nothing is parsed")
    @NullSource
    @ValueSource(strings = {"viewer", "Underwriter", "SUPERUSER", " AML_ANALYST_L2", "AML_ANALYST_L2 ", ""})
    @DisplayName("a role spelled in any other way, or unknown, yields no caller: never case-folded, "
            + "never trimmed, and never an exception")
    void shouldReturnEmptyWhenRoleIsNotExactConstantName(String role) {
        assertThat(AuthenticatedCaller.parse(TENANT, ACTOR, role)).isEmpty();
    }

    @ParameterizedTest(name = "tenant {0} is malformed, so nothing is parsed")
    @NullSource
    @ValueSource(strings = {"", "1-1-1-1-1", "not-a-uuid-at-all-not-a-uuid-at-all-0000"})
    @DisplayName("a malformed tenant yields no caller, whatever the role says")
    void shouldReturnEmptyWhenTenantIsMalformed(String tenant) {
        assertThat(AuthenticatedCaller.parse(tenant, ACTOR, ActorRole.AML_ANALYST_L2.name())).isEmpty();
    }

    @Test
    @DisplayName("an actor id containing a control character yields no caller")
    void shouldReturnEmptyWhenActorIdContainsControlCharacter() {
        assertThat(AuthenticatedCaller.parse(TENANT, "uw\t01", ActorRole.AML_ANALYST_L2.name()))
                .isEmpty();
    }

    @Test
    @DisplayName("a parsed caller's actor does not print the actor id")
    void shouldNotPrintActorIdWhenCallerActorIsPrinted() {
        var caller = AuthenticatedCaller.parse(TENANT, ACTOR, ActorRole.AML_ANALYST_L2.name());

        assertThat(caller).isPresent();
        assertThat(caller.orElseThrow().actor().toString()).doesNotContain(ACTOR);
    }
}
