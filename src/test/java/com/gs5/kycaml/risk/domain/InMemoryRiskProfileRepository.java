package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic stand-in for the {@code command} schema. A fake, not a mock: it reproduces the
 * two behaviours the business rules depend on, the unique key (tenant, customer number) and the
 * version check, and records what was saved so a test can see exactly what was announced.
 */
final class InMemoryRiskProfileRepository implements RiskProfileRepository {

    private record Key(UUID tenantId, String customerNumber) { }

    private final Map<Key, RiskProfileSnapshot> profiles = new ConcurrentHashMap<>();
    private final List<RecordedFact> facts = new CopyOnWriteArrayList<>();
    private final List<RiskDomainEvent> events = new CopyOnWriteArrayList<>();

    private volatile Runnable afterNextRead = () -> { };

    @Override
    public Optional<RiskProfileSnapshot> findOwn(UUID tenantId, CustomerNumber customerNumber) {
        var found = Optional.ofNullable(profiles.get(new Key(tenantId, customerNumber.value())));
        var hook = afterNextRead;
        afterNextRead = () -> { };
        hook.run();
        return found;
    }

    /** Runs once, right after the next read: a change recorded behind the reader's back (BR-30). */
    void afterNextRead(Runnable change) {
        afterNextRead = change;
    }

    @Override
    public synchronized Result<RiskProfileVersion, RiskProfileConcurrentChange> save(
            RiskProfileSnapshot snapshot, RecordedFact fact, List<RiskDomainEvent> announced) {
        var key = new Key(snapshot.tenantId(), snapshot.customerNumber().value());
        var stored = profiles.get(key);
        var expected = snapshot.version();
        if (expected == null ? stored != null : stored == null || !expected.equals(stored.version())) {
            return Result.failure(new RiskProfileConcurrentChange());
        }
        long next = expected == null ? 1L : expected + 1L;
        profiles.put(key, new RiskProfileSnapshot(snapshot.id(), snapshot.tenantId(),
                snapshot.customerNumber(), snapshot.latestEvaluation(), snapshot.decidedTrack(),
                snapshot.latestDecision(), next, snapshot.updatedAt()));
        facts.add(fact);
        events.addAll(announced);
        return Result.success(new RiskProfileVersion(next));
    }

    /** Another change recorded behind a reader's back: the stored version moves by one. */
    synchronized void simulateConcurrentChange(UUID tenantId, String customerNumber) {
        var key = new Key(tenantId, customerNumber);
        var stored = profiles.get(key);
        profiles.put(key, new RiskProfileSnapshot(stored.id(), stored.tenantId(), stored.customerNumber(),
                stored.latestEvaluation(), stored.decidedTrack(), stored.latestDecision(),
                stored.version() + 1L, stored.updatedAt()));
    }

    Optional<RiskProfileSnapshot> stored(UUID tenantId, String customerNumber) {
        return Optional.ofNullable(profiles.get(new Key(tenantId, customerNumber)));
    }

    int profileCount() {
        return profiles.size();
    }

    List<RecordedFact> recordedFacts() {
        return new ArrayList<>(facts);
    }

    List<RiskDomainEvent> announcedEvents() {
        return new ArrayList<>(events);
    }
}
