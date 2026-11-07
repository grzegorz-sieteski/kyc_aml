package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.ActorRole;

import java.util.Arrays;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * An identified caller: the organisation and identity from {@link ActorContext}, and the role.
 *
 * <p>The only type that carries a role into the adapter. It never reaches {@code domain/} or
 * {@code query/}, which is how a domain role check becomes a build failure.
 * */
public final class AuthenticatedCaller {

    private final ActorContext actor;

    private AuthenticatedCaller(ActorContext actor) {
        this.actor = actor;
    }

    /**
     * Builds a caller from raw claims, or empty. The role is matched exactly by constant name over
     * {@link ActorRole#values()}: never {@code valueOf}, which throws, and never case-folded. An
     * unknown role makes the whole result empty. The tenant and actor checks are those of
     * {@link ActorContext#parse}.
     */
    public static Optional<AuthenticatedCaller> parse(@Nullable String tenantId,
                                                      @Nullable String actorId,
                                                      @Nullable String role) {
        return Arrays.stream(ActorRole.values())
                .filter(candidate -> candidate.name().equals(role))
                .findFirst()
                .flatMap(parsedRole -> ActorContext.parse(tenantId, actorId, parsedRole))
                .map(AuthenticatedCaller::new);
    }

    /** The organisation and identity. */
    public ActorContext actor() {
        return actor;
    }

    /** The role. */
    public ActorRole role() {
        return actor.role();
    }
}
