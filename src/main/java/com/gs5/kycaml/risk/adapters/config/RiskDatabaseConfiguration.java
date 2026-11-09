package com.gs5.kycaml.risk.adapters.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The service role of decision D-11 (design addendum H). Flyway migrates as the schema owner
 * ({@code spring.flyway.user} / {@code password}, from {@code FLYWAY_USERNAME} / {@code FLYWAY_PASSWORD}
 * with no defaults); the service connects as a role that owns nothing. The migrations grant that role
 * its rights through the Flyway placeholder {@code service_role}, which is set here to the user the
 * service actually connects as: {@code DATABASE_USERNAME} in production, the container's user under
 * {@code @ServiceConnection}. Taking it from the connection, not from a second setting, means the
 * grants can never name a role other than the one the service logs in as.
 *
 * <p>The placeholder is substituted into SQL that runs as the schema owner, and Flyway does not escape
 * it. So only a plain, unquoted PostgreSQL identifier of at most 63 characters is passed on (R-24), and
 * never one PostgreSQL reads as PUBLIC, a role keyword or a built-in {@code pg_} role (R-30): a GRANT to
 * any of those would reach roles other than the service's, and V5 never runs again to undo it.
 *
 * <p>Any {@code service_role} placeholder configured elsewhere (for example
 * {@code spring.flyway.placeholders.service_role}), in any letter case Flyway would match (R-31), is
 * always removed first, so it is never trusted;
 * any other user name leaves the placeholder unset, and Flyway refuses to migrate. Flyway 12's
 * {@code FluentConfiguration.placeholders(Map)} replaces the whole map (it assigns the field), which is
 * what makes the removal take effect.
 */
@Configuration
class RiskDatabaseConfiguration {

    static final String SERVICE_ROLE_PLACEHOLDER = "service_role";

    /** A lower-case PostgreSQL identifier that needs no quoting, within the 63-byte name limit. */
    static final Pattern PLAIN_ROLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /**
     * Names that match {@link #PLAIN_ROLE_NAME} but do not name one ordinary role in a GRANT: PUBLIC
     * (also when quoted), and the role keywords PostgreSQL resolves to some other role or rejects.
     */
    static final Set<String> RESERVED_ROLE_NAMES =
            Set.of("public", "none", "current_user", "session_user", "current_role", "user");

    /** The prefix PostgreSQL reserves for its built-in roles, such as {@code pg_write_all_data}. */
    static final String RESERVED_ROLE_PREFIX = "pg_";

    @Bean
    FlywayConfigurationCustomizer serviceRolePlaceholder(JdbcConnectionDetails service) {
        return configuration -> {
            var placeholders = new HashMap<>(configuration.getPlaceholders());
            placeholders.keySet().removeIf(RiskDatabaseConfiguration::readAsServiceRole);
            // No user on the connection, or one that is not an acceptable role name, leaves the
            // placeholder unset, and Flyway refuses to migrate.
            Optional.ofNullable(service.getUsername())
                    .filter(RiskDatabaseConfiguration::isAcceptableServiceRole)
                    .ifPresent(role -> placeholders.put(SERVICE_ROLE_PLACEHOLDER, role));
            configuration.placeholders(placeholders);
        };
    }

    /**
     * R-31: Flyway 12 looks placeholders up in a {@code CaseInsensitiveMap} that folds names with the
     * default-locale {@code toLowerCase()}, so a key such as {@code SERVICE_ROLE} would still be read as
     * {@code service_role}. Every key that any of those foldings could turn into the name is removed.
     */
    private static boolean readAsServiceRole(String key) {
        return key.equalsIgnoreCase(SERVICE_ROLE_PLACEHOLDER)
                || key.toLowerCase(Locale.ROOT).equals(SERVICE_ROLE_PLACEHOLDER)
                || key.toLowerCase(Locale.getDefault()).equals(SERVICE_ROLE_PLACEHOLDER);
    }

    private static boolean isAcceptableServiceRole(String role) {
        return PLAIN_ROLE_NAME.matcher(role).matches()
                && !RESERVED_ROLE_NAMES.contains(role)
                && !role.startsWith(RESERVED_ROLE_PREFIX);
    }
}
