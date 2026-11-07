package com.gs5.kycaml.risk.domain;

import java.util.Optional;
import java.util.UUID;

/** Outbound port: the compliance view of a customer, read from the query side. */
public interface ComplianceRiskViewReader {

    /** Empty when the booking centre has no view for the number (BR-04). */
    Optional<ComplianceRiskView> find(UUID tenantId, CustomerNumber customerNumber);
}
