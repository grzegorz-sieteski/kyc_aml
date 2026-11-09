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

    private static java.util.Map<String, String> placeholdersAfter(String user, String presetKey) {
        var configuration = Flyway.configure().placeholders(java.util.Map.of(presetKey, "public"));
        placeholderFor(user, configuration);
        return configuration.getPlaceholders();
    }

    @ParameterizedTest(name = "preset key {0}")
    @ValueSource(strings = {"SERVICE_ROLE", "Service_Role", "service_ROLE", "SERV\u0130CE_ROLE"})
    @DisplayName("R-31: Flyway matches placeholder names ignoring case, so no case variant of service_role survives an invalid user")
    void shouldRemoveEveryCaseVariantForAnInvalidUser(String presetKey) {
        assertThat(placeholdersAfter("Not Valid", presetKey).keySet())
                .noneMatch(key -> key.equalsIgnoreCase(RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER)
                        || key.toLowerCase(java.util.Locale.ROOT).equals(RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER)
                        || key.toLowerCase(java.util.Locale.forLanguageTag("tr")).equals(
                                RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER));
    }

    @ParameterizedTest(name = "preset key {0}")
    @ValueSource(strings = {"SERVICE_ROLE", "Service_Role"})
    @DisplayName("R-31: with a valid user, the service's own name is the only service_role left, in any case")
    void shouldLeaveOnlyTheServiceUserForAValidUser(String presetKey) {
        var placeholders = placeholdersAfter("kyc_service", presetKey);

        assertThat(placeholders.entrySet())
                .filteredOn(entry -> entry.getKey().equalsIgnoreCase(RiskDatabaseConfiguration.SERVICE_ROLE_PLACEHOLDER))
                .extracting(java.util.Map.Entry::getValue).containsExactly("kyc_service");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("R-32: a changed placeholder suffix cannot make V5 read ${service_role} as another, unremoved name")
    void shouldPinThePlaceholderSyntax() {
        var configuration = Flyway.configure().placeholderPrefix("${service_").placeholderSuffix("_role}")
                .placeholderReplacement(false)
                .placeholders(java.util.Map.of("service", "public", "role", "public"));

        placeholderFor("kyc_service", configuration);

        assertThat(configuration.getPlaceholderPrefix()).isEqualTo("${");
        assertThat(configuration.getPlaceholderSuffix()).isEqualTo("}");
        assertThat(configuration.isPlaceholderReplacement()).isTrue();
        assertThat(configuration.getPlaceholders()).containsEntry("service_role", "kyc_service");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("R-32: the syntax is pinned even when the user is refused, so V5 can only fail on the missing name")
    void shouldPinThePlaceholderSyntaxForARefusedUser() {
        var configuration = Flyway.configure().placeholderSuffix("_role}");

        placeholderFor("public", configuration);

        assertThat(configuration.getPlaceholderSuffix()).isEqualTo("}");
        assertThat(configuration.getPlaceholders().keySet()).noneMatch(key -> key.equalsIgnoreCase("service_role"));
    }
}
