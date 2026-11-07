package com.gs5.kycaml.risk.adapters.persistence;

import com.gs5.kycaml.risk.domain.CustomerNumber;
import com.gs5.kycaml.risk.domain.DecisionRecord;
import com.gs5.kycaml.risk.domain.Evaluation;
import com.gs5.kycaml.risk.domain.RecordedFact;
import com.gs5.kycaml.risk.domain.RiskDomainEvent;
import com.gs5.kycaml.risk.domain.RiskProfileConcurrentChange;
import com.gs5.kycaml.risk.domain.RiskProfileRepository;
import com.gs5.kycaml.risk.domain.RiskProfileSnapshot;
import com.gs5.kycaml.risk.domain.RiskProfileVersion;
import com.gs5.kycaml.risk.domain.RiskTrack;
import com.gs5.kycaml.shared.kernel.Result;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores risk profiles in schema {@code command} with plain SQL: the profile row, the appended
 * history row and the outbox rows in one transaction, so nothing is half-recorded (BR-42).
 *
 * <p>The database settles every race: a first evaluation is an {@code INSERT ... ON CONFLICT DO
 * NOTHING} on the unique (booking centre, customer number), and a later one is an {@code UPDATE}
 * guarded by the version it read (BR-30). A refusal writes nothing.
 */
@Component
public class RiskProfileSqlAdapter implements RiskProfileRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RiskProfileSqlAdapter.class);
    private static final long FIRST_VERSION = 1L;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public RiskProfileSqlAdapter(JdbcClient jdbc, JsonMapper json, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
    }

    @Override
    public Optional<RiskProfileSnapshot> findOwn(UUID tenantId, CustomerNumber customerNumber) {
        return jdbc.sql("""
                        SELECT id, customer_number, evaluation::text AS evaluation, evaluated_at, decided_track,
                               latest_decision::text AS latest_decision, updated_at, version
                        FROM command.risk_profile
                        WHERE tenant_id = :tenantId
                          AND customer_number = :customerNumber
                        """)
                .param("tenantId", tenantId)
                .param("customerNumber", customerNumber.value())
                .query((rs, rowNumber) -> read(tenantId, rs))
                .optional()
                .flatMap(row -> row);
    }

    @Override
    public Result<RiskProfileVersion, RiskProfileConcurrentChange> save(
            RiskProfileSnapshot snapshot, RecordedFact fact, List<RiskDomainEvent> events) {
        // execute() returns what the callback returns; it is declared nullable only for callbacks
        // that return null, and this one never does.
        return Optional.ofNullable(transaction.execute(status -> saveInTransaction(snapshot, fact, events)))
                .orElseGet(() -> Result.failure(new RiskProfileConcurrentChange()));
    }

    private Result<RiskProfileVersion, RiskProfileConcurrentChange> saveInTransaction(
            RiskProfileSnapshot snapshot, RecordedFact fact, List<RiskDomainEvent> events) {
        var expected = snapshot.version();
        var newVersion = expected == null ? FIRST_VERSION : expected + 1L;
        var written = expected == null ? insert(snapshot) : update(snapshot, expected, newVersion);
        if (written != 1) {
            return Result.failure(new RiskProfileConcurrentChange());
        }
        appendHistory(snapshot, newVersion, fact);
        events.forEach(event -> appendOutbox(snapshot, event));
        return Result.success(new RiskProfileVersion(newVersion));
    }

    private int insert(RiskProfileSnapshot snapshot) {
        return jdbc.sql("""
                        INSERT INTO command.risk_profile
                            (id, tenant_id, customer_number, evaluation, evaluated_at, decided_track,
                             latest_decision, updated_at, version)
                        VALUES (:id, :tenantId, :customerNumber, CAST(:evaluation AS JSONB), :evaluatedAt,
                                CAST(:decidedTrack AS VARCHAR), CAST(:latestDecision AS JSONB), :updatedAt, 1)
                        ON CONFLICT (tenant_id, customer_number) DO NOTHING
                        """)
                .param("id", snapshot.id())
                .param("tenantId", snapshot.tenantId())
                .param("customerNumber", snapshot.customerNumber().value())
                .param("evaluation", write(snapshot.latestEvaluation()))
                .param("evaluatedAt", timestamp(snapshot.evaluatedAt()))
                .param("decidedTrack", nameOf(snapshot.decidedTrack()))
                .param("latestDecision", writeOrNull(snapshot.latestDecision()))
                .param("updatedAt", timestamp(snapshot.updatedAt()))
                .update();
    }

    private int update(RiskProfileSnapshot snapshot, long expected, long newVersion) {
        return jdbc.sql("""
                        UPDATE command.risk_profile
                        SET evaluation = CAST(:evaluation AS JSONB),
                            evaluated_at = :evaluatedAt,
                            decided_track = CAST(:decidedTrack AS VARCHAR),
                            latest_decision = CAST(:latestDecision AS JSONB),
                            updated_at = :updatedAt,
                            version = :newVersion
                        WHERE id = :id
                          AND tenant_id = :tenantId
                          AND version = :expected
                        """)
                .param("evaluation", write(snapshot.latestEvaluation()))
                .param("evaluatedAt", timestamp(snapshot.evaluatedAt()))
                .param("decidedTrack", nameOf(snapshot.decidedTrack()))
                .param("latestDecision", writeOrNull(snapshot.latestDecision()))
                .param("updatedAt", timestamp(snapshot.updatedAt()))
                .param("newVersion", newVersion)
                .param("id", snapshot.id())
                .param("tenantId", snapshot.tenantId())
                .param("expected", expected)
                .update();
    }

    private void appendHistory(RiskProfileSnapshot snapshot, long version, RecordedFact fact) {
        var actorId = switch (fact) {
            case RecordedFact.EvaluationFact evaluation -> evaluation.actorId();
            case RecordedFact.DecisionFact decision -> decision.decision().decidedBy();
        };
        var recordedAt = switch (fact) {
            case RecordedFact.EvaluationFact evaluation -> evaluation.recordedAt();
            case RecordedFact.DecisionFact decision -> decision.decision().decidedAt();
        };
        var kind = fact instanceof RecordedFact.EvaluationFact ? "EVALUATION" : "DECISION";
        jdbc.sql("""
                        INSERT INTO command.risk_profile_history
                            (profile_id, version, fact_kind, fact, actor_id, recorded_at)
                        VALUES (:profileId, :version, :kind, CAST(:fact AS JSONB), :actorId, :recordedAt)
                        """)
                .param("profileId", snapshot.id())
                .param("version", version)
                .param("kind", kind)
                .param("fact", write(OutboxDocuments.historyFact(fact)))
                .param("actorId", actorId)
                .param("recordedAt", timestamp(recordedAt))
                .update();
    }

    private void appendOutbox(RiskProfileSnapshot snapshot, RiskDomainEvent event) {
        jdbc.sql("""
                        INSERT INTO command.outbox
                            (event_id, profile_id, tenant_id, customer_number, aggregate_version, event_type,
                             payload, view, status, attempts, created_at)
                        VALUES (:eventId, :profileId, :tenantId, :customerNumber, :version, :eventType,
                                CAST(:payload AS JSONB), CAST(:view AS JSONB), 'PENDING', 0, :createdAt)
                        """)
                .param("eventId", event.eventId())
                .param("profileId", snapshot.id())
                .param("tenantId", event.tenantId())
                .param("customerNumber", event.customerNumber().value())
                .param("version", event.version().value())
                .param("eventType", OutboxDocuments.eventType(event))
                .param("payload", write(OutboxDocuments.payload(event)))
                .param("view", write(OutboxDocuments.view(snapshot, event)))
                .param("createdAt", timestamp(event.occurredAt()))
                .update();
    }

    /** A stored row that cannot be read back as a valid snapshot is not found, never half-built. */
    private Optional<RiskProfileSnapshot> read(UUID tenantId, ResultSet rs) throws SQLException {
        var number = CustomerNumber.parse(rs.getString("customer_number")).toOptional();
        var decidedTrack = trackOf(rs.getString("decided_track"));
        if (number.isEmpty() || decidedTrack == null && rs.getString("decided_track") != null) {
            return Optional.empty();
        }
        try {
            var evaluation = json.readValue(rs.getString("evaluation"), Evaluation.class);
            var storedDecision = rs.getString("latest_decision");
            var decision = storedDecision == null ? null : json.readValue(storedDecision, DecisionRecord.class);
            return Optional.of(new RiskProfileSnapshot(rs.getObject("id", UUID.class), tenantId, number.get(),
                    evaluation, instant(rs, "evaluated_at"), decidedTrack, decision, rs.getLong("version"),
                    instant(rs, "updated_at")));
        } catch (JacksonException unreadable) {
            LOG.error("Stored risk profile {} cannot be read back and is treated as not found",
                    rs.getObject("id", UUID.class));
            return Optional.empty();
        }
    }

    private static @Nullable RiskTrack trackOf(@Nullable String name) {
        return Arrays.stream(RiskTrack.values()).filter(track -> track.name().equals(name)).findFirst().orElse(null);
    }

    private static @Nullable String nameOf(@Nullable RiskTrack track) {
        return track == null ? null : track.name();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String write(Object document) {
        return json.writeValueAsString(document);
    }

    private @Nullable String writeOrNull(@Nullable Object document) {
        return document == null ? null : write(document);
    }
}
