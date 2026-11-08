package com.gs5.kycaml.risk.adapters.persistence;

import com.gs5.kycaml.risk.adapters.RiskDatabaseSupport;
import com.gs5.kycaml.risk.adapters.events.OutboxRelay;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskUseCase;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionUseCase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-09 and R-17 (stage 6, SR-08, CR-12; decision D-11). Migrations run as the schema owner; the service
 * connects as a role that owns nothing, may do its work, may only add to and read the history, and
 * cannot switch off the append-only guard. Even the owner cannot empty the history with TRUNCATE.
 */
class DatabaseRolesIT extends RiskDatabaseSupport {

    private static final String SERVICE_ROLE = "kyc_service";
    private static final String SERVICE_PASSWORD = "service-test-only";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private PGSimpleDataSource owner;
    private PGSimpleDataSource service;
    private JdbcClient serviceJdbc;
    private TransactionTemplate serviceTransaction;

    @BeforeEach
    void databaseMigratedByTheOwnerForTheServiceRole() throws SQLException {
        var base = (PGSimpleDataSource) dataSource;
        try (var admin = DriverManager.getConnection(base.getUrl(), base.getUser(), base.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + SERVICE_ROLE
                    + "') THEN CREATE ROLE " + SERVICE_ROLE + " LOGIN PASSWORD '" + SERVICE_PASSWORD
                    + "'; END IF; END $$");
            var name = "roles_" + DATABASES.incrementAndGet() + "_" + System.nanoTime();
            statement.execute("CREATE DATABASE " + name);
            owner = new PGSimpleDataSource();
            owner.setUrl(base.getUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
            owner.setUser(base.getUser());
            owner.setPassword(base.getPassword());
        }
        Flyway.configure().dataSource(owner).locations("classpath:db/migration")
                .placeholders(Map.of("service_role", SERVICE_ROLE)).load().migrate();

        service = new PGSimpleDataSource();
        service.setUrl(owner.getUrl());
        service.setUser(SERVICE_ROLE);
        service.setPassword(SERVICE_PASSWORD);
        serviceJdbc = JdbcClient.create(service);
        serviceTransaction = new TransactionTemplate(new DataSourceTransactionManager(service));
    }

    private String sqlStateOf(String sql, PGSimpleDataSource as) {
        try (var connection = as.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
            return "succeeded";
        } catch (SQLException refused) {
            return refused.getSQLState();
        }
    }

    @Test
    @DisplayName("the service role can evaluate, decide and project with the grants of the migrations alone")
    void shouldLetTheServiceRoleDoItsWork() {
        var serviceProfiles = new RiskProfileSqlAdapter(serviceJdbc, json, serviceTransaction);
        var evaluate = new EvaluateCustomerRiskUseCase(serviceProfiles, bookingCentres, ids, clockAt(NOW));
        var decide = new RecordComplianceDecisionUseCase(serviceProfiles, ids, clockAt(NOW.plusSeconds(1)));

        assertThat(evaluate.execute(ONBOARDING_IN_DE, exposed("C-ROLE")).isSuccess()).isTrue();
        assertThat(decide.execute(L2_IN_DE, decision("C-ROLE", DecisionKind.FREEZE_ASSETS, 1)).isSuccess()).isTrue();
        var relay = new OutboxRelay(serviceJdbc,
                new ViewProjector(serviceJdbc, json, serviceTransaction, clockAt(NOW)),
                serviceTransaction, clockAt(NOW), 100);

        assertThat(relay.runOnce()).isEqualTo(2);
    }

    @Test
    @DisplayName("the service role owns no table, so it cannot alter the schema it works in")
    void shouldOwnNothing() {
        var owned = JdbcClient.create(owner).sql("SELECT count(*) FROM pg_tables WHERE schemaname IN"
                + " ('command', 'query') AND tableowner = :role").param("role", SERVICE_ROLE)
                .query(Integer.class).single();

        assertThat(owned).isZero();
        assertThat(sqlStateOf("ALTER TABLE command.risk_profile_history DISABLE TRIGGER ALL", service))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("the service role may not update, delete or truncate the history: it lacks the right, not only the trigger")
    void shouldNotGrantTheServiceRoleAnyChangeToTheHistory() {
        assertThat(sqlStateOf("UPDATE command.risk_profile_history SET actor_id = 'x'", service))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlStateOf("DELETE FROM command.risk_profile_history", service))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlStateOf("TRUNCATE command.risk_profile_history", service))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("not even the owner can empty the history with TRUNCATE (append-only, BR-29)")
    void shouldRefuseTruncateOfTheHistoryForTheOwner() {
        assertThatThrownBy(() -> JdbcClient.create(owner).sql("TRUNCATE command.risk_profile_history").update())
                .as("TRUNCATE bypasses row triggers; a statement trigger must refuse it")
                .isInstanceOf(RuntimeException.class);
    }
}
