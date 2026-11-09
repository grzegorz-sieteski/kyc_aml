package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.FrontOfficeStatus;
import com.gs5.kycaml.risk.domain.RiskTrack;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptException;
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
     * <p>Only a {@link TransientDataAccessException} (a deadlock, a lock or query timeout) escapes: it
     * says nothing about the event, so the relay releases the chunk without counting an attempt (R-13).
     * Every other database failure is this event's {@link ProjectionFailure} and counts an attempt
     * (R-23): a refused view, but also a persistent failure such as bad SQL or a lost grant, which would
     * otherwise be retried forever without ever being counted or parked. So is a view that cannot be
     * read.
     *
     * <p>The catch names every direct subclass of {@code DataAccessException} on the classpath except
     * the transient one, because there is no {@code throw} in this codebase to rethrow that one from a
     * catch-all. A subclass added by a later Spring release would escape like a transient failure; the
     * relay logs every such release with the exception type, so it would not go unnoticed.
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
        } catch (JacksonException | NonTransientDataAccessException | RecoverableDataAccessException
                 | ScriptException theEventsOwnFault) {
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
