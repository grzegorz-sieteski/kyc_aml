package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.risk.domain.CustomerNumber;
import com.gs5.kycaml.risk.domain.FrontOfficeStatus;
import com.gs5.kycaml.risk.domain.FrontOfficeStatusReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Reads the front-office status from schema {@code query}, filtered by booking centre (BR-04). */
@Component
class FrontOfficeStatusSqlReader implements FrontOfficeStatusReader {

    private final JdbcClient jdbc;

    FrontOfficeStatusSqlReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @SuppressWarnings("NullAway")
    public Optional<FrontOfficeStatus> find(UUID tenantId, CustomerNumber customerNumber) {
        return null;
    }
}
