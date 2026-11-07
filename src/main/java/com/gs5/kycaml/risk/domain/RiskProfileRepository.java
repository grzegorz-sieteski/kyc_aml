package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port: where risk profiles, their history and their events are recorded. */
public interface RiskProfileRepository {

    /**
     * Finds the profile of a customer within the caller's own booking centre (BR-04). Empty when
     * there is none, or when the stored row can no longer be read back as a valid snapshot.
     */
    Optional<RiskProfileSnapshot> findOwn(UUID tenantId, CustomerNumber customerNumber);

    /**
     * Records the new state, the fact that caused it and the events it announces, all or nothing
     * (BR-42). A snapshot whose version is null is inserted at version 1; any other is updated only
     * if the stored version is still the one it carries, and moves one higher. Returns the new
     * version, or the refusal when the stored version moved or the insert collided (BR-30). The
     * database settles the race, not a read before the write.
     */
    Result<RiskProfileVersion, RiskProfileConcurrentChange> save(
            RiskProfileSnapshot snapshot, RecordedFact fact, List<RiskDomainEvent> events);
}
