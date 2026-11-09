package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5: adversarial probing of the running service, over real HTTP on a real Tomcat and a real
 * PostgreSQL. MockMvc is not enough here: it skips the servlet container's own path handling and the
 * error dispatch, which is where several of these findings live.
 *
 * <p>Each {@code QA-xx} test that fails today is a defect, described in the stage 5 report on the
 * state board. The others guard behaviour that held under attack and must keep holding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.outbox.relay.poll-interval=PT1H"})
@Import(RiskApiAdversarialIT.Identity.class)
@Testcontainers
class RiskApiAdversarialIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String DE_CENTRE = "0de00000-0000-0000-0000-00000000000d";
    private static final String REASON = "Analyst reviewed the alert and the documents";
    private static final int RACERS = 24;

    private static final String CUSTOMER = """
            {"profileClass":"POLITICALLY_EXPOSED",
             "residence":{"country":"DE","riskClass":"LOW"},
             "citizenships":[{"country":"DE","riskClass":"LOW"}],
             "birth":{"country":"DE","riskClass":"LOW"},
             "loginCountry":"AE","channel":"REMOTE","highRiskProductRequest":true,
             "adverseMediaClass":"UNCONFIRMED_FINANCIAL_CRIME",
             "sanctionsMatch":false,"terroristLink":false,"forgedIdentity":false}
            """;

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    private static volatile Optional<AuthenticatedCaller> caller = Optional.empty();

    /** The only identity source there is: the test says who is calling. */
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

    private final HttpClient http = HttpClient.newHttpClient();

    private record Answer(int status, String contentType, String body) {
    }

    @BeforeEach
    void nobodyIsIdentified() {
        caller = Optional.empty();
    }

    private static void callAs(ActorRole role) {
        caller = AuthenticatedCaller.parse(DE_CENTRE, "actor-" + role.name().toLowerCase(), role.name());
    }

    private static String newNumber() {
        return "C-QA-" + NUMBERS.incrementAndGet();
    }

    /** Sends a request with the path exactly as given: no client-side encoding or normalisation. */
    private Answer send(String method, String rawPath, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private Answer evaluate(String rawNumber, String body) throws Exception {
        return send("POST", "/api/risk-profiles/" + rawNumber + "/evaluations", body);
    }

    private Answer decide(String number, String bodyFields) throws Exception {
        return send("POST", "/api/risk-profiles/" + number + "/decisions", "{" + bodyFields + "}");
    }

    /** A customer evaluated once, at version 1; the caller is left as an L2 analyst. */
    private String evaluatedCustomer() throws Exception {
        var number = newNumber();
        callAs(ActorRole.ONBOARDING_SYSTEM);
        assertThat(evaluate(number, CUSTOMER).status()).isEqualTo(200);
        callAs(ActorRole.AML_ANALYST_L2);
        return number;
    }

    private long versionOf(String number) {
        return jdbc.sql("SELECT version FROM command.risk_profile WHERE tenant_id = CAST(:tenant AS UUID)"
                        + " AND customer_number = :number")
                .param("tenant", DE_CENTRE).param("number", number)
                .query(Long.class).optional().orElse(0L);
    }

    private int historyOf(String number) {
        return jdbc.sql("SELECT count(*) FROM command.risk_profile_history h JOIN command.risk_profile p"
                        + " ON p.id = h.profile_id WHERE p.tenant_id = CAST(:tenant AS UUID)"
                        + " AND p.customer_number = :number")
                .param("tenant", DE_CENTRE).param("number", number)
                .query(Integer.class).single();
    }

    // =========================================================================== defects found

    @Nested
    @DisplayName("defects found in stage 5")
    class Defects {

        @ParameterizedTest(name = "versionViewed = {0}")
        @ValueSource(strings = {"1.9", "\"1\""})
        @DisplayName("QA-01: a version viewed that is not a whole JSON number records no decision (BR-33)")
        void shouldNotRecordADecisionOnAVersionThatIsNotAWholeNumber(String versionViewed) throws Exception {
            var number = evaluatedCustomer();

            var answer = decide(number, "\"kind\":\"FREEZE_ASSETS\",\"justification\":\"" + REASON
                    + "\",\"versionViewed\":" + versionViewed);

            assertThat(answer.status()).as(answer.body()).isEqualTo(400);
            assertThat(versionOf(number)).isEqualTo(1L);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "\"sanctionsMatch\":0", "\"sanctionsMatch\":1", "\"sanctionsMatch\":2",
                "\"sanctionsMatch\":\"false\"", "\"terroristLink\":0", "\"forgedIdentity\":\"false\"",
                "\"highRiskProductRequest\":0"})
        @DisplayName("QA-02: a critical fact or the product request that is not a JSON boolean is refused (BR-10, BR-12)")
        void shouldRefuseAYesOrNoAnswerThatIsNotABoolean(String field) throws Exception {
            var name = field.substring(0, field.indexOf(':'));
            var body = CUSTOMER.replaceFirst(java.util.regex.Pattern.quote(name) + ":(true|false)", field);
            assertThat(body).contains(field);
            var number = newNumber();
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answer = evaluate(number, body);

            assertThat(answer.status()).as(answer.body()).isEqualTo(400);
            assertThat(versionOf(number)).isZero();
        }

        @Test
        @DisplayName("QA-03: a customer number with a ';' never lands on the customer named by its prefix (BR-06)")
        void shouldNotTruncateACustomerNumberAtASemicolon() throws Exception {
            var number = evaluatedCustomer();
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answer = evaluate(number + ";x", CUSTOMER);

            assertThat(versionOf(number)).as("status %d, body %s", answer.status(), answer.body()).isEqualTo(1L);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"DELETE /api/risk-profiles/C-QA-SECRET",
                "PUT /api/risk-profiles/C-QA-SECRET",
                "GET /api/risk-profiles/C-QA-SECRET/evaluations",
                "GET /api/risk-profiles/C-QA-SECRET/unknown"})
        @DisplayName("QA-04: an answer from outside the four resources does not echo the customer number either")
        void shouldNotEchoTheCustomerNumberFromTheContainerErrorPath(String request) throws Exception {
            callAs(ActorRole.AML_ANALYST_L2);
            var parts = request.split(" ");

            var answer = send(parts[0], parts[1], parts[0].equals("PUT") ? "{}" : null);

            assertThat(answer.status()).isBetween(400, 499);
            assertThat(answer.body()).doesNotContain("C-QA-SECRET");
        }
    }

    // =========================================================================== held under attack

    @Nested
    @DisplayName("concurrency: the database settles every race (BR-30)")
    class Concurrency {

        private List<Answer> race(String method, String path, String body) throws Exception {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Answer>>();
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var i = 0; i < RACERS; i++) {
                    futures.add(threads.submit(() -> {
                        start.await();
                        return send(method, path, body);
                    }));
                }
                start.countDown();
                var answers = new ArrayList<Answer>();
                for (var future : futures) {
                    answers.add(future.get());
                }
                return answers;
            }
        }

        @Test
        @DisplayName("many freezes on the same version: exactly one is recorded, every other is a 409")
        void shouldRecordExactlyOneOfManyConcurrentFreezes() throws Exception {
            var number = evaluatedCustomer();

            var answers = race("POST", "/api/risk-profiles/" + number + "/decisions",
                    "{\"kind\":\"FREEZE_ASSETS\",\"justification\":\"" + REASON + "\",\"versionViewed\":1}");

            assertThat(answers).filteredOn(a -> a.status() == 200).hasSize(1);
            assertThat(answers).filteredOn(a -> a.status() != 200).allSatisfy(a -> assertThat(a.status()).isEqualTo(409));
            assertThat(versionOf(number)).isEqualTo(2L);
            assertThat(historyOf(number)).isEqualTo(2);
        }

        @Test
        @DisplayName("many first evaluations of one new customer: one profile, one history row per success, no 5xx")
        void shouldKeepOneProfileUnderConcurrentFirstEvaluations() throws Exception {
            var number = newNumber();
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answers = race("POST", "/api/risk-profiles/" + number + "/evaluations", CUSTOMER);

            var succeeded = answers.stream().filter(a -> a.status() == 200).toList();
            assertThat(succeeded).isNotEmpty();
            assertThat(answers).filteredOn(a -> a.status() != 200).allSatisfy(a -> assertThat(a.status()).isEqualTo(409));
            assertThat(succeeded).extracting(Answer::body).doesNotHaveDuplicates();
            assertThat(versionOf(number)).isEqualTo(succeeded.size());
            assertThat(historyOf(number)).isEqualTo(succeeded.size());
        }
    }

    @Nested
    @DisplayName("boundaries hold over real HTTP")
    class Boundaries {

        private static final String EMOJI_ENCODED = "%F0%9F%98%80";
        private static final String EMOJI = "\\uD83D\\uDE00";

        @Test
        @DisplayName("a customer number is measured in code points: 64 emoji pass, 65 are too long (BR-06)")
        void shouldMeasureTheCustomerNumberInCodePoints() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            assertThat(evaluate(EMOJI_ENCODED.repeat(64), CUSTOMER).status()).isEqualTo(200);
            var tooLong = evaluate(EMOJI_ENCODED.repeat(65), CUSTOMER);
            assertThat(tooLong.status()).isEqualTo(400);
            assertThat(tooLong.body()).contains("urn:problem-type:risk:customer-number-too-long");
        }

        @ParameterizedTest(name = "%{0}")
        @ValueSource(strings = {"0A", "0D", "7F", "C2%85"})
        @DisplayName("a control character that reaches the resource is refused as such (BR-06)")
        void shouldRefuseAnEncodedControlCharacterInTheCustomerNumber(String encoded) throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answer = evaluate("C-%" + encoded + "X", CUSTOMER);

            assertThat(answer.status()).isEqualTo(400);
            assertThat(answer.body()).contains("urn:problem-type:risk:customer-number-control-character");
        }

        @ParameterizedTest(name = "%{0}")
        @ValueSource(strings = {"00", "2F", "5C", "FF", "ED%A0%80"})
        @DisplayName("a path the container refuses is a 400 that names no server or version, and records nothing")
        void shouldRefuseAPathTheContainerRejectsWithoutDisclosingTheServer(String encoded) throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answer = evaluate("C-%" + encoded + "X", CUSTOMER);

            assertThat(answer.status()).isEqualTo(400);
            assertThat(answer.body()).doesNotContainIgnoringCase("tomcat").doesNotContain("Exception");
        }

        @Test
        @DisplayName("a justification is measured in code points: 19 emoji are too short, 20 pass (BR-34)")
        void shouldMeasureTheJustificationInCodePoints() throws Exception {
            var shortOne = decide(evaluatedCustomer(), "\"kind\":\"FREEZE_ASSETS\",\"justification\":\""
                    + EMOJI.repeat(19) + "\",\"versionViewed\":1");
            var exact = decide(evaluatedCustomer(), "\"kind\":\"FREEZE_ASSETS\",\"justification\":\""
                    + EMOJI.repeat(20) + "\",\"versionViewed\":1");

            assertThat(shortOne.body()).contains("urn:problem-type:risk:justification-too-short");
            assertThat(exact.status()).isEqualTo(200);
        }

        @Test
        @DisplayName("2 000 characters pass and 2 001 are too long (BR-34)")
        void shouldHoldTheJustificationUpperBound() throws Exception {
            var longest = decide(evaluatedCustomer(), "\"kind\":\"FREEZE_ASSETS\",\"justification\":\""
                    + "a".repeat(2_000) + "\",\"versionViewed\":1");
            var tooLong = decide(evaluatedCustomer(), "\"kind\":\"FREEZE_ASSETS\",\"justification\":\""
                    + "a".repeat(2_001) + "\",\"versionViewed\":1");

            assertThat(longest.status()).isEqualTo(200);
            assertThat(tooLong.body()).contains("urn:problem-type:risk:justification-too-long");
        }

        @Test
        @DisplayName("CR LF is a line break, a CR alone is a control character (BR-34)")
        void shouldAcceptOnlyRealLineBreaks() throws Exception {
            var crlf = decide(evaluatedCustomer(), "\"kind\":\"FREEZE_ASSETS\",\"justification\":\"" + REASON
                    + "\\r\\nsecond line\",\"versionViewed\":1");
            var loneCr = decide(evaluatedCustomer(), "\"kind\":\"FREEZE_ASSETS\",\"justification\":\"" + REASON
                    + "\\rsecond line\",\"versionViewed\":1");

            assertThat(crlf.status()).isEqualTo(200);
            assertThat(loneCr.body()).contains("urn:problem-type:risk:justification-control-character");
        }

        @Test
        @DisplayName("JSON nested 5 000 deep is unreadable input, not a crash")
        void shouldTreatDeeplyNestedJsonAsNoInput() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);

            var answer = evaluate(newNumber(), "{\"x\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}");

            assertThat(answer.status()).isEqualTo(400);
            assertThat(answer.contentType()).startsWith("application/problem+json");
        }

        @Test
        @DisplayName("a body field naming another booking centre is ignored: the caller's centre decides (BR-04)")
        void shouldIgnoreATenantSmuggledInTheBody() throws Exception {
            callAs(ActorRole.ONBOARDING_SYSTEM);
            var number = newNumber();

            var answer = evaluate(number, CUSTOMER.replaceFirst("\\{", "{\"tenantId\":\"05000000-0000-0000-0000-000000000005\","));

            assertThat(answer.status()).isEqualTo(200);
            assertThat(versionOf(number)).isEqualTo(1L);
        }
    }
}
