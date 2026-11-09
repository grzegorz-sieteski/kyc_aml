package com.gs5.kycaml.risk.adapters.web;

import module java.base;

import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
import com.gs5.kycaml.risk.domain.BookingCentres;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller — <strong>fail-closed</strong>.
 *
 * <p>The identity source is the bearer token (D-13, design addendum 2026-11-09 (3)): the resource server
 * ({@link BearerTokenSecurityConfiguration}) validates it and puts it in the security context, and
 * {@link CallerClaims} maps its claims to a caller (D-14 to D-16). Without a validated token there is
 * no caller: this resolver returns {@link Optional#empty()} and never invents, defaults or infers an
 * actor, a tenant or a role, and the request is refused as {@code CallerNotIdentified} ({@code 401}) by
 * the access gate (BR-01).
 *
 * <p><strong>Reading the identity off request headers would not be authentication.</strong> A
 * resolver that trusted {@code X-Tenant-Id} / {@code X-Actor-Id} / {@code X-Actor-Role} would let
 * any caller become any actor in any organisation. There is deliberately no such resolver in this
 * repository, not even a profile-gated one. The tests that need a caller without a token inject it
 * through a {@code @Primary} subclass of this resolver that overrides {@link #resolve()}; such a
 * subclass uses the no-argument constructor, which on its own identifies nobody.
 *
 * <p>This is deliberately <strong>not</strong> a servlet filter wrapping
 * {@code chain.doFilter} in {@code ActorContext.runWith}: {@code doFilter} declares
 * {@code IOException} and {@code ServletException}, {@code runWith} takes a {@code Runnable}, and
 * bridging the two means catching and re-raising a checked exception in {@code src/main}. Binding the
 * scope around the use case call only means no checked exception ever crosses the scope.
 */
@Component
class ActorContextResolver {

    private final @Nullable CallerClaims claims;

    /** No identity source: {@link #resolve()} is empty unless a subclass overrides it. */
    ActorContextResolver() {
        this.claims = null;
    }

    /** The production resolver: the caller is the one the validated bearer token names. */
    @Autowired
    ActorContextResolver(CallerClaims claims) {
        this.claims = claims;
    }

    /** The caller the validated bearer token of this request names, or empty. */
    Optional<AuthenticatedCaller> resolve() {
        return Optional.ofNullable(claims).flatMap(mapping -> validatedToken().flatMap(mapping::callerOf));
    }

    /** The token the resource server validated for this request; nothing else in the context counts. */
    private static Optional<Jwt> validatedToken() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && token.isAuthenticated()
                ? Optional.of(token.getToken())
                : Optional.empty();
    }

    /**
     * The identified caller, the only answer the resources use (design addendum A step 1): the
     * identity source's caller, kept only when its booking centre has a jurisdiction configured. A
     * caller of any other booking centre is not identified (BR-01, R-05), on every route and before
     * any input is looked at. Final, so no identity source can skip the booking-centre check.
     */
    final Optional<AuthenticatedCaller> identify(BookingCentres bookingCentres) {
        return resolve().filter(caller -> bookingCentres.jurisdictionOf(caller.actor().tenantId()).isPresent());
    }
}
