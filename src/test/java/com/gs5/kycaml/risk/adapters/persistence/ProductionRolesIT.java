package com.gs5.kycaml.risk.adapters.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-29 (stage 6 re-review, CR-5, SR-03c; D-11). The production wiring itself, not a hand-built Flyway:
 * the application context starts with a datasource user that is not the owner and a separate Flyway
 * owner, as `application.yml` reads them from DATABASE_* and FLYWAY_*. The migrations must grant the
 * service user (through RiskDatabaseConfiguration), and that user must own nothing.
 */
@SpringBootTest(properties = {
        "app.booking-centres.0de00000-0000-0000-0000-00000000000d=DE",
        "app.outbox.relay.poll-interval=PT1H"})
@Testcontainers
class ProductionRolesIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String APP_ROLE = "kyc_app";
    private static final String APP_PASSWORD = "app-test-only";

    @DynamicPropertySource
    static void twoRoles(DynamicPropertyRegistry registry) throws SQLException {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE
                    + "') THEN CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'; END IF; END $$");
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    private static JdbcClient owner() {
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return JdbcClient.create(source);
    }

    @Test
    @DisplayName("the service connects as its own role, and the migrations ran as the owner")
    void shouldConnectAsTheServiceAndMigrateAsTheOwner() {
        assertThat(JdbcClient.create(dataSource).sql("SELECT current_user").query(String.class).single())
                .isEqualTo(APP_ROLE);
        assertThat(owner().sql("SELECT DISTINCT installed_by FROM flyway_schema_history").query(String.class).list())
                .containsExactly(POSTGRES.getUsername());
    }

    @Test
    @DisplayName("the service role owns no table and has exactly its grants: history is SELECT and INSERT only")
    void shouldGrantTheServiceRoleItsRightsAndNoMore() {
        var owner = owner();

        assertThat(owner.sql("SELECT count(*) FROM pg_tables WHERE schemaname IN ('command', 'query')"
                + " AND tableowner = :role").param("role", APP_ROLE).query(Integer.class).single()).isZero();
        for (var right : new String[] {"SELECT", "INSERT"}) {
            assertThat(privilege(owner, "command.risk_profile_history", right)).as(right).isTrue();
        }
        for (var right : new String[] {"UPDATE", "DELETE", "TRUNCATE"}) {
            assertThat(privilege(owner, "command.risk_profile_history", right)).as(right).isFalse();
        }
        for (var table : new String[] {"command.risk_profile", "command.outbox", "query.compliance_risk_view",
                "query.front_office_status"}) {
            assertThat(privilege(owner, table, "UPDATE")).as(table).isTrue();
        }
    }

    private static boolean privilege(JdbcClient owner, String table, String right) {
        return owner.sql("SELECT has_table_privilege(:role, :table, :right)")
                .param("role", APP_ROLE).param("table", table).param("right", right)
                .query(Boolean.class).single();
    }
}
