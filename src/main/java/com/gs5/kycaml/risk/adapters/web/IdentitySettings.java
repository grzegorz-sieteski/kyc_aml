package com.gs5.kycaml.risk.adapters.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The three identity settings without which a token check is silently absent (I-01), read from the same
 * prefix as Spring Boot's own resource-server properties. Spring Boot adds the issuer and the audience
 * validator only for a non-empty value, and falls back to issuer discovery when the JWK set URI is empty;
 * so each of them must be present and not blank, or the service does not start. The failure names the
 * setting.
 *
 * <p>A missing property arrives as {@code null} and is kept as an empty value, which the constraints
 * refuse just the same; {@link ConfiguredIdentityValidator} then has no null to consider.
 */
@Validated
@ConfigurationProperties(prefix = IdentitySettings.PREFIX)
record IdentitySettings(
        @NotBlank(message = IdentitySettings.PREFIX + ".issuer-uri (ISSUER_URI) must be set and not blank")
        String issuerUri,
        @NotEmpty(message = IdentitySettings.PREFIX + ".audiences (TOKEN_AUDIENCE) must name at least one audience")
        List<@NotBlank(message = IdentitySettings.PREFIX + ".audiences (TOKEN_AUDIENCE) must not hold a blank audience")
                String> audiences,
        @NotBlank(message = IdentitySettings.PREFIX + ".jwk-set-uri (JWK_SET_URI) must be set and not blank")
        String jwkSetUri) {

    static final String PREFIX = "spring.security.oauth2.resourceserver.jwt";

    IdentitySettings {
        issuerUri = issuerUri == null ? "" : issuerUri;
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
        jwkSetUri = jwkSetUri == null ? "" : jwkSetUri;
    }
}
