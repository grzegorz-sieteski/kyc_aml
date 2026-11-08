package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.risk.adapters.access.AuthenticatedCaller;
import com.gs5.kycaml.risk.adapters.events.OutboxRelay;
import com.gs5.kycaml.shared.kernel.ActorRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole service over HTTP on a real PostgreSQL: the application context as it runs, the real
 * use cases, the real outbox and projector. Only the identity is injected, because the service has
 * no identity source yet and a resolver that invented callers would not be authentication.
 */
@SpringBootTest(properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.booking-centres.05a00000-0000-0000-0000-0000000000a5=US",
        "app.outbox.relay.poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class RiskApiFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String DE_CENTRE = "0de00000-0000-0000-0000-00000000000d";
    private static final String OTHER_CENTRE = "05000000-0000-0000-0000-000000000005";
    /** A second booking centre in scope: its callers are identified, and see none of Germany's customers. */
    private static final String US_CENTRE = "05a00000-0000-0000-0000-0000000000a5";
    private static final String CUSTOMER = "C-SECRET-123";
    private static final String REASON = "Analyst reviewed the alert and the documents";

    private static final String EXPOSED_CUSTOMER = """
            {"profileClass":"POLITICALLY_EXPOSED",
             "residence":{"country":"DE","riskClass":"LOW"},
             "citizenships":[{"country":"DE","riskClass":"LOW"}],
             "birth":{"country":"DE","riskClass":"LOW"},
             "loginCountry":"AE","channel":"REMOTE","highRiskProductRequest":true,
             "adverseMediaClass":"UNCONFIRMED_FINANCIAL_CRIME",
             "sanctionsMatch":false,"terroristLink":false,"forgedIdentity":false}
            """;

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

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OutboxRelay relay;

    private static void callAs(ActorRole role, String tenant) {
        caller = AuthenticatedCaller.parse(tenant, "actor-" + role.name().toLowerCase(), role.name());
    }

    private static void callAs(ActorRole role) {
        callAs(role, DE_CENTRE);
    }

    @BeforeEach
    void nobodyIsIdentified() {
        caller = Optional.empty();
    }

    private ResultActions evaluate(String number, String body) throws Exception {
        return mvc.perform(post("/api/risk-profiles/" + number + "/evaluations")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decide(String number, String kind, long versionViewed) throws Exception {
        return mvc.perform(post("/api/risk-profiles/" + number + "/decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"" + kind + "\",\"justification\":\"" + REASON + "\",\"versionViewed\":"
                        + versionViewed + "}"));
    }

    private ResultActions view(String number) throws Exception {
        return mvc.perform(get("/api/risk-profiles/" + number));
    }

    private ResultActions frontOffice(String number) throws Exception {
        return mvc.perform(get("/api/risk-profiles/" + number + "/front-office-status"));
    }

    private void relayEverything() {
        while (relay.runOnce() > 0) {
            continue;
        }
    }

    // ------------------------------------------------------------------ the whole flow

    @Test
    @DisplayName("evaluate, view, freeze: the compliance view follows, and the front office only ever sees 'pending'")
    void shouldCarryACustomerThroughEvaluationAndAFreeze() throws Exception {
        callAs(ActorRole.ONBOARDING_SYSTEM);
        evaluate("C-FLOW-1", EXPOSED_CUSTOMER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerNumber").value("C-FLOW-1"))
                .andExpect(jsonPath("$.version").value(1));
        relayEverything();

        callAs(ActorRole.AML_ANALYST_L2);
        view("C-FLOW-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerNumber").value("C-FLOW-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.evaluatedTrack").value("YELLOW"))
                .andExpect(jsonPath("$.effectiveTrack").value("YELLOW"))
                .andExpect(jsonPath("$.restriction").value("SOFT_LIMITS"))
                .andExpect(jsonPath("$.id").doesNotExist());

        callAs(ActorRole.FRONT_OFFICE);
        frontOffice("C-FLOW-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFICATION_PENDING"));

        callAs(ActorRole.AML_ANALYST_L2);
        decide("C-FLOW-1", "FREEZE_ASSETS", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        relayEverything();

        view("C-FLOW-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.effectiveTrack").value("RED"))
                .andExpect(jsonPath("$.restriction").value("ASSETS_FROZEN"))
                .andExpect(jsonPath("$.latestDecision.justification").value(REASON));

        callAs(ActorRole.FRONT_OFFICE);
        frontOffice("C-FLOW-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFICATION_PENDING"))
                .andExpect(content().string(not(containsString("RED"))))
                .andExpect(content().string(not(containsString("FROZEN"))))
                .andExpect(jsonPath("$.totalScore").doesNotExist())
                .andExpect(jsonPath("$.effectiveTrack").doesNotExist());
    }

    @Test
    @DisplayName("a decision made on an out-of-date view is refused as changed since viewed (409)")
    void shouldRefuseADecisionOnAStaleView() throws Exception {
        callAs(ActorRole.ONBOARDING_SYSTEM);
        evaluate("C-STALE", EXPOSED_CUSTOMER).andExpect(status().isOk());
        callAs(ActorRole.AML_ANALYST_L2);

        decide("C-STALE", "ESCALATE_EDD", 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:risk:risk-profile-changed-since-viewed"));
    }

    @Test
    @DisplayName("clearing a customer who has nothing to clear is refused by the decision rules (422)")
    void shouldRefuseClearingWhenNothingIsToBeCleared() throws Exception {
        callAs(ActorRole.ONBOARDING_SYSTEM);
        evaluate("C-GREEN", EXPOSED_CUSTOMER.replace("POLITICALLY_EXPOSED", "INDIVIDUAL_EMPLOYED")
                .replace("\"highRiskProductRequest\":true", "\"highRiskProductRequest\":false")
                .replace("UNCONFIRMED_FINANCIAL_CRIME", "NONE")
                .replace("\"loginCountry\":\"AE\"", "\"loginCountry\":\"DE\"")
                .replace("REMOTE", "BRANCH")).andExpect(status().isOk());
        callAs(ActorRole.AML_ANALYST_L2);

        decide("C-GREEN", "CLEAR_FALSE_POSITIVE", 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem-type:risk:nothing-to-clear"));
    }

    // ------------------------------------------------------------------ who may call

    @Test
    @DisplayName("nobody identified: every route answers 401 before looking at any input (BR-01)")
    void shouldRefuseAnUnidentifiedCaller() throws Exception {
        evaluate(CUSTOMER, "not even json").andExpect(status().isUnauthorized());
        decide(CUSTOMER, "NOT_A_KIND", 1).andExpect(status().isUnauthorized());
        view(CUSTOMER).andExpect(status().isUnauthorized());
        frontOffice(CUSTOMER).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a request with no body is refused by the gate first: 401 unidentified, 403 wrong role, then 400")
    void shouldCheckTheCallerBeforeLookingForABody() throws Exception {
        mvc.perform(post("/api/risk-profiles/C-1/evaluations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/risk-profiles/C-1/decisions")).andExpect(status().isUnauthorized());

        callAs(ActorRole.AML_ANALYST_L1);
        mvc.perform(post("/api/risk-profiles/C-1/evaluations")).andExpect(status().isForbidden());

        callAs(ActorRole.ONBOARDING_SYSTEM);
        mvc.perform(post("/api/risk-profiles/C-1/evaluations")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a role that may not do the operation is refused with 403 (BR-02)")
    void shouldRefuseTheWrongRole() throws Exception {
        callAs(ActorRole.AML_ANALYST_L1);
        evaluate(CUSTOMER, EXPOSED_CUSTOMER).andExpect(status().isForbidden());
        decide(CUSTOMER, "FREEZE_ASSETS", 1).andExpect(status().isForbidden());

        callAs(ActorRole.FRONT_OFFICE);
        view(CUSTOMER).andExpect(status().isForbidden());
        decide(CUSTOMER, "ESCALATE_EDD", 1).andExpect(status().isForbidden());

        callAs(ActorRole.ONBOARDING_SYSTEM);
        view(CUSTOMER).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unreadable decision kind from an unauthorised caller is a 403, not a hint about the kind")
    void shouldRefuseTheRoleBeforeTheKind() throws Exception {
        callAs(ActorRole.FRONT_OFFICE);

        decide(CUSTOMER, "NOT_A_KIND", 1).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ input and absence

    @Test
    @DisplayName("input that cannot be scored is a 400, and so is a body that is not readable")
    void shouldRefuseBadInput() throws Exception {
        callAs(ActorRole.ONBOARDING_SYSTEM);

        evaluate("C-BAD", "{\"profileClass\":\"NOT_A_CLASS\"}").andExpect(status().isBadRequest());
        evaluate("C-BAD", "not even json").andExpect(status().isBadRequest());

        callAs(ActorRole.AML_ANALYST_L2);
        decide("C-BAD", "NOT_A_KIND", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:risk:decision-kind-not-recognised"));
    }

    @Test
    @DisplayName("an unknown customer, or one of another booking centre, is 404 (BR-04)")
    void shouldAnswerNotFoundForUnknownAndForeignCustomers() throws Exception {
        callAs(ActorRole.ONBOARDING_SYSTEM);
        evaluate("C-HOME", EXPOSED_CUSTOMER).andExpect(status().isOk());
        relayEverything();

        callAs(ActorRole.AML_ANALYST_L2);
        view("C-UNKNOWN").andExpect(status().isNotFound());
        decide("C-UNKNOWN", "ESCALATE_EDD", 1).andExpect(status().isNotFound());

        callAs(ActorRole.AML_ANALYST_L2, US_CENTRE);
        view("C-HOME").andExpect(status().isNotFound());
        decide("C-HOME", "ESCALATE_EDD", 1).andExpect(status().isNotFound());

        callAs(ActorRole.FRONT_OFFICE, US_CENTRE);
        frontOffice("C-HOME").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a caller of a booking centre not in scope is not identified: 401 on every route, before any input (BR-01, R-05)")
    void shouldRefuseABookingCentreWithoutAJurisdiction() throws Exception {
        callAs(ActorRole.ONBOARDING_SYSTEM, OTHER_CENTRE);
        evaluate("C-ELSEWHERE", EXPOSED_CUSTOMER)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:risk:caller-not-identified"));
        evaluate("C-ELSEWHERE", "{\"profileClass\":\"NOT_A_CLASS\"}")
                .andExpect(status().isUnauthorized());

        callAs(ActorRole.AML_ANALYST_L2, OTHER_CENTRE);
        view("C-ELSEWHERE").andExpect(status().isUnauthorized());
        decide("C-ELSEWHERE", "ESCALATE_EDD", 1).andExpect(status().isUnauthorized());

        callAs(ActorRole.FRONT_OFFICE, OTHER_CENTRE);
        frontOffice("C-ELSEWHERE").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no problem answer repeats the customer number or the path (constant text and instance)")
    void shouldNeverEchoTheCustomerNumberInAProblem() throws Exception {
        callAs(ActorRole.AML_ANALYST_L2);

        view(CUSTOMER)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value("/api/risk-profiles/compliance-view"))
                .andExpect(content().string(not(containsString(CUSTOMER))));
        decide(CUSTOMER, "ESCALATE_EDD", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.instance").value("/api/risk-profiles/decisions"))
                .andExpect(content().string(not(containsString(CUSTOMER))));
    }
}
