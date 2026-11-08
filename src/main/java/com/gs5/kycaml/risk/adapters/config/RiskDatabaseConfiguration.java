package com.gs5.kycaml.risk.adapters.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Optional;

/**
 * The service role of decision D-11 (design addendum H). Flyway migrates as the schema owner
 * ({@code spring.flyway.user} / {@code password}, from {@code FLYWAY_USERNAME} / {@code FLYWAY_PASSWORD}
 * with no defaults); the service connects as a role that owns nothing. The migrations grant that role
 * its rights through the Flyway placeholder {@code service_role}, which is set here to the user the
 * service actually connects as: {@code DATABASE_USERNAME} in production, the container's user under
 * {@code @ServiceConnection}. Taking it from the connection, not from a second setting, means the
 * grants can never name a role other than the one the service logs in as.
 */
@Configuration
class RiskDatabaseConfiguration {

    static final String SERVICE_ROLE_PLACEHOLDER = "service_role";

    @Bean
    FlywayConfigurationCustomizer serviceRolePlaceholder(JdbcConnectionDetails service) {
        // No user on the connection leaves the placeholder unset, and Flyway refuses to migrate.
        return configuration -> Optional.ofNullable(service.getUsername()).ifPresent(role -> {
            var placeholders = new HashMap<>(configuration.getPlaceholders());
            placeholders.put(SERVICE_ROLE_PLACEHOLDER, role);
            configuration.placeholders(placeholders);
        });
    }
}
