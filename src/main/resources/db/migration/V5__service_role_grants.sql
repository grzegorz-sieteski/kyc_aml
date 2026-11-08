-- R-09 (decision D-11, design addendum H). The migrations run as the schema owner; the service connects
-- as ${service_role}, which owns nothing. It may do its work and nothing more: read and write the
-- mutable tables, and only read and add to the history. Owning nothing, it cannot alter a table or
-- switch off the append-only triggers; lacking UPDATE, DELETE and TRUNCATE on the history, it is
-- refused for want of the right before any trigger is reached.
GRANT USAGE ON SCHEMA command, query TO "${service_role}";

GRANT SELECT, INSERT, UPDATE ON command.risk_profile, command.outbox TO "${service_role}";
GRANT SELECT, INSERT ON command.risk_profile_history TO "${service_role}";

GRANT SELECT, INSERT, UPDATE ON query.compliance_risk_view, query.front_office_status TO "${service_role}";
