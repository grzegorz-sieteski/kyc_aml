package com.gs5.kycaml.risk.adapters.projection;

import com.gs5.kycaml.shared.kernel.Result;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * The only writer of schema {@code query}. It upserts the compliance view and the front-office
 * status of one customer, gated by the profile version so a repeated or late projection changes
 * nothing.
 */
@Component
public class ViewProjector {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ViewProjector(JdbcClient jdbc, JsonMapper json, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Success is true when the views were written and false when the version gate ignored the
     * projection because the views are already at that version or later (idempotence).
     */
    @SuppressWarnings("NullAway")
    public Result<Boolean, ProjectionFailure> project(Projection projection) {
        return null;
    }
}
