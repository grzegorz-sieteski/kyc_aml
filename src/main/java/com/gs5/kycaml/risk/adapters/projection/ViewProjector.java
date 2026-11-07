package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.FrontOfficeStatus;
import com.gs5.kycaml.risk.domain.RiskTrack;
import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
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
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Success is true when the views were written and false when the version gate ignored the
     * projection because the views are already at that version or later (idempotence).
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
        } catch (JacksonException | DataAccessException unwritable) {
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
