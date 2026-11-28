package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.KycAmlApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * I-01 (D-13): an identity setting that is present but empty must stop the service from starting. Left
 * alone, Spring Boot starts and simply drops the issuer or audience check, so a token issued for another
 * application, or by another tenant, would identify a caller.
 */
@Testcontainers
class IdentitySettingsStartupIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    private static String[] arguments(String emptied) {
        var arguments = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.user=" + POSTGRES.getUsername(),
                "--spring.flyway.password=" + POSTGRES.getPassword(),
                "--app.outbox.relay.poll-interval=PT1H"));
        if (emptied != null) {
            arguments.add("--" + JWT + emptied + "=");
        }
        return arguments.toArray(String[]::new);
    }

    private static String messagesOf(Throwable failure) {
        var text = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    @DisplayName("control: with all three settings present the service starts")
    void shouldStartWithAllSettings() {
        try (var context = new SpringApplicationBuilder(KycAmlApplication.class).run(arguments(null))) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @ParameterizedTest(name = "{0} empty")
    @CsvSource({"issuer-uri, issuer", "audiences, audience", "jwk-set-uri, jwk"})
    @DisplayName("an empty issuer, audience or key set URI stops startup, and the failure names the setting")
    void shouldRefuseToStartWithAnEmptySetting(String setting, String named) {
        var failure = catchThrowable(() ->
                new SpringApplicationBuilder(KycAmlApplication.class).run(arguments(setting)).close());

        assertThat(failure).as("startup with an empty " + setting).isNotNull();
        assertThat(messagesOf(failure)).containsIgnoringCase(named);
    }
}
