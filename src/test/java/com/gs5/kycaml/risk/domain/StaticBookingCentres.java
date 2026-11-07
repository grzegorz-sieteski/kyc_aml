package com.gs5.kycaml.risk.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Booking centres known to a test: the three in scope (D-06). */
final class StaticBookingCentres implements BookingCentres {

    static final UUID DE_CENTRE = UUID.fromString("0de00000-0000-0000-0000-00000000000d");
    static final UUID US_CENTRE = UUID.fromString("05000000-0000-0000-0000-000000000005");
    static final UUID GB_CENTRE = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    static final UUID UNKNOWN_CENTRE = UUID.fromString("0ff00000-0000-0000-0000-00000000000f");

    private final Map<UUID, Jurisdiction> centres = Map.of(
            DE_CENTRE, Jurisdiction.DE,
            US_CENTRE, Jurisdiction.US,
            GB_CENTRE, Jurisdiction.GB);

    @Override
    public Optional<Jurisdiction> jurisdictionOf(UUID tenantId) {
        return Optional.ofNullable(centres.get(tenantId));
    }
}
