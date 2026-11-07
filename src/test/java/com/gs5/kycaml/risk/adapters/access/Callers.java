package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorRole;

import java.util.Optional;

/**
 * Builds callers through {@link AuthenticatedCaller#parse}, so every test exercises the production
 * path rather than a test-only constructor.
 */
final class Callers {

    static final String TENANT = "0a000000-0000-0000-0000-00000000000a";
    static final String ACTOR = "uw-01";

    private Callers() {
    }

    static AuthenticatedCaller of(ActorRole role) {
        return AuthenticatedCaller.parse(TENANT, ACTOR, role.name()).orElseThrow();
    }

    static Optional<AuthenticatedCaller> optionalOf(ActorRole role) {
        return Optional.of(of(role));
    }
}
