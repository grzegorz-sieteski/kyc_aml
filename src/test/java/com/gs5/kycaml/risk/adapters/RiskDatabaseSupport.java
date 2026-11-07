package com.gs5.kycaml.risk.adapters;

import com.gs5.kycaml.risk.adapters.events.OutboxRelay;
import com.gs5.kycaml.risk.adapters.persistence.RiskProfileSqlAdapter;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import com.gs5.kycaml.risk.domain.BookingCentres;
import com.gs5.kycaml.risk.domain.CustomerNumber;
import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskCommand;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskUseCase;
import com.gs5.kycaml.risk.domain.Jurisdiction;
import com.gs5.kycaml.risk.domain.RawEvaluateCustomerRisk;
import com.gs5.kycaml.risk.domain.RawRatedCountry;
import com.gs5.kycaml.risk.domain.RawRecordComplianceDecision;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionCommand;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionUseCase;
import com.gs5.kycaml.risk.domain.RiskProfileId;
import com.gs5.kycaml.risk.domain.RiskProfileIdGenerator;
import com.gs5.kycaml.shared.kernel.ActorContext;
import com.gs5.kycaml.shared.kernel.ActorRole;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real PostgreSQL for the adapter tests: one container for the whole run, a fresh database with the
 * real migrations for every test. Nothing here is a fake: the SQL, the constraints, the trigger and
 * the locking are the ones that run in production.
 */
public abstract class RiskDatabaseSupport {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static {
        POSTGRES.start();
    }

    protected static final UUID DE_CENTRE = UUID.fromString("0de00000-0000-0000-0000-00000000000d");
    protected static final UUID US_CENTRE = UUID.fromString("05000000-0000-0000-0000-000000000005");
    protected static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    protected static final ActorContext ONBOARDING_IN_DE =
            new ActorContext(DE_CENTRE, "onboarding-01", ActorRole.ONBOARDING_SYSTEM);
    protected static final ActorContext ONBOARDING_IN_US =
            new ActorContext(US_CENTRE, "onboarding-02", ActorRole.ONBOARDING_SYSTEM);
    protected static final ActorContext L2_IN_DE = new ActorContext(DE_CENTRE, "l2-01", ActorRole.AML_ANALYST_L2);
    protected static final ActorContext L2_IN_US = new ActorContext(US_CENTRE, "l2-02", ActorRole.AML_ANALYST_L2);

    protected static final String REASON = "Analyst reviewed the alert and the documents";

    protected DataSource dataSource;
    protected JdbcClient jdbc;
    protected JsonMapper json;
    protected TransactionTemplate transaction;
    protected RiskProfileSqlAdapter profiles;

    /** Booking centres of the tests: Germany and the United States. */
    protected final BookingCentres bookingCentres = tenantId -> {
        if (tenantId.equals(DE_CENTRE)) {
            return Optional.of(Jurisdiction.DE);
        }
        return tenantId.equals(US_CENTRE) ? Optional.of(Jurisdiction.US) : Optional.empty();
    };

    /** Real random ids, as in production. */
    protected final RiskProfileIdGenerator ids = new RiskProfileIdGenerator() {
        @Override
        public RiskProfileId nextProfileId() {
            return new RiskProfileId(UUID.randomUUID());
        }

        @Override
        public UUID nextEventId() {
            return UUID.randomUUID();
        }
    };

    @BeforeEach
    void freshDatabase() throws Exception {
        var name = "risk_" + DATABASES.incrementAndGet();
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
            statement.execute("CREATE DATABASE " + name);
        }
        var source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();

        dataSource = source;
        jdbc = JdbcClient.create(source);
        json = JsonMapper.builder().build();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        profiles = new RiskProfileSqlAdapter(jdbc, json, transaction);
    }

    protected Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    protected EvaluateCustomerRiskUseCase evaluateAt(Instant instant) {
        return new EvaluateCustomerRiskUseCase(profiles, bookingCentres, ids, clockAt(instant));
    }

    protected RecordComplianceDecisionUseCase decideAt(Instant instant) {
        return new RecordComplianceDecisionUseCase(profiles, ids, clockAt(instant));
    }

    protected ViewProjector projector() {
        return new ViewProjector(jdbc, json, transaction, clockAt(NOW));
    }

    protected OutboxRelay relay(int batchSize) {
        return new OutboxRelay(jdbc, projector(), transaction, clockAt(NOW), batchSize);
    }

    // ------------------------------------------------------------------ fixtures

    /** E3: 51.00, Yellow, no critical fact. */
    protected static EvaluateCustomerRiskCommand exposed(String number) {
        return command(number, "POLITICALLY_EXPOSED", false);
    }

    /** E1: 4.50, Green. */
    protected static EvaluateCustomerRiskCommand employed(String number) {
        return command(number, "INDIVIDUAL_EMPLOYED", false);
    }

    /** A sanctions match: 100.00, Red, a critical fact in force. */
    protected static EvaluateCustomerRiskCommand sanctioned(String number) {
        return command(number, "INDIVIDUAL_EMPLOYED", true);
    }

    private static EvaluateCustomerRiskCommand command(String number, String profile, boolean sanctions) {
        var low = new RawRatedCountry("DE", "LOW");
        var raw = new RawEvaluateCustomerRisk(number, profile, low, List.of(low), low,
                profile.equals("POLITICALLY_EXPOSED") ? "AE" : "DE",
                profile.equals("POLITICALLY_EXPOSED") ? "REMOTE" : "BRANCH",
                profile.equals("POLITICALLY_EXPOSED"),
                profile.equals("POLITICALLY_EXPOSED") ? "UNCONFIRMED_FINANCIAL_CRIME" : "NONE",
                sanctions, false, false);
        return EvaluateCustomerRiskCommand.parse(raw).toOptional().orElseThrow();
    }

    protected static RecordComplianceDecisionCommand decision(String number, DecisionKind kind, long versionViewed) {
        return RecordComplianceDecisionCommand.parse(
                new RawRecordComplianceDecision(number, kind.name(), REASON, versionViewed))
                .toOptional().orElseThrow();
    }

    protected static CustomerNumber number(String text) {
        return CustomerNumber.parse(text).toOptional().orElseThrow();
    }

    protected int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
