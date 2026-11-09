package com.gs5.kycaml.risk.adapters.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-13 to D-16, design addendum 2026-11-09 (3): the real identity source, end to end. No resolver is
 * replaced here: the token is checked by the resource server against a JWK set served locally, and the
 * claims are mapped by the production resolver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.outbox.relay.poll-interval=PT1H"})
@Testcontainers
class IdentityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final TestTokens KEYS = TestTokens.generate("kid-1");
    private static final TestTokens STRANGER = TestTokens.generate("kid-1");
    private static final AtomicInteger NUMBERS = new AtomicInteger();
    private static final String CUSTOMER = """
            {"profileClass":"INDIVIDUAL_EMPLOYED","residence":{"country":"DE","riskClass":"LOW"},
             "citizenships":[{"country":"DE","riskClass":"LOW"}],"birth":{"country":"DE","riskClass":"LOW"},
             "loginCountry":"DE","channel":"BRANCH","highRiskProductRequest":false,"adverseMediaClass":"NONE",
             "sanctionsMatch":false,"terroristLink":false,"forgedIdentity":false}
            """;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        var jwks = KEYS.serveJwks();
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> jwks);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestTokens.AUDIENCE);
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String token, String contentType, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> view(String token) throws Exception {
        return send("GET", "/api/risk-profiles/C-ID-NOBODY", token, null, null);
    }

    private static void assertNotIdentified(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"type\":\"urn:problem-type:risk:caller-not-identified\"");
        assertThat(response.headers().allValues("Cache-Control")).anySatisfy(v -> assertThat(v).contains("no-store"));
    }

    // ------------------------------------------------------------------ accepted

    @Test
    @DisplayName("a valid token of the onboarding system evaluates (200)")
    void shouldAdmitAValidOnboardingToken() throws Exception {
        var token = KEYS.sign(TestTokens.claims("ONBOARDING_SYSTEM"));

        var response = send("POST", "/api/risk-profiles/C-ID-" + NUMBERS.incrementAndGet() + "/evaluations", token,
                "application/json", CUSTOMER);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("a valid L2 token is identified: an unknown customer is 404, not 401")
    void shouldIdentifyAValidAnalystToken() throws Exception {
        var response = view(KEYS.sign(TestTokens.claims("AML_ANALYST_L2")));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    }

    @Test
    @DisplayName("a role claim that is a single string is accepted as one role")
    void shouldAcceptASingleStringRole() throws Exception {
        var claims = TestTokens.claims("MLRO");
        claims.put("roles", "MLRO");

        assertThat(view(KEYS.sign(claims)).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("roles of other applications are ignored as long as exactly one role of this service remains")
    void shouldIgnoreForeignRoles() throws Exception {
        var claims = TestTokens.claims("MLRO");
        claims.put("roles", List.of("Directory.Read", "MLRO", "SomeOtherApp.Admin"));

        assertThat(view(KEYS.sign(claims)).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a role the token grants decides what is allowed: front office may not view risk (403)")
    void shouldApplyTheRoleFromTheToken() throws Exception {
        assertThat(view(KEYS.sign(TestTokens.claims("FRONT_OFFICE"))).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ refused: no token, bad token

    @Test
    @DisplayName("no token: 401 caller-not-identified")
    void shouldRefuseNoToken() throws Exception {
        assertNotIdentified(view(null));
    }

    @Test
    @DisplayName("a token that is not a JWT: the same constant 401, no detail from the token parser")
    void shouldRefuseGarbage() throws Exception {
        var response = view("not-a-jwt");

        assertNotIdentified(response);
        assertThat(response.body()).doesNotContain("Malformed").doesNotContain("Jwt").doesNotContain("invalid_token");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer")
                .doesNotContain("error_description");
    }

    static Stream<Arguments> badTokens() {
        Function<Function<Map<String, Object>, Map<String, Object>>, String> signed =
                change -> KEYS.sign(change.apply(TestTokens.claims("AML_ANALYST_L2")));
        var now = Instant.now().getEpochSecond();
        return Stream.of(
                Arguments.of("expired", signed.apply(c -> with(c, "exp", now - 120))),
                Arguments.of("not yet valid", signed.apply(c -> with(c, "nbf", now + 600))),
                Arguments.of("wrong issuer", signed.apply(c -> with(c, "iss", "https://login.microsoftonline.com/other/v2.0"))),
                Arguments.of("wrong audience", signed.apply(c -> with(c, "aud", "api://someone-else"))),
                Arguments.of("signed by another key with the same kid", STRANGER.sign(TestTokens.claims("AML_ANALYST_L2"))),
                Arguments.of("alg none", TestTokens.unsigned(TestTokens.claims("AML_ANALYST_L2"))),
                Arguments.of("two roles of this service (D-15)", signed.apply(c -> with(c, "roles", List.of("AML_ANALYST_L1", "MLRO")))),
                Arguments.of("no role claim", signed.apply(c -> without(c, "roles"))),
                Arguments.of("no role of this service", signed.apply(c -> with(c, "roles", List.of("Directory.Read")))),
                Arguments.of("role not matching exactly", signed.apply(c -> with(c, "roles", List.of("aml_analyst_l2")))),
                Arguments.of("role claim of the wrong type", signed.apply(c -> with(c, "roles", 7))),
                Arguments.of("no booking centre", signed.apply(c -> without(c, "booking_centre"))),
                Arguments.of("booking centre not a UUID", signed.apply(c -> with(c, "booking_centre", "DE"))),
                Arguments.of("booking centre of the wrong type", signed.apply(c -> with(c, "booking_centre", List.of(TestTokens.DE_CENTRE)))),
                Arguments.of("booking centre not in scope (D-16)", signed.apply(c -> with(c, "booking_centre", TestTokens.OUT_OF_SCOPE_CENTRE))),
                Arguments.of("no actor", signed.apply(c -> without(c, "oid"))),
                Arguments.of("blank actor", signed.apply(c -> with(c, "oid", "  "))),
                Arguments.of("actor of the wrong type", signed.apply(c -> with(c, "oid", 42))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badTokens")
    @DisplayName("an invalid token or claims that do not name exactly one identity, role and centre: 401")
    void shouldRefuseABadToken(String attack, String token) throws Exception {
        assertNotIdentified(view(token));
    }

    @Test
    @DisplayName("a token in the query string is not read")
    void shouldIgnoreATokenInTheQuery() throws Exception {
        var token = KEYS.sign(TestTokens.claims("AML_ANALYST_L2"));

        assertNotIdentified(send("GET", "/api/risk-profiles/C-ID-NOBODY?access_token=" + token, null, null, null));
    }

    @Test
    @DisplayName("a token in a form body is not read: the body stays unread before identification")
    void shouldIgnoreATokenInAFormBody() throws Exception {
        var token = KEYS.sign(TestTokens.claims("ONBOARDING_SYSTEM"));

        assertNotIdentified(send("POST", "/api/risk-profiles/C-ID-FORM/evaluations", null,
                "application/x-www-form-urlencoded", "access_token=" + token));
    }

    @Test
    @DisplayName("a POST with a valid token needs no CSRF token: the API is stateless")
    void shouldNotRequireCsrf() throws Exception {
        var token = KEYS.sign(TestTokens.claims("ONBOARDING_SYSTEM"));

        var response = send("POST", "/api/risk-profiles/C-ID-" + NUMBERS.incrementAndGet() + "/evaluations", token,
                "application/json", CUSTOMER);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).as("no session").isEmpty();
    }

    private static Map<String, Object> with(Map<String, Object> claims, String name, Object value) {
        var copy = new HashMap<>(claims);
        copy.put(name, value);
        return copy;
    }

    private static Map<String, Object> without(Map<String, Object> claims, String name) {
        var copy = new HashMap<>(claims);
        copy.remove(name);
        return copy;
    }
}
