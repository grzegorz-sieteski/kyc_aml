package com.gs5.kycaml.risk.adapters.events;

import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Relays the transactional outbox to the projector. A batch is claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several relays never take the same row, and the claimed rows
 * of one profile are projected in version order on the partition of that profile.
 */
public class OutboxRelay {

    private final JdbcClient jdbc;
    private final ViewProjector projector;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, ViewProjector projector, TransactionTemplate transaction, Clock clock,
                       int batchSize) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Relays one batch and returns how many events were delivered. */
    @SuppressWarnings("NullAway")
    public int runOnce() {
        return -1;
    }
}
