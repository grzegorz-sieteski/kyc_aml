package com.gs5.kycaml.risk.adapters.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-24 (stage 6 re-review, SR-03b): the service role name goes into SQL that runs as the schema owner, so
 * only a plain PostgreSQL identifier is passed to the migrations. Anything else leaves the placeholder
 * unset, and Flyway refuses to migrate.
 */
class RiskDatabaseConfigurationTest {

    private static String placeholderFor(String user) {
        JdbcConnectionDetails details = new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return user;
            }

            @Override
            public String getPassword() {
                return "unused";
            }

            @Override
            public String getJdbcUrl() {
                return "jdbc:postgresql://localhost/unused";
            }
        };
        var configuration = Flyway.configure();
        new RiskDatabaseConfiguration().serviceRolePlaceholder(details).customize(configuration);
        return configuration.getPlaceholders().get(RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"kyc_service", "service", "_svc1"})
    @DisplayName("a plain lower-case identifier is passed to the migrations")
    void shouldPassAPlainIdentifier(String user) {
        assertThat(placeholderFor(user)).isEqualTo(user);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"svc\"; DROP TABLE command.risk_profile_history; --", "Service", "1svc", "svc-role",
            "svc role", "", "a23456789012345678901234567890123456789012345678901234567890123x"})
    @DisplayName("anything else leaves the placeholder unset")
    void shouldRefuseAnythingElse(String user) {
        assertThat(placeholderFor(user)).isNull();
    }
}
