package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewReader;
import com.gs5.kycaml.risk.domain.CustomerNumber;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

/** Reads the compliance view from schema {@code query}, filtered by booking centre (BR-04). */
@Component
class ComplianceRiskViewSqlReader implements ComplianceRiskViewReader {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ComplianceRiskViewSqlReader(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A stored view that cannot be read back is not found, never half-built. */
    @Override
    public Optional<ComplianceRiskView> find(UUID tenantId, CustomerNumber customerNumber) {
        return jdbc.sql("""
                        SELECT view::text FROM query.compliance_risk_view
                        WHERE tenant_id = :tenantId AND customer_number = :customerNumber
                        """)
                .param("tenantId", tenantId)
                .param("customerNumber", customerNumber.value())
                .query(String.class)
                .optional()
                .flatMap(this::read);
    }

    private Optional<ComplianceRiskView> read(String stored) {
        try {
            return Optional.of(json.readValue(stored, ComplianceRiskView.class));
        } catch (JacksonException unreadable) {
            return Optional.empty();
        }
    }
}
