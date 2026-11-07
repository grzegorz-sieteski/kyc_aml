package com.gs5.kycaml.shared.kernel;

import module java.base;

/**
 * The only way a business failure travels through this codebase.
 *
 * <p>Exceptions are not used for control flow: they are an invisible {@code goto}, they cost a
 * stack trace capture on every virtual thread, and they let a caller forget a branch silently.
 * A {@code Result} makes every failure a value the compiler can see, and pairing it with a
 * {@code sealed interface *Error} makes an exhaustive {@code switch} impossible to get wrong.
 *
 * <p>Note what is absent from this file: there is no {@code throw} statement anywhere, including
 * in {@link #orElseGet}. A build-time Checkstyle rule enforces that for all of {@code src/main}.
 *
 * @param <T> value produced on the happy path
 * @param <E> closed hierarchy of business failures, normally a {@code sealed interface *Error}
 */
public sealed interface Result<T, E> {

    record Success<T, E>(T value) implements Result<T, E> { }

    record Failure<T, E>(E error) implements Result<T, E> { }

    static <T, E> Result<T, E> success(T value) {
        return new Success<>(value);
    }

    static <T, E> Result<T, E> failure(E error) {
        return new Failure<>(error);
    }

    default boolean isSuccess() {
        return switch (this) {
            case Success<T, E> _ -> true;
            case Failure<T, E> _ -> false;
        };
    }

    default boolean isFailure() {
        return !isSuccess();
    }

    /** Transforms the value, leaving a failure untouched. */
    default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
        return switch (this) {
            case Success<T, E>(var value) -> new Success<>(mapper.apply(value));
            case Failure<T, E>(var error) -> new Failure<>(error);
        };
    }

    /** Chains another fallible step; the first failure short-circuits the rest. */
    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> mapper) {
        return switch (this) {
            case Success<T, E>(var value) -> mapper.apply(value);
            case Failure<T, E>(var error) -> new Failure<>(error);
        };
    }

    /** Translates the failure, e.g. from an adapter error to a domain error. */
    default <F> Result<T, F> mapError(Function<? super E, ? extends F> mapper) {
        return switch (this) {
            case Success<T, E>(var value) -> new Success<>(value);
            case Failure<T, E>(var error) -> new Failure<>(mapper.apply(error));
        };
    }

    /** Turns a success into a failure when an invariant does not hold. */
    default Result<T, E> ensure(Predicate<? super T> invariant, Function<? super T, ? extends E> onViolation) {
        return switch (this) {
            case Success<T, E>(var value) ->
                    invariant.test(value) ? this : new Failure<>(onViolation.apply(value));
            case Failure<T, E> _ -> this;
        };
    }

    /** Collapses both branches into one value. This is how adapters leave the railway. */
    default <R> R fold(Function<? super T, ? extends R> onSuccess, Function<? super E, ? extends R> onFailure) {
        return switch (this) {
            case Success<T, E>(var value) -> onSuccess.apply(value);
            case Failure<T, E>(var error) -> onFailure.apply(error);
        };
    }

    /** Runs a side effect on success without changing the railway. */
    default Result<T, E> peek(Consumer<? super T> action) {
        return switch (this) {
            case Success<T, E>(var value) -> {
                action.accept(value);
                yield this;
            }
            case Failure<T, E> _ -> this;
        };
    }

    /** Runs a side effect on failure, e.g. a metric. Does not change the railway. */
    default Result<T, E> peekError(Consumer<? super E> action) {
        return switch (this) {
            case Success<T, E> _ -> this;
            case Failure<T, E>(var error) -> {
                action.accept(error);
                yield this;
            }
        };
    }

    default T orElse(T fallback) {
        return switch (this) {
            case Success<T, E>(var value) -> value;
            case Failure<T, E> _ -> fallback;
        };
    }

    default T orElseGet(Function<? super E, ? extends T> fallback) {
        return switch (this) {
            case Success<T, E>(var value) -> value;
            case Failure<T, E>(var error) -> fallback.apply(error);
        };
    }

    default Optional<T> toOptional() {
        return switch (this) {
            case Success<T, E>(var value) -> Optional.ofNullable(value);
            case Failure<T, E> _ -> Optional.empty();
        };
    }

    default Optional<E> errorToOptional() {
        return switch (this) {
            case Success<T, E> _ -> Optional.empty();
            case Failure<T, E>(var error) -> Optional.of(error);
        };
    }

    /** Bridges a lookup that may legitimately find nothing into the railway. */
    static <T, E> Result<T, E> fromOptional(Optional<T> optional, Supplier<? extends E> onEmpty) {
        return optional.<Result<T, E>>map(Success::new)
                .orElseGet(() -> new Failure<>(onEmpty.get()));
    }
}
