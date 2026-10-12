-- P1-1: tender, committee and stage dates (DM Entity catalogue, Tender).

CREATE TABLE tenders (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    name         text NOT NULL CHECK (btrim(name) <> ''),
    reference_no text,
    description  text,
    status       text NOT NULL DEFAULT 'draft'
                 CHECK (status IN ('draft', 'requirements', 'evaluation', 'consolidation', 'finalized')),
    opened_at    timestamptz,
    archived_at  timestamptz,
    created_by   uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    row_version  integer NOT NULL DEFAULT 1,
    deleted_at   timestamptz,
    deleted_by   uuid REFERENCES users (id) ON DELETE RESTRICT
);
-- Names are unique among tenders that are not soft-deleted, ignoring case and outer spaces.
CREATE UNIQUE INDEX tenders_name_unique ON tenders (lower(btrim(name))) WHERE deleted_at IS NULL;
CREATE INDEX tenders_status ON tenders (status) WHERE deleted_at IS NULL;
CREATE TRIGGER tenders_touch BEFORE INSERT OR UPDATE ON tenders FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE tender_members (
    id                      uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id               uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    user_id                 uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    is_chair                boolean NOT NULL DEFAULT false,
    status                  text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'removed')),
    added_at                timestamptz NOT NULL DEFAULT now(),
    removed_at              timestamptz,
    removal_handling        text CHECK (removal_handling IN ('kept_as_history', 'reassigned')),
    reassigned_to_user_id   uuid REFERENCES users (id) ON DELETE RESTRICT,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    row_version             integer NOT NULL DEFAULT 1,
    -- The Chair is an active member.
    CHECK (NOT is_chair OR status = 'active'),
    CHECK ((status = 'removed') = (removed_at IS NOT NULL)),
    CHECK (removal_handling IS NULL OR status = 'removed')
);
CREATE UNIQUE INDEX tender_members_one_active_chair ON tender_members (tender_id)
    WHERE is_chair AND status = 'active';
CREATE UNIQUE INDEX tender_members_one_active_row ON tender_members (tender_id, user_id)
    WHERE status = 'active';
CREATE INDEX tender_members_user_active ON tender_members (user_id) WHERE status = 'active';
CREATE TRIGGER tender_members_touch BEFORE INSERT OR UPDATE ON tender_members
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- An administrator cannot also sit on a committee (UX-TND-02).
CREATE FUNCTION refuse_admin_member() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF NEW.status = 'active' AND EXISTS (SELECT 1 FROM users u WHERE u.id = NEW.user_id AND u.role = 'admin') THEN
    RAISE EXCEPTION 'an administrator cannot be a committee member' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tender_members_no_admin BEFORE INSERT OR UPDATE ON tender_members
    FOR EACH ROW EXECUTE FUNCTION refuse_admin_member();

CREATE TABLE tender_stage_dates (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id         uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    stage             text NOT NULL CHECK (stage IN ('requirements', 'evaluation', 'consolidation')),
    due_date          date NOT NULL,
    reminder_lead_days integer CHECK (reminder_lead_days >= 0),
    last_reminded_at  timestamptz,
    -- A stage left out of a later PUT is marked removed, never deleted (rule 11).
    removed_at        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    row_version       integer NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX tender_stage_dates_one_per_stage ON tender_stage_dates (tender_id, stage)
    WHERE removed_at IS NULL;
CREATE TRIGGER tender_stage_dates_touch BEFORE INSERT OR UPDATE ON tender_stage_dates
    FOR EACH ROW EXECUTE FUNCTION touch_row();
