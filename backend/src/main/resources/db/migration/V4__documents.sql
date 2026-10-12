-- P1-1: documents, versions, pages, sections and passages (DM Entity catalogue, Documents).

CREATE TABLE documents (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id          uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    doc_type           text NOT NULL CHECK (doc_type IN ('rfp', 'brd', 'addendum', 'other', 'proposal')),
    title              text NOT NULL CHECK (btrim(title) <> ''),
    parent_document_id uuid REFERENCES documents (id) ON DELETE RESTRICT,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    row_version        integer NOT NULL DEFAULT 1,
    deleted_at         timestamptz,
    deleted_by         uuid REFERENCES users (id) ON DELETE RESTRICT,
    -- Only an addendum points at the RFP.
    CHECK (parent_document_id IS NULL OR doc_type = 'addendum')
);
CREATE UNIQUE INDEX documents_one_active_rfp ON documents (tender_id)
    WHERE doc_type = 'rfp' AND deleted_at IS NULL;
CREATE INDEX documents_tender ON documents (tender_id) WHERE deleted_at IS NULL;
CREATE TRIGGER documents_touch BEFORE INSERT OR UPDATE ON documents FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE document_versions (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    document_id    uuid NOT NULL REFERENCES documents (id) ON DELETE RESTRICT,
    version_no     integer NOT NULL CHECK (version_no >= 1),
    file_name      text NOT NULL CHECK (btrim(file_name) <> ''),
    file_format    text NOT NULL CHECK (file_format IN ('pdf', 'docx')),
    size_bytes     bigint CHECK (size_bytes > 0),
    sha256         text CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key    text,
    status         text NOT NULL DEFAULT 'uploading'
                   CHECK (status IN ('uploading', 'processing', 'ready', 'failed')),
    failure_reason text CHECK (failure_reason IN ('password_protected', 'no_selectable_text',
                   'unsupported_format', 'source_unavailable', 'index_unavailable', 'timeout', 'other')),
    page_count     integer CHECK (page_count >= 0),
    uploaded_by    uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    superseded_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    row_version    integer NOT NULL DEFAULT 1,
    deleted_at     timestamptz,
    deleted_by     uuid REFERENCES users (id) ON DELETE RESTRICT,
    UNIQUE (document_id, version_no),
    -- A stored version has its object and hash; failure_reason only on a failed version.
    CHECK (status IN ('uploading', 'failed') OR (storage_key IS NOT NULL AND sha256 IS NOT NULL AND size_bytes IS NOT NULL)),
    CHECK (failure_reason IS NULL OR status = 'failed'),
    CHECK (storage_key IS NULL OR storage_key ~
        '^originals/[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}\.(pdf|docx)$')
);
CREATE INDEX document_versions_document ON document_versions (document_id, version_no DESC);
CREATE INDEX document_versions_status ON document_versions (status) WHERE deleted_at IS NULL;
CREATE TRIGGER document_versions_touch BEFORE INSERT OR UPDATE ON document_versions
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- Pages, sections and passages are written once by the result handler and never change.
-- A different chunking means a new document version (rule 13).
CREATE TABLE document_pages (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    page_no             integer NOT NULL CHECK (page_no >= 1),
    width               numeric(10, 2) NOT NULL CHECK (width > 0),
    height              numeric(10, 2) NOT NULL CHECK (height > 0),
    render_key          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, page_no)
);
CREATE TRIGGER document_pages_immutable BEFORE UPDATE OR DELETE ON document_pages
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TABLE document_sections (
    id                  uuid PRIMARY KEY,
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    parent_section_id   uuid REFERENCES document_sections (id) ON DELETE RESTRICT,
    title               text NOT NULL,
    level               integer NOT NULL CHECK (level >= 1),
    sort_order          integer NOT NULL,
    start_page          integer NOT NULL CHECK (start_page >= 1),
    end_page            integer NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (end_page >= start_page)
);
CREATE INDEX document_sections_version ON document_sections (document_version_id, sort_order);
CREATE TRIGGER document_sections_immutable BEFORE UPDATE OR DELETE ON document_sections
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- The id is uuid5(namespace, '{document_version_id}:{ordinal}') chosen by the worker, so a
-- retried parse yields the same ids; it is also the id of the passage's Qdrant point.
CREATE TABLE passages (
    id                  uuid PRIMARY KEY,
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    section_id          uuid REFERENCES document_sections (id) ON DELETE RESTRICT,
    page_no             integer NOT NULL CHECK (page_no >= 1),
    passage_no          integer NOT NULL CHECK (passage_no >= 1),
    text                text NOT NULL CHECK (text <> ''),
    bbox                jsonb NOT NULL,
    char_start          integer NOT NULL CHECK (char_start >= 0),
    char_end            integer NOT NULL,
    content_hash        text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (char_end >= char_start),
    UNIQUE (document_version_id, page_no, passage_no)
);
CREATE INDEX passages_page ON passages (document_version_id, page_no);
CREATE INDEX passages_text_search ON passages USING gin (to_tsvector('english', text));
CREATE TRIGGER passages_immutable BEFORE UPDATE OR DELETE ON passages
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
