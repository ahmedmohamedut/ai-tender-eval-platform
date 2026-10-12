-- Phase 1, P1-1. Conventions of the data model (DM Purpose, scope and data principles) and
-- the identity and settings tables (DM Entity catalogue, Identity and settings).
--
-- Every key is a UUIDv7 (native uuidv7() on PostgreSQL 18). Foreign keys are
-- ON DELETE RESTRICT: nothing is hard-deleted through the application.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Editable tables carry updated_at and row_version. The trigger bumps both, so a
-- writer states the version it loaded in the WHERE clause (UX-REQ-12).
CREATE FUNCTION touch_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  IF TG_OP = 'UPDATE' THEN
    NEW.row_version := OLD.row_version + 1;
  END IF;
  RETURN NEW;
END $$;

-- Tables whose rows never change once written (pages, sections, passages, declarations).
CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'rows of % are immutable', TG_TABLE_NAME USING ERRCODE = '23000';
END $$;

CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    email           text NOT NULL,
    display_name    text NOT NULL,
    role            text NOT NULL CHECK (role IN ('admin', 'member', 'observer')),
    status          text NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'active', 'deactivated')),
    sso_subject     text NOT NULL,
    time_zone       text NOT NULL DEFAULT 'UTC',
    last_sign_in_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    row_version     integer NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX users_email_unique ON users (lower(email));
CREATE UNIQUE INDEX users_sso_subject_unique ON users (sso_subject);
CREATE TRIGGER users_touch BEFORE INSERT OR UPDATE ON users FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE drafts (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    object_type text NOT NULL,
    object_id   uuid NOT NULL,
    field       text NOT NULL,
    body        text NOT NULL,
    saved_at    timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1,
    UNIQUE (user_id, object_type, object_id, field)
);
CREATE TRIGGER drafts_touch BEFORE INSERT OR UPDATE ON drafts FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE requirement_categories (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    name        text NOT NULL UNIQUE,
    sort_order  integer NOT NULL DEFAULT 0,
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1
);
CREATE TRIGGER requirement_categories_touch BEFORE INSERT OR UPDATE ON requirement_categories
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE settings (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    key         text NOT NULL UNIQUE,
    value       text NOT NULL,
    description text NOT NULL DEFAULT '',
    changed_by  uuid REFERENCES users (id) ON DELETE RESTRICT,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1
);
CREATE TRIGGER settings_touch BEFORE INSERT OR UPDATE ON settings FOR EACH ROW EXECUTE FUNCTION touch_row();

-- Business settings an administrator may change without a release (PI 7.7).
INSERT INTO settings (key, value, description) VALUES
    ('note.min_length', '10', 'Shortest justification note, in characters (UX-GEN-04)'),
    ('upload.max_bytes', '104857600', 'Largest upload, 100 MB. The gateway body limit is larger.'),
    ('session.warning_seconds', '120', 'How long before the idle timeout the browser warns (UX-AUTH-04)'),
    ('ai.low_confidence_threshold', '0.6', 'Grades below it are marked low confidence');
