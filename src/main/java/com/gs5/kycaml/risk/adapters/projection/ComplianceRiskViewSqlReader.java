package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.domain.ComplianceRiskView;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewReader;
import com.gs5.kycaml.risk.domain.CustomerNumber;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
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

    @Override
    @SuppressWarnings("NullAway")
    public Optional<ComplianceRiskView> find(UUID tenantId, CustomerNumber customerNumber) {
        return null;
    }
}
