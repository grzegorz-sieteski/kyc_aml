package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
import com.gs5.kycaml.shared.kernel.ActorRole;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the claims of a validated access token to a caller (D-14 to D-16, design addendum 2026-11-09 (3)).
 *
 * <p>The claim names are configuration ({@code app.identity.*}), so the identity provider's setup can
 * change without a release. Each claim must have the expected JSON type: the actor and the booking centre
 * a string, the roles a string or an array of strings. Values that are not {@link ActorRole} names are
 * roles of other applications and are ignored; exactly one {@link ActorRole} must remain (D-15). The
 * booking centre and the actor are checked by {@link AuthenticatedCaller#parse}. Any other outcome is
 * empty, never a partial or guessed caller, and never a reason.
 */
@Component
final class CallerClaims {

    private final String actorClaim;
    private final String roleClaim;
    private final String bookingCentreClaim;

    CallerClaims(@Value("${app.identity.actor-claim:oid}") String actorClaim,
                 @Value("${app.identity.role-claim:roles}") String roleClaim,
                 @Value("${app.identity.booking-centre-claim:booking_centre}") String bookingCentreClaim) {
        this.actorClaim = actorClaim;
        this.roleClaim = roleClaim;
        this.bookingCentreClaim = bookingCentreClaim;
    }

    /** The caller the token names, or empty. */
    Optional<AuthenticatedCaller> callerOf(Jwt token) {
        Map<String, Object> claims = token.getClaims();
        return text(claims.get(actorClaim)).flatMap(actor ->
                text(claims.get(bookingCentreClaim)).flatMap(centre ->
                        onlyRole(claims.get(roleClaim)).flatMap(role ->
                                AuthenticatedCaller.parse(centre, actor, role.name()))));
    }

    private static Optional<String> text(@Nullable Object value) {
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    /** Exactly one role of this service among the values, matched by exact name; otherwise empty. */
    private static Optional<ActorRole> onlyRole(@Nullable Object value) {
        return roleValues(value).flatMap(values -> {
            var ours = Arrays.stream(ActorRole.values())
                    .filter(role -> values.contains(role.name()))
                    .toList();
            return ours.size() == 1 ? Optional.of(ours.getFirst()) : Optional.empty();
        });
    }

    /** A single string, or an array whose every element is a string; any other shape is empty. */
    private static Optional<List<String>> roleValues(@Nullable Object value) {
        return switch (value) {
            case String single -> Optional.of(List.of(single));
            case Collection<?> many when many.stream().allMatch(String.class::isInstance) ->
                    Optional.of(many.stream().map(String.class::cast).toList());
            case null, default -> Optional.empty();
        };
    }
}
