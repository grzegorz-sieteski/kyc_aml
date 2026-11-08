package com.gs5.kycaml.risk.adapters.web;

import module java.base;

import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
import com.gs5.kycaml.risk.domain.BookingCentres;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller — <strong>fail-closed</strong>.
 *
 * <p>How a caller is authenticated is an open dependency: {@code spring-boot-starter-security} is
 * not on this project's classpath, and no identity source has been chosen. The product owner
 * confirmed the behaviour in that situation: <strong>no identity source configured means no
 * actor</strong>. This resolver returns {@link Optional#empty()} and never invents, defaults or
 * infers an actor, a tenant or a role. The request is then refused as {@code CallerNotIdentified}
 * ({@code 401}) by the access gate, which is the correct answer under BR-01 and the only safe one: a
 * resolver that manufactures an actor when it cannot identify one turns BR-01 into decoration and
 * makes every tenant boundary in BR-17 meaningless.
 *
 * <p><strong>Reading the identity off request headers would not be authentication.</strong> A
 * resolver that trusted {@code X-Tenant-Id} / {@code X-Actor-Id} / {@code X-Actor-Role} would let
 * any caller become any actor in any organisation: total cross-tenant compromise of BR-17 and
 * total bypass of BR-02. There is deliberately <strong>no such resolver in this repository at
 * all</strong> — not even a profile-gated one, because a bean that exists can be activated. The
 * tests do not need one: they inject the actor directly through
 * a {@code @Primary} subclass of this resolver that says who is calling.
 *
 * <p>This is also deliberately <strong>not</strong> a servlet filter wrapping
 * {@code chain.doFilter} in {@code ActorContext.runWith}: {@code doFilter} declares
 * {@code IOException} and {@code ServletException}, {@code runWith} takes a {@code Runnable}, and
 * bridging the two means catching and re-raising a checked exception in {@code src/main} — awkward
 * at best, and a trap next to the Checkstyle zero-throw gate. Binding the scope around the useCase
 * call only means no checked exception ever crosses the scope.
 */
@Component
class ActorContextResolver {

    /** The caller, when an identity source can name one. Empty is the configured answer today. */
    Optional<AuthenticatedCaller> resolve() {
        return Optional.empty();
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
