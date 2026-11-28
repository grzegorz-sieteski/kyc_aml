package com.gs5.kycaml.risk.adapters.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-14: the claim names are configuration. With Entra-style names configured (an extension attribute for
 * the booking centre, {@code sub} for the actor, {@code app_roles} for roles), a token carrying those names
 * is identified, and one carrying the default names is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.outbox.relay.poll-interval=PT1H",
        "app.identity.actor-claim=sub",
        "app.identity.role-claim=app_roles",
        "app.identity.booking-centre-claim=extn.bookingCentre"})
@Testcontainers
class IdentityClaimNamesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final TestTokens KEYS = TestTokens.generate("kid-names");

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        var jwks = KEYS.serveJwks();
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> jwks);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestTokens.AUDIENCE);
    }

    @LocalServerPort
    private int port;

    private int statusFor(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/risk-profiles/C-NAMES"))
                .header("Authorization", "Bearer " + token).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("the configured claim names identify the caller")
    void shouldReadTheConfiguredClaimNames() throws Exception {
        var claims = TestTokens.claims("AML_ANALYST_L2");
        claims.remove("oid");
        claims.remove("roles");
        claims.remove("booking_centre");
        claims.put("sub", "actor-sub-1");
        claims.put("app_roles", List.of("AML_ANALYST_L2"));
        claims.put("extn.bookingCentre", TestTokens.DE_CENTRE);

        assertThat(statusFor(KEYS.sign(claims))).isEqualTo(404);
    }

    @Test
    @DisplayName("the default claim names are not read when others are configured")
    void shouldNotFallBackToTheDefaultNames() throws Exception {
        assertThat(statusFor(KEYS.sign(TestTokens.claims("AML_ANALYST_L2")))).isEqualTo(401);
    }

    @Test
    @DisplayName("I-09: a claim name is a literal key, not a path: a nested object under 'extn' identifies nobody")
    void shouldNotFollowADottedNameIntoANestedObject() throws Exception {
        var claims = TestTokens.claims("AML_ANALYST_L2");
        claims.remove("oid");
        claims.remove("roles");
        claims.remove("booking_centre");
        claims.put("sub", "actor-sub-1");
        claims.put("app_roles", List.of("AML_ANALYST_L2"));
        claims.put("extn", java.util.Map.of("bookingCentre", TestTokens.DE_CENTRE));

        assertThat(statusFor(KEYS.sign(claims))).isEqualTo(401);
    }
}
