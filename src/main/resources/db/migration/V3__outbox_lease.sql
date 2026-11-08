-- A relay claims a batch by leasing it, in a short transaction of its own, and projects it outside any
-- claim. If the relay dies the lease runs out and the rows come back. Both columns are null while a row
-- is free. (ADR addendum "relay connection pressure", candidate 2b.)
ALTER TABLE command.outbox
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN locked_by    UUID;
