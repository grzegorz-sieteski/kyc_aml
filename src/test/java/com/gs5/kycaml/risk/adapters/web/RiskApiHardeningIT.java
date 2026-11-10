package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
import com.gs5.kycaml.risk.adapters.events.OutboxRelay;
import com.gs5.kycaml.shared.kernel.ActorRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3 after the stage 6 reviews: the contract of the hardened edge, over real HTTP on Tomcat and
 * PostgreSQL. Decisions D-08 to D-12 and the design addendum of 2026-11-08 (sections A to E).
 *
 * <p>Every test names the R-xx it pins. Problem types are the contract: {@code request-too-large} (413),
 * {@code request-not-readable}, {@code too-many-citizenships}, {@code version-viewed-not-valid} (400),
 * {@code request-not-handled} for anything outside the four resources.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.outbox.relay.poll-interval=PT1H"})
@Import(RiskApiHardeningIT.Identity.class)
@Testcontainers
class RiskApiHardeningIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String DE_CENTRE = "0de00000-0000-0000-0000-00000000000d";
    private static final String REASON = "Analyst reviewed the alert and the documents";
    private static final String PROBLEM = "urn:problem-type:risk:";
    private static final int EVALUATION_LIMIT = 64 * 1024;
    private static final int DECISION_LIMIT = 16 * 1024;
    private static final String DE_LOW = "{\"country\":\"DE\",\"riskClass\":\"LOW\"}";

    private static final AtomicInteger NUMBERS = new AtomicInteger();
    private static volatile Optional<AuthenticatedCaller> caller = Optional.empty();

    @TestConfiguration
    static class Identity {
        @Bean
        @Primary
        ActorContextResolver testResolver() {
            return new ActorContextResolver() {
                @Override
                Optional<AuthenticatedCaller> resolve() {
                    return caller;
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Answer(int status, String body, HttpResponse<String> response) {
    }

    @BeforeEach
    void nobodyIsIdentified() {
        caller = Optional.empty();
    }

    private static void callAs(ActorRole role) {
        caller = AuthenticatedCaller.parse(DE_CENTRE, "actor-" + role.name().toLowerCase(), role.name());
    }

    private static String newNumber() {
        return "C-HD-" + NUMBERS.incrementAndGet();
    }

    /** The fields of a valid evaluation, without the surrounding braces, so tests can add and replace. */
    private static String customerFields(String citizenships) {
        return "\"profileClass\":\"POLITICALLY_EXPOSED\",\"residence\":" + DE_LOW + ",\"citizenships\":"
                + citizenships + ",\"birth\":" + DE_LOW + ",\"loginCountry\":\"AE\",\"channel\":\"REMOTE\","
                + "\"highRiskProductRequest\":true,\"adverseMediaClass\":\"UNCONFIRMED_FINANCIAL_CRIME\","
                + "\"sanctionsMatch\":false,\"terroristLink\":false,\"forgedIdentity\":false";
    }

    private static String customer() {
        return "{" + customerFields("[" + DE_LOW + "]") + "}";
    }

    private static String decision(String kind, String versionViewed) {
        return "{\"kind\":\"" + kind + "\",\"justification\":\"" + REASON + "\",\"versionViewed\":" + versionViewed + "}";
    }

    /** A valid body padded with an ignored field to exactly {@code bytes} bytes of UTF-8. */
    private static String paddedTo(String validObject, int bytes) {
        var empty = "{\"pad\":\"\"," + validObject.substring(1);
        var body = "{\"pad\":\"" + "a".repeat(bytes - empty.getBytes(StandardCharsets.UTF_8).length) + "\","
                + validObject.substring(1);
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
        return body;
    }

    private Answer send(String method, String path, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").method(method, body).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body(), response);
    }

    private Answer post(String path, String body) throws Exception {
        return send("POST", path, HttpRequest.BodyPublishers.ofString(body));
    }

    private Answer evaluate(String number, String body) throws Exception {
        return post("/api/risk-profiles/" + number + "/evaluations", body);
    }

    private Answer decide(String number, String body) throws Exception {
        return post("/api/risk-profiles/" + number + "/decisions", body);
    }

    /** A customer evaluated once (version 1); the caller is left as an L2 analyst. */
    private String evaluatedCustomer() throws Exception {
        var number = newNumber();
        callAs(ActorRole.ONBOARDING_SYSTEM);
        assertThat(evaluate(number, customer()).status()).isEqualTo(200);
        callAs(ActorRole.AML_ANALYST_L2);
        return number;
    }

    private long versionOf(String number) {
        return jdbc.sql("SELECT version FROM command.risk_profile WHERE tenant_id = CAST(:tenant AS UUID)"
                        + " AND customer_number = :number")
                .param("tenant", DE_CENTRE).param("number", number)
                .query(Long.class).optional().orElse(0L);
    }

    private static void assertProblem(Answer answer, int status, String code) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(status);
        assertThat(answer.response().headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(answer.body()).contains("\"type\":\"" + PROBLEM + code + "\"");
    }

    // ============================================================================ R-01, R-02

    @Nested
    @DisplayName("R-01, R-02: the caller is identified before the body is read, and the body is bounded (D-09)")
    class Bounded {

        @Test
        @DisplayName("an evaluation of exactly 64 KiB is read; one byte more is request-too-large (413)")
        void shouldHoldTheEvaluationLimitToTheByte() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            assertThat(evaluate(newNumber(), paddedTo(customer(), EVALUATION_LIMIT)).status()).isEqualTo(200);
            var number = newNumber();
            assertProblem(evaluate(number, paddedTo(customer(), EVALUATION_LIMIT + 1)), 413, "request-too-large");
            assertThat(versionOf(number)).isZero();
        }

        @Test
        @DisplayName("a decision of exactly 16 KiB is read; one byte more is request-too-large (413)")
        void shouldHoldTheDecisionLimitToTheByte() throws Exception {
            var number = evaluatedCustomer();
            var other = evaluatedCustomer();

            assertThat(decide(number, paddedTo(decision("FREEZE_ASSETS", "1"), DECISION_LIMIT)).status())
                    .isEqualTo(200);
            assertProblem(decide(other, paddedTo(decision("FREEZE_ASSETS", "1"), DECISION_LIMIT + 1)), 413,
                    "request-too-large");
            assertThat(versionOf(other)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a chunked body with no declared length is cut off at the limit too")
        void shouldBoundABodyWithoutContentLength() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var bytes = paddedTo(customer(), EVALUATION_LIMIT * 4).getBytes(StandardCharsets.UTF_8);

            var answer = send("POST", "/api/risk-profiles/" + newNumber() + "/evaluations",
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));

            assertProblem(answer, 413, "request-too-large");
        }

        @Test
        @DisplayName("nobody identified: a 1 MiB body on either route is a 401, never a 413 or a 400")
        void shouldRefuseTheUnidentifiedBeforeLookingAtTheBody() throws Exception {
            var huge = paddedTo(customer(), 1024 * 1024);

            assertProblem(evaluate(newNumber(), huge), 401, "caller-not-identified");
            assertProblem(decide(newNumber(), huge), 401, "caller-not-identified");
            assertProblem(decide(newNumber(), "{\"kind\":"), 401, "caller-not-identified");
        }

        @Test
        @DisplayName("evaluate: a role that may not evaluate gets 403 for an oversized body, the role is first")
        void shouldJudgeTheEvaluateRoleBeforeTheSize() throws Exception {
            callAs(ActorRole.AML_ANALYST_L1);

            assertProblem(evaluate(newNumber(), paddedTo(customer(), EVALUATION_LIMIT + 1)), 403, "permission-denied");
        }

        @Test
        @DisplayName("R-02: a mistyped field does not change the permission: an L1 escalation with a bad version is 400, not 403")
        void shouldReadTheKindOnItsOwn() throws Exception {
            var number = evaluatedCustomer();
            callAs(ActorRole.AML_ANALYST_L1);

            assertProblem(decide(number, decision("ESCALATE_EDD", "\"abc\"")), 400, "version-viewed-not-valid");
        }

        @Test
        @DisplayName("D-08: an unrecognised kind is a 403 for a role that may not freeze, a 400 for one that may")
        void shouldJudgeAnUnknownKindAsFreezing() throws Exception {
            var number = evaluatedCustomer();

            callAs(ActorRole.AML_ANALYST_L1);
            assertProblem(decide(number, decision("FREEZE", "1")), 403, "permission-denied");
            callAs(ActorRole.MLRO);
            assertProblem(decide(number, decision("FREEZE", "1")), 400, "decision-kind-not-recognised");
        }
    }

    // ============================================================================ R-06, R-07

    @Nested
    @DisplayName("R-06, R-07: input is read strictly, and a wrong field is refused as that field (D-12)")
    class Strict {

        @ParameterizedTest(name = "body {0}")
        @ValueSource(strings = {"", " ", "[]", "null", "42", "\"text\"", "not json", "{\"profileClass\":",
                "{} {}", "{\"profileClass\":\"INDIVIDUAL_EMPLOYED\"} trailing"})
        @DisplayName("an evaluation body that is not exactly one JSON object is request-not-readable")
        void shouldRefuseAnEvaluationBodyThatIsNotOneObject(String body) throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            assertProblem(evaluate(newNumber(), body), 400, "request-not-readable");
        }

        @ParameterizedTest(name = "body {0}")
        @ValueSource(strings = {"", "[]", "null", "not json", "{\"kind\":\"FREEZE_ASSETS\"} {}"})
        @DisplayName("a decision body that is not exactly one JSON object is request-not-readable")
        void shouldRefuseADecisionBodyThatIsNotOneObject(String body) throws Exception {
            var number = evaluatedCustomer();

            assertProblem(decide(number, body), 400, "request-not-readable");
        }

        @Test
        @DisplayName("no body at all is request-not-readable on both routes, once the caller is admitted")
        void shouldRefuseAnAbsentBody() throws Exception {
            var number = evaluatedCustomer();
            assertProblem(send("POST", "/api/risk-profiles/" + number + "/decisions",
                    HttpRequest.BodyPublishers.noBody()), 400, "request-not-readable");

            callAs(ActorRole.ONBOARDING_SYSTEM);
            assertProblem(send("POST", "/api/risk-profiles/" + newNumber() + "/evaluations",
                    HttpRequest.BodyPublishers.noBody()), 400, "request-not-readable");
        }

        @Test
        @DisplayName("a field named twice is request-not-readable: sanctions true then false is not read as false")
        void shouldRefuseADuplicateKey() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var number = newNumber();
            var twice = "{" + customerFields("[" + DE_LOW + "]").replace("\"sanctionsMatch\":false",
                    "\"sanctionsMatch\":true,\"sanctionsMatch\":false") + "}";

            assertProblem(evaluate(number, twice), 400, "request-not-readable");
            assertThat(versionOf(number)).isZero();

            var decided = evaluatedCustomer();
            assertProblem(decide(decided, "{\"kind\":\"ESCALATE_EDD\",\"kind\":\"FREEZE_ASSETS\",\"justification\":\""
                    + REASON + "\",\"versionViewed\":1}"), 400, "request-not-readable");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"\"sanctionsMatch\":0", "\"sanctionsMatch\":\"false\"", "\"terroristLink\":1",
                "\"forgedIdentity\":null", "\"forgedIdentity\":[]"})
        @DisplayName("a critical fact that is not true or false is refused as critical-fact-missing")
        void shouldRefuseANonBooleanCriticalFactAsThatFact(String field) throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var name = field.substring(0, field.indexOf(':'));
            var body = "{" + customerFields("[" + DE_LOW + "]").replaceFirst(
                    java.util.regex.Pattern.quote(name) + ":false", java.util.regex.Matcher.quoteReplacement(field)) + "}";
            assertThat(body).contains(field);

            assertProblem(evaluate(newNumber(), body), 400, "critical-fact-missing");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"\"true\"", "1", "0", "\"\""})
        @DisplayName("a product request that is not true or false is refused as product-request-missing")
        void shouldRefuseANonBooleanProductRequestAsThatField(String value) throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var body = "{" + customerFields("[" + DE_LOW + "]").replace("\"highRiskProductRequest\":true",
                    "\"highRiskProductRequest\":" + value) + "}";

            assertProblem(evaluate(newNumber(), body), 400, "product-request-missing");
        }

        @Test
        @DisplayName("a profile class that is not text is refused as profile-class-not-recognised")
        void shouldRefuseANonTextProfileClassAsThatField() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var body = "{" + customerFields("[" + DE_LOW + "]").replace("\"POLITICALLY_EXPOSED\"", "7") + "}";

            assertProblem(evaluate(newNumber(), body), 400, "profile-class-not-recognised");
        }

        @ParameterizedTest(name = "versionViewed {0}")
        @ValueSource(strings = {"1.9", "1.0", "\"1\"", "0", "-1", "true", "null", "9223372036854775808"})
        @DisplayName("a version viewed that is not a whole number of at least 1 is version-viewed-not-valid")
        void shouldRefuseAVersionViewedThatIsNotValid(String versionViewed) throws Exception {
            var number = evaluatedCustomer();

            assertProblem(decide(number, decision("FREEZE_ASSETS", versionViewed)), 400, "version-viewed-not-valid");
            assertThat(versionOf(number)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a missing version viewed is version-viewed-not-valid, not 'changed since viewed'")
        void shouldRefuseAMissingVersionViewed() throws Exception {
            var number = evaluatedCustomer();

            assertProblem(decide(number, "{\"kind\":\"FREEZE_ASSETS\",\"justification\":\"" + REASON + "\"}"), 400,
                    "version-viewed-not-valid");
        }

        @Test
        @DisplayName("BR-37 order: the justification is judged before the version viewed, the customer before both")
        void shouldJudgeTheVersionViewedInItsPlace() throws Exception {
            var number = evaluatedCustomer();

            assertProblem(decide(number, "{\"kind\":\"FREEZE_ASSETS\",\"justification\":\"too short\"}"), 400,
                    "justification-too-short");
            assertProblem(decide("C-HD-NOBODY", decision("FREEZE_ASSETS", "1.5")), 404, "customer-not-found");
        }
    }

    // ============================================================================ R-10, R-03

    @Nested
    @DisplayName("R-10, R-03: limits and identity of the input")
    class Input {

        @Test
        @DisplayName("10 citizenships are accepted, 11 are too-many-citizenships (D-10)")
        void shouldAcceptTenCitizenshipsAndRefuseEleven() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var ten = "[" + String.join(",", java.util.Collections.nCopies(10, DE_LOW)) + "]";
            var eleven = "[" + String.join(",", java.util.Collections.nCopies(11, DE_LOW)) + "]";

            assertThat(evaluate(newNumber(), "{" + customerFields(ten) + "}").status()).isEqualTo(200);
            var number = newNumber();
            assertProblem(evaluate(number, "{" + customerFields(eleven) + "}"), 400, "too-many-citizenships");
            assertThat(versionOf(number)).isZero();
        }

        @Test
        @DisplayName("a literal ';' is part of the customer number: 'C;x' is its own customer (BR-06)")
        void shouldKeepASemicolonInTheCustomerNumber() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var number = newNumber();

            var answer = evaluate(number + ";x", customer());

            assertThat(answer.status()).as(answer.body()).isEqualTo(200);
            assertThat(answer.body()).contains("\"customerNumber\":\"" + number + ";x\"");
            assertThat(versionOf(number + ";x")).isEqualTo(1L);
            assertThat(versionOf(number)).isZero();
        }
    }

    // ============================================================================ R-08, R-18

    @Nested
    @DisplayName("R-08, R-18: what every answer looks like")
    class Answers {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"DELETE /api/risk-profiles/C-HD-SECRET", "GET /api/risk-profiles/C-HD-SECRET/unknown",
                "GET /api/risk-profiles/C-HD-SECRET/evaluations", "GET /nothing-here"})
        @DisplayName("an answer from outside the four resources is a constant problem, request-not-handled")
        void shouldAnswerContainerErrorsWithAConstantProblem(String request) throws Exception {
            callAs(ActorRole.AML_ANALYST_L2);
            var parts = request.split(" ");

            var answer = send(parts[0], parts[1], HttpRequest.BodyPublishers.noBody());

            assertThat(answer.status()).isBetween(400, 499);
            assertProblem(answer, answer.status(), "request-not-handled");
            assertThat(answer.body()).doesNotContain("C-HD-SECRET").doesNotContain("\"path\"")
                    .doesNotContain("timestamp");
        }

        @Test
        @DisplayName("evaluation, compliance view and front-office status are answered with Cache-Control: no-store")
        void shouldForbidCachingOfEveryAnswer() throws Exception {
            var number = evaluatedCustomer();
            relay.runOnce();

            callAs(ActorRole.ONBOARDING_SYSTEM);
            var evaluated = evaluate(number, customer());
            callAs(ActorRole.AML_ANALYST_L2);
            var viewed = send("GET", "/api/risk-profiles/" + number, HttpRequest.BodyPublishers.noBody());
            var refused = send("GET", "/api/risk-profiles/C-HD-NOBODY", HttpRequest.BodyPublishers.noBody());
            callAs(ActorRole.FRONT_OFFICE);
            var status = send("GET", "/api/risk-profiles/" + number + "/front-office-status",
                    HttpRequest.BodyPublishers.noBody());

            assertThat(viewed.status()).as(viewed.body()).isEqualTo(200);
            assertThat(status.status()).isEqualTo(200);
            for (var answer : java.util.List.of(evaluated, viewed, refused, status)) {
                assertThat(answer.response().headers().allValues("Cache-Control")).as(answer.body())
                        .anySatisfy(value -> assertThat(value).contains("no-store"));
            }
        }
    }

    // ============================================================================ R-21, R-22, R-26

    @Nested
    @DisplayName("R-21, R-22: nothing reads a body before the caller is identified; R-26: the raw number on every route")
    class BeforeIdentification {

        @Test
        @DisplayName("R-21: no multipart resolver exists, so DispatcherServlet parses no part before a handler runs")
        void shouldHaveNoMultipartResolver() {
            assertThat(context.getBeansOfType(org.springframework.web.multipart.MultipartResolver.class)).isEmpty();
        }

        @Test
        @DisplayName("R-22: no form content filter exists, so no PUT/PATCH/DELETE form body is read before dispatch")
        void shouldHaveNoFormContentFilter() {
            assertThat(context.getBeansOfType(org.springframework.web.filter.FormContentFilter.class)).isEmpty();
        }

        @Test
        @DisplayName("R-21: nobody identified: a multipart POST with a 1.5 MB part is a 401, not a 413 from parsing it (under the 2 MB swallow limit, so the answer is never cut off)")
        void shouldRefuseAnUnidentifiedMultipartPostBeforeParsingIt() throws Exception {
            var body = "--x\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a\"\r\n\r\n"
                    + "a".repeat(1536 * 1024) + "\r\n--x--\r\n";
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/risk-profiles/" + newNumber() + "/evaluations"))
                    .header("Content-Type", "multipart/form-data; boundary=x")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertProblem(new Answer(response.statusCode(), response.body(), response), 401, "caller-not-identified");
        }

        @Test
        @DisplayName("R-26: 'C;x' is not 'C' on the compliance view, the front-office status and a decision")
        void shouldKeepASemicolonOnEveryRoute() throws Exception {
            var number = evaluatedCustomer();
            relay.runOnce();
            var other = number + ";x";

            assertThat(send("GET", "/api/risk-profiles/" + number, HttpRequest.BodyPublishers.noBody()).status())
                    .isEqualTo(200);
            assertProblem(send("GET", "/api/risk-profiles/" + other, HttpRequest.BodyPublishers.noBody()), 404,
                    "customer-not-found");
            assertProblem(decide(other, decision("FREEZE_ASSETS", "1")), 404, "customer-not-found");
            assertThat(versionOf(number)).isEqualTo(1L);

            callAs(ActorRole.FRONT_OFFICE);
            assertThat(send("GET", "/api/risk-profiles/" + number + "/front-office-status",
                    HttpRequest.BodyPublishers.noBody()).status()).isEqualTo(200);
            assertProblem(send("GET", "/api/risk-profiles/" + other + "/front-office-status",
                    HttpRequest.BodyPublishers.noBody()), 404, "customer-not-found");
        }
    }

    // ============================================================================ I-03

    @Nested
    @DisplayName("I-03: the HTTP firewall refuses nothing that BR-06 allows in a customer number")
    class Firewall {

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({
                "C-PCT-%2550, C-PCT-%50",
                "C-DOT-%2E1, C-DOT-.1",
                "C-LS-%E2%80%A8x, C-LS-\u2028x",
                "C-PS-%E2%80%A9x, C-PS-\u2029x"})
        @DisplayName("an encoded percent, period, line separator or paragraph separator is part of the number")
        void shouldLetTheResourceDecide(String rawPath, String expectedNumber) throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answer = evaluate(rawPath, customer());

            assertThat(answer.status()).as(answer.body()).isEqualTo(200);
            assertThat(versionOf(expectedNumber)).isEqualTo(1L);
        }
    }
}
