package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.FrontOfficeStatus;
import com.gs5.kycaml.risk.domain.RiskTrack;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The only writer of schema {@code query}. It upserts the compliance view and the front-office
 * status of one customer, gated by the profile version so a repeated or late projection changes
 * nothing.
 */
@Component
public class ViewProjector {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ViewProjector(JdbcClient jdbc, JsonMapper json, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        // Nested: inside a caller's transaction a failing write rolls back to a savepoint, so it is a
        // failure value and the caller's transaction stays usable; alone it is an ordinary transaction.
        this.transaction = new TransactionTemplate();
        this.transaction.setTransactionManager(transaction.getTransactionManager());
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.clock = clock;
    }

    /**
     * Success is true when the views were written and false when the version gate ignored the
     * projection because the views are already at that version or later (idempotence).
     *
     * <p>Only a failure that belongs to the event itself is a {@link ProjectionFailure}: a view the
     * database refuses ({@link DataIntegrityViolationException}) or a view that cannot be read. Any
     * other database failure, a deadlock or a lock or query timeout above all, says nothing about the
     * event, so it propagates and the relay releases the chunk without counting an attempt (R-13).
     */
    public Result<Boolean, ProjectionFailure> project(Projection projection) {
        try {
            var view = json.readValue(projection.viewJson(), ComplianceRiskView.class);
            var status = view.effectiveTrack() == RiskTrack.GREEN
                    ? FrontOfficeStatus.ACTIVE : FrontOfficeStatus.VERIFICATION_PENDING;
            // execute() is declared nullable only for callbacks that return null; this one never does.
            return Optional.ofNullable(transaction.execute(unused -> write(projection, status)))
                    .map(Result::<Boolean, ProjectionFailure>success)
                    .orElseGet(() -> Result.failure(new ProjectionFailure()));
        } catch (JacksonException | DataIntegrityViolationException theEventsOwnFault) {
            return Result.failure(new ProjectionFailure());
        }
    }

    private boolean write(Projection projection, FrontOfficeStatus status) {
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var written = jdbc.sql("""
                        INSERT INTO query.compliance_risk_view
                            (tenant_id, customer_number, projection_version, view, projected_at)
                        VALUES (:tenantId, :customerNumber, :version, CAST(:view AS JSONB), :now)
                        ON CONFLICT (tenant_id, customer_number) DO UPDATE
                        SET projection_version = EXCLUDED.projection_version,
                            view = EXCLUDED.view,
                            projected_at = EXCLUDED.projected_at
                        WHERE query.compliance_risk_view.projection_version < EXCLUDED.projection_version
                        """)
                .param("tenantId", projection.tenantId())
                .param("customerNumber", projection.customerNumber())
                .param("version", projection.version())
                .param("view", projection.viewJson())
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO query.front_office_status
                            (tenant_id, customer_number, projection_version, status, projected_at)
                        VALUES (:tenantId, :customerNumber, :version, :status, :now)
                        ON CONFLICT (tenant_id, customer_number) DO UPDATE
                        SET projection_version = EXCLUDED.projection_version,
                            status = EXCLUDED.status,
                            projected_at = EXCLUDED.projected_at
                        WHERE query.front_office_status.projection_version < EXCLUDED.projection_version
                        """)
                .param("tenantId", projection.tenantId())
                .param("customerNumber", projection.customerNumber())
                .param("version", projection.version())
                .param("status", status.name())
                .param("now", now)
                .update();
        return written == 1;
    }
}
