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
        return placeholderFor(user, Flyway.configure());
    }

    private static String placeholderFor(String user, org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
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
        new RiskDatabaseConfiguration().serviceRolePlaceholder(details).customize(configuration);
        return configuration.getPlaceholders().get(RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"kyc_service", "service", "_svc1",
            "a23456789012345678901234567890123456789012345678901234567890123"})
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"public", "none", "current_user", "session_user", "current_role", "user",
            "pg_write_all_data", "pg_monitor"})
    @DisplayName("R-30: a name PostgreSQL reads as PUBLIC, a keyword or a built-in role is refused: GRANT would reach every role")
    void shouldRefuseReservedRoleNames(String user) {
        assertThat(placeholderFor(user)).isNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("R-30: a service_role placeholder configured elsewhere is never trusted: an invalid user still leaves it unset")
    void shouldNotKeepAConfiguredPlaceholderForAnInvalidUser() {
        var preset = Flyway.configure().placeholders(java.util.Map.of(
                RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER, "public"));

        assertThat(placeholderFor("Not Valid", preset)).isNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("R-30: a valid user replaces a service_role placeholder configured elsewhere")
    void shouldReplaceAConfiguredPlaceholderWithTheServiceUser() {
        var preset = Flyway.configure().placeholders(java.util.Map.of(
                RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER, "someone_else"));

        assertThat(placeholderFor("kyc_service", preset)).isEqualTo("kyc_service");
    }
}
