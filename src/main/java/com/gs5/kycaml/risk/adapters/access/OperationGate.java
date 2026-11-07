package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.Result;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The only door to an operation. It admits a caller, or refuses it before any input is parsed.
 *
 * <p>Two type parameters, as the product owner decided. The refusal type is always
 * {@link AccessRefusal}, so search's {@code switch} has a {@code PermissionDenied} arm that can
 * never run. A test proves it never runs, because the type no longer does.
 *
 * <p>The operation can run only through the {@link Admission} that {@link #admit} returns, so an
 * unauthorised caller's input is never examined by our code. The constructor is public only so
 * configuration can build a gate.
 *
 *
 * @param <C> the raw input the operation receives after admission
 * @param <R> the operation's result
 */
public final class OperationGate<C, R> {

    private final Permission permission;
    private final BiFunction<ActorContext, C, R> operation;

    public OperationGate(Permission permission, BiFunction<ActorContext, C, R> operation) {
        this.permission = permission;
        this.operation = operation;
    }

    /** Admits the caller, which yields the only handle to run the operation, or the refusal. */
    public Result<Admission<C, R>, AccessRefusal> admit(Optional<AuthenticatedCaller> caller) {
        return permission.check(caller)
                .map(admitted -> new Admission<>(admitted.actor(), operation));
    }
}
