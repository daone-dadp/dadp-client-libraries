-- DBA installs once in each writable PostgreSQL 17 application database.
-- Grant USAGE, SELECT/INSERT/UPDATE to only the approved application role.
-- Keep this ledger with the business database backup; never truncate it while
-- old request IDs can be resubmitted. No plaintext parameters are stored here.
CREATE SCHEMA dadp_recovery;
REVOKE ALL ON SCHEMA dadp_recovery FROM PUBLIC;
CREATE TABLE dadp_recovery.write_requests (
    principal name NOT NULL DEFAULT current_user,
    scope varchar(64) NOT NULL,
    request_id uuid NOT NULL,
    request_hash bytea NOT NULL CHECK (octet_length(request_hash)=32),
    affected_rows bigint,
    generated_key bigint,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(principal, scope, request_id)
);
REVOKE ALL ON dadp_recovery.write_requests FROM PUBLIC;
ALTER TABLE dadp_recovery.write_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE dadp_recovery.write_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY actor_request_scope ON dadp_recovery.write_requests
    USING(principal=current_user) WITH CHECK(principal=current_user);
-- Example (replace approved_app_role with the site's application role):
-- GRANT USAGE ON SCHEMA dadp_recovery TO approved_app_role;
-- GRANT SELECT,INSERT,UPDATE ON dadp_recovery.write_requests TO approved_app_role;
