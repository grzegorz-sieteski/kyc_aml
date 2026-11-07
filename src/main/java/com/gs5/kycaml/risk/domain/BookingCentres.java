package com.gs5.kycaml.risk.domain;

import java.util.Optional;
import java.util.UUID;

/** Outbound port: which jurisdiction a booking centre belongs to (A-16, D-06). */
public interface BookingCentres {

    /** Empty when the booking centre is unknown; the caller is then not identified (BR-01). */
    Optional<Jurisdiction> jurisdictionOf(UUID tenantId);
}
