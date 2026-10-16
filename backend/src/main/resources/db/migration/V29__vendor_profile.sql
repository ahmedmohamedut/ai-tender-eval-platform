-- UI revamp (vendor registry screens, PG-05, UX-VND-01/03): a fuller vendor profile and an
-- active or inactive status. Additive only: no policy or status-change trigger is touched.
ALTER TABLE vendors
    ADD COLUMN legal_name text,
    ADD COLUMN country    text,
    ADD COLUMN industry   text,
    ADD COLUMN email      text CHECK (email IS NULL OR email ~ '^[^@\s]+@[^@\s]+$'),
    ADD COLUMN phone      text,
    ADD COLUMN address    text,
    ADD COLUMN status     text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive'));
CREATE INDEX vendors_status ON vendors (status);
