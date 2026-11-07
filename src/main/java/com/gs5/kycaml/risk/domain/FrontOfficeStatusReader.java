package com.gs5.kycaml.risk.domain;

import java.util.Optional;
import java.util.UUID;

/** Outbound port: the front-office status of a customer, read from the query side. */
public interface FrontOfficeStatusReader {

    /** Empty when the booking centre has no status for the number (BR-04). */
    Optional<FrontOfficeStatus> find(UUID tenantId, CustomerNumber customerNumber);
}
