-- R-17 (design addendum H): TRUNCATE fires no row trigger, so the append-only guard of V1 did not stop
-- it. A statement trigger does, for every role, the schema owner included (BR-29, BR-44).
--
-- The total-score CHECK of design section 9 is deliberately not added: the score lives inside the
-- evaluation document and is already capped at the maximum by Evaluation in the domain; the deviation is
-- recorded in the design addendum.
CREATE TRIGGER trg_risk_profile_history_no_truncate
    BEFORE TRUNCATE
    ON command.risk_profile_history
    FOR EACH STATEMENT
EXECUTE FUNCTION command.refuse_history_change();
