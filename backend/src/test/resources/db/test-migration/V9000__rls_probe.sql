-- Test-only table with a row-level security policy shaped like the real ones
-- (implementation guide 5.3). It exists only on the test classpath; no real schema
-- is created here.
CREATE TABLE rls_probe (
    id       bigserial PRIMARY KEY,
    owner_id text NOT NULL,
    note     text NOT NULL
);
ALTER TABLE rls_probe ENABLE ROW LEVEL SECURITY;

CREATE POLICY rls_probe_read ON rls_probe FOR SELECT USING (
    current_setting('app.role', true) = 'admin'
    OR owner_id = current_setting('app.user_id', true)
);
CREATE POLICY rls_probe_insert ON rls_probe FOR INSERT WITH CHECK (
    owner_id = current_setting('app.user_id', true)
);
