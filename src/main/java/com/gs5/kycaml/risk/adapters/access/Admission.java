package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorContext;

import java.util.function.BiFunction;

/**
 * Evidence that a permission was checked for this operation. Only {@link OperationGate} mints one.
 *
 * <p>{@link #run} supplies the admitted actor, so the operation cannot be run for a different actor
 * from the one that was checked.
 *
 * @param <C> the raw input
 * @param <R> the operation's result
 */
public final class Admission<C, R> {

    private final ActorContext actor;
    private final BiFunction<ActorContext, C, R> operation;

    /** Package-private: nothing outside the guard can produce evidence of a check. */
    Admission(ActorContext actor, BiFunction<ActorContext, C, R> operation) {
        this.actor = actor;
        this.operation = operation;
    }

    /** Runs the guarded operation for exactly the admitted actor. */
    public R run(C input) {
        return operation.apply(actor, input);
    }
}
