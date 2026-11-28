package com.gs5.kycaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-13. The integration tests set issuer, audience and key source themselves, so they cannot notice if the
 * shipped {@code application.yml} loses one. Without {@code audiences}, a token Entra ID issued for any
 * other application would identify a caller here. This pins the shipped file: each setting is present,
 * comes from the environment, and has no default.
 */
class ProductionIdentitySettingsTest {

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    private static Object shipped(String property) throws IOException {
        var sources = new YamlPropertySourceLoader().load("shipped", new ClassPathResource("application.yml"));
        assertThat(sources).hasSize(1);
        return sources.getFirst().getProperty(property);
    }

    @Test
    @DisplayName("issuer, audience and key set come from the environment, with no default")
    void shouldRequireIssuerAudienceAndKeysFromTheEnvironment() throws IOException {
        assertThat(shipped(JWT + "issuer-uri")).isEqualTo("${ISSUER_URI}");
        assertThat(shipped(JWT + "audiences")).isEqualTo("${TOKEN_AUDIENCE}");
        assertThat(shipped(JWT + "jwk-set-uri")).isEqualTo("${JWK_SET_URI}");
    }

    @Test
    @DisplayName("only RS256 signatures are accepted")
    void shouldAcceptRs256Only() throws IOException {
        assertThat(shipped(JWT + "jws-algorithms")).isEqualTo("RS256");
    }

    @Test
    @DisplayName("I-08: Spring Security logs at INFO: DEBUG would print request paths, which carry customer numbers")
    void shouldPinSpringSecurityLoggingToInfo() throws IOException {
        assertThat(String.valueOf(shipped("logging.level.org.springframework.security"))).isEqualTo("INFO");
    }
}
