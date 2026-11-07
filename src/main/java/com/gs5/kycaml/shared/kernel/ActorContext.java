package com.gs5.kycaml.shared.kernel;

import module java.base;

import org.jspecify.annotations.Nullable;

/**
 * The authenticated caller, propagated to the domain without a framework dependency.
 *
 * <p>The service runs on virtual threads, so {@code ThreadLocal} is the wrong tool: it pins state
 * to a carrier thread and leaks across tasks. Java 25 finalised {@link ScopedValue} (JEP 506),
 * which binds a value for the dynamic extent of one call and unbinds it automatically.
 *
 * <p><strong>Always bind through {@link #callWith} / {@link #runWith}.</strong> The static
 * {@code ScopedValue.callWhere(...)} and {@code ScopedValue.runWhere(...)} helpers that older
 * material still shows were <em>removed from the JDK in 24</em>; the final API is
 * {@code ScopedValue.where(KEY, value).call(op)}. These two methods wrap it so no caller has to
 * remember which form is current, and a Checkstyle rule fails the build on the removed one.
 *
 * @param tenantId organisation the caller belongs to; the basis of every BOLA check
 * @param actorId  stable identifier of the caller, written to audit fields
 * @param role     what the caller is allowed to do
 */
public record ActorContext(UUID tenantId, String actorId, ActorRole role) {

    public static final ScopedValue<ActorContext> CURRENT = ScopedValue.newInstance();

    public ActorContext {
    }

    /**
     * Runs {@code operation} with this actor bound, and returns its value.
     *
     * <p>This is the correct Java 25 form of what used to be written
     * {@code ScopedValue.callWhere(CURRENT, actor, op)}.
     */
    public static <R> R callWith(ActorContext actor, Supplier<R> operation) {
        return ScopedValue.where(CURRENT, actor).call(operation::get);
    }

    /** Runs {@code operation} with this actor bound. */
    public static void runWith(ActorContext actor, Runnable operation) {
        ScopedValue.where(CURRENT, actor).run(operation);
    }

    /**
     * The actor bound to the current extent, or empty outside one.
     *
     * <p>Returns an {@code Optional} rather than calling {@code CURRENT.get()}, which throws
     * when unbound. Callers stay on the railway: {@code Result.fromOptional(current(), ...)}.
     */
    public static Optional<ActorContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /**
     * Masks {@code actorId} (OPEN-01): this record is the first, upstream holder of the caller's
     * identity, so masking every downstream record was not enough. The organisation and the role
     * stay visible; neither identifies a person.
     */
    @Override
    public String toString() {
        return "ActorContext[tenantId=" + tenantId + ", actorId=***, role=" + role + "]";
    }

    /** The width of {@code registered_by VARCHAR(255)}: the largest actor id the store can hold. */
    public static final int MAX_ACTOR_ID_LENGTH = 255;

    /**
     * Builds a caller from raw claims, or returns empty when anything is missing or malformed.
     *
     * <p>Fail-closed: the result is never partial and never carries a reason, since a reason would
     * be an oracle for someone probing token validation. {@code tenantId} must be the canonical
     * 36-character UUID text; {@code actorId} must be non-blank, at most
     * {@link #MAX_ACTOR_ID_LENGTH} code points, free of category Cc, and is not trimmed.
     *
     * <p>The canonical-text check is a pattern rather than {@code UUID.fromString}, because
     * {@code fromString} accepts non-canonical spellings such as {@code 1-1-1-1-1} and would
     * let two different claim strings name the same organisation.
     */
    public static Optional<ActorContext> parse(@Nullable String tenantId,
                                               @Nullable String actorId,
                                               @Nullable ActorRole role) {
        var tenant = CanonicalUuid.parse(tenantId);
        if (tenant.isEmpty()
                || actorId == null || actorId.isBlank()
                || actorId.codePointCount(0, actorId.length()) > MAX_ACTOR_ID_LENGTH
                || ControlCharacters.firstIn(actorId).isPresent()
                || role == null) {
            return Optional.empty();
        }
        return Optional.of(new ActorContext(tenant.get(), actorId, role));
    }

    /** True when this actor may read data belonging to {@code otherTenantId}. */
    public boolean belongsToTenant(UUID otherTenantId) {
        return tenantId.equals(otherTenantId);
    }
}
