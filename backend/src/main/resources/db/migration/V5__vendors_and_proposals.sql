-- P1-1: vendors, their place in a tender, proposal versions and conflict declarations
-- (DM Entity catalogue, Vendors and proposals).

CREATE TABLE vendors (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    name              text NOT NULL CHECK (btrim(name) <> ''),
    normalized_name   text NOT NULL,
    website           text CHECK (website ~* '^https?://[^\s]+$'),
    normalized_domain text,
    description       text,
    registration_no   text,
    contact_person    text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    row_version       integer NOT NULL DEFAULT 1,
    deleted_at        timestamptz,
    deleted_by        uuid REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX vendors_normalized_name_trgm ON vendors USING gin (normalized_name gin_trgm_ops);
CREATE INDEX vendors_normalized_domain_trgm ON vendors USING gin (normalized_domain gin_trgm_ops);
CREATE TRIGGER vendors_touch BEFORE INSERT OR UPDATE ON vendors FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE tender_vendors (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id   uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    vendor_id   uuid NOT NULL REFERENCES vendors (id) ON DELETE RESTRICT,
    added_by    uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    added_at    timestamptz NOT NULL DEFAULT now(),
    removed_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1,
    UNIQUE (tender_id, vendor_id)
);
CREATE TRIGGER tender_vendors_touch BEFORE INSERT OR UPDATE ON tender_vendors
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE proposal_versions (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_vendor_id uuid NOT NULL REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    version_no       integer NOT NULL CHECK (version_no >= 1),
    status           text NOT NULL DEFAULT 'uploaded'
                     CHECK (status IN ('uploaded', 'in_screening', 'accepted', 'rejected',
                                       'resubmission_requested', 'evaluating', 'evaluated', 'superseded')),
    change_reason    text,
    uploaded_by      uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    uploaded_at      timestamptz NOT NULL DEFAULT now(),
    superseded_at    timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    row_version      integer NOT NULL DEFAULT 1,
    UNIQUE (tender_vendor_id, version_no),
    CHECK ((status = 'superseded') = (superseded_at IS NOT NULL))
);
-- One version per vendor that is not superseded.
CREATE UNIQUE INDEX proposal_versions_one_current ON proposal_versions (tender_vendor_id)
    WHERE status <> 'superseded';
CREATE TRIGGER proposal_versions_touch BEFORE INSERT OR UPDATE ON proposal_versions
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE proposal_version_files (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    proposal_version_id uuid NOT NULL REFERENCES proposal_versions (id) ON DELETE RESTRICT,
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    file_role           text NOT NULL CHECK (file_role IN ('main', 'annex')),
    sort_order          integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (proposal_version_id, document_version_id)
);
-- Each proposal version has exactly one main file (the service adds the first).
CREATE UNIQUE INDEX proposal_version_files_one_main ON proposal_version_files (proposal_version_id)
    WHERE file_role = 'main';
CREATE INDEX proposal_version_files_document ON proposal_version_files (document_version_id);

CREATE TABLE conflict_declarations (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_vendor_id uuid NOT NULL REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    has_conflict     boolean NOT NULL,
    statement        text,
    declared_at      timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tender_vendor_id, user_id),
    CHECK (NOT has_conflict OR (statement IS NOT NULL AND btrim(statement) <> ''))
);
-- Immutable once saved.
CREATE TRIGGER conflict_declarations_immutable BEFORE UPDATE OR DELETE ON conflict_declarations
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
