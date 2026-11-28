package com.gs5.kycaml.risk.adapters.web;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Refuses every token unless the issuer is configured and is the token's, the configured audiences are
 * not empty and one of them is among the token's, and the token expires (I-01, I-02).
 *
 * <p>Spring Boot's own validators check the issuer and the audience only when the setting is non-empty,
 * and {@code JwtTimestampValidator} accepts a token with no {@code exp}. {@link IdentitySettings} already
 * stops startup on an empty setting; this is the second guard, in the decoder itself, so that a token is
 * never accepted because a check was absent. Spring Boot adds every {@code OAuth2TokenValidator<Jwt>} bean
 * to the decoder it builds. The reason never leaves the service ({@link CallerNotIdentifiedAnswer}).
 */
final class ConfiguredIdentityValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REFUSED = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "The token is not from the configured issuer, not for a configured audience, or does not expire.",
            null);

    private final String issuer;
    private final List<String> audiences;

    ConfiguredIdentityValidator(IdentitySettings settings) {
        this.issuer = settings.issuerUri();
        this.audiences = settings.audiences();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return fromTheIssuer(token) && forAnAudience(token) && token.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(REFUSED);
    }

    private boolean fromTheIssuer(Jwt token) {
        return StringUtils.hasText(issuer) && issuer.equals(token.getClaimAsString(JwtClaimNames.ISS));
    }

    private boolean forAnAudience(Jwt token) {
        var named = Optional.ofNullable(token.getAudience()).orElse(List.of());
        return !audiences.isEmpty() && !Collections.disjoint(audiences, named);
    }
}
