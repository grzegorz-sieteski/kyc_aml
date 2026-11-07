package com.gs5.kycaml.risk.adapters.events;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay on a fixed delay. It drains the outbox batch by batch and stops when a batch
 * delivers nothing, so a row that keeps failing cannot keep it busy.
 */
@Component
class ScheduledOutboxRelay {

    private final OutboxRelay relay;

    ScheduledOutboxRelay(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval}")
    void relayPending() {
        while (relay.runOnce() > 0) {
            continue;
        }
    }
}
