package com.gs5.kycaml.risk.adapters.config;

import com.gs5.kycaml.risk.domain.Jurisdiction;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.UUID;

/**
 * Which jurisdiction each booking centre is under (D-06): {@code app.booking-centres.<centre id>} is
 * {@code US}, {@code GB} or {@code DE}. A centre that is not listed cannot evaluate anyone.
 */
@ConfigurationProperties(prefix = "app")
record RiskProperties(Map<UUID, Jurisdiction> bookingCentres) {

    RiskProperties {
        bookingCentres = bookingCentres == null ? Map.of() : Map.copyOf(bookingCentres);
    }
}
