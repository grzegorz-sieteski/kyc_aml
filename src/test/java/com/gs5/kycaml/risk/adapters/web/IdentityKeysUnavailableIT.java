package com.gs5.kycaml.risk.adapters.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I-05 (D-13): when the identity provider's key set cannot be fetched, a caller with a token is not
 * identified, exactly like any other. The test configuration's JWK set URI points at a closed port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.outbox.relay.poll-interval=PT1H"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class IdentityKeysUnavailableIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    private HttpResponse<String> view(String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/risk-profiles/C-KEYS"));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a well-formed token while the key set is unreachable: the constant 401, and no token or URL in the log")
    void shouldNotIdentifyWhenTheKeySetIsUnreachable(CapturedOutput output) throws Exception {
        var token = TestTokens.generate("kid-down").sign(TestTokens.claims("MLRO"));

        var withToken = view(token);
        var withoutToken = view(null);

        assertThat(withToken.statusCode()).as(withToken.body()).isEqualTo(401);
        assertThat(withToken.body()).isEqualTo(withoutToken.body());
        assertThat(output.getAll()).doesNotContain(token).doesNotContain("unused-jwks")
                .doesNotContain("at org.springframework.security");
    }
}
