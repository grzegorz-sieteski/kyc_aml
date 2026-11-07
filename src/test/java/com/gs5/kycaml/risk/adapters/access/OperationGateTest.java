package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.ActorRole;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate: a refused caller never reaches the operation, an admitted caller reaches it exactly
 * once with the admitted actor, and input is read only after admission (BR-03).
 */
class OperationGateTest {

    /** Input that counts every look at it: the probe for "judged before examined". */
    static final class WatchedInput {

        private final AtomicInteger looks = new AtomicInteger();

        String read() {
            looks.incrementAndGet();
            return "input";
        }

        int looks() {
            return looks.get();
        }
    }

    private static final Permission ONLY_L2 = Permission.rolesAllowed(Set.of(ActorRole.AML_ANALYST_L2));

    private static OperationGate<WatchedInput, String> gateReading() {
        return new OperationGate<>(ONLY_L2, (actor, input) -> input.read());
    }

    @Test
    @DisplayName("a refused role never has its input looked at (BR-03)")
    void shouldRefuseTheRoleBeforeTheInputIsExamined() {
        var input = new WatchedInput();

        var outcome = gateReading().admit(Callers.optionalOf(ActorRole.FRONT_OFFICE))
                .map(admission -> admission.run(input));

        assertThat(outcome).isEqualTo(
                Result.<String, AccessRefusal>failure(new AccessRefusal.PermissionDenied(ActorRole.FRONT_OFFICE)));
        assertThat(input.looks()).isZero();
    }

    @Test
    @DisplayName("an admitted caller's input is looked at, so the zero above cannot pass for the wrong reason")
    void shouldExamineTheInputOfAnAdmittedCaller() {
        var input = new WatchedInput();

        var outcome = gateReading().admit(Callers.optionalOf(ActorRole.AML_ANALYST_L2))
                .map(admission -> admission.run(input));

        assertThat(outcome).isEqualTo(Result.<String, AccessRefusal>success("input"));
        assertThat(input.looks()).isEqualTo(1);
    }

    @Test
    @DisplayName("an absent caller is refused as not identified, and the operation never runs (BR-01)")
    void shouldNotRunTheOperationWithoutACaller() {
        var calls = new AtomicInteger();
        var gate = new OperationGate<String, String>(ONLY_L2, (actor, input) -> {
            calls.incrementAndGet();
            return input;
        });

        var outcome = gate.admit(Optional.empty()).map(admission -> admission.run("x"));

        assertThat(outcome).isEqualTo(
                Result.<String, AccessRefusal>failure(new AccessRefusal.CallerNotIdentified()));
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("an admitted caller reaches the operation exactly once, with the admitted actor and its input")
    void shouldRunTheOperationOnceWithTheAdmittedActor() {
        var calls = new AtomicInteger();
        var seen = new AtomicReference<ActorContext>();
        var gate = new OperationGate<String, String>(ONLY_L2, (actor, input) -> {
            calls.incrementAndGet();
            seen.set(actor);
            return input;
        });

        var outcome = gate.admit(Callers.optionalOf(ActorRole.AML_ANALYST_L2)).map(admission -> admission.run("x"));

        assertThat(outcome).isEqualTo(Result.<String, AccessRefusal>success("x"));
        assertThat(calls).hasValue(1);
        assertThat(seen.get().actorId()).isEqualTo(Callers.ACTOR);
        assertThat(seen.get().tenantId().toString()).isEqualTo(Callers.TENANT);
        assertThat(seen.get().role()).isEqualTo(ActorRole.AML_ANALYST_L2);
    }
}
