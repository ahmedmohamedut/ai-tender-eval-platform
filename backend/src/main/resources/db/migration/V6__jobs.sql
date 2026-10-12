-- P1-1: jobs, steps and scope (DM Entity catalogue, Jobs and AI runs; DM Jobs).
-- ai_runs arrives with the agent role in Phase 3.
--
-- The jobs table is the record of what ran. The work itself travels through NATS: the
-- backend creates the row, commits, then publishes (rule 4). Workers never touch this table.

CREATE TABLE jobs (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id        uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    job_type         text NOT NULL CHECK (job_type IN ('document_processing', 'requirement_extraction',
                     'evaluation', 'screening_precheck')),
    target_type      text NOT NULL CHECK (target_type IN ('document_version', 'proposal_version', 'tender')),
    target_id        uuid NOT NULL,
    status           text NOT NULL DEFAULT 'queued'
                     CHECK (status IN ('queued', 'running', 'completed', 'completed_with_warnings',
                                       'failed', 'cancelled')),
    started_by       uuid REFERENCES users (id) ON DELETE RESTRICT,
    current_step     text,
    progress_percent integer NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    queued_at        timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    failure_code     text CHECK (failure_code IN ('password_protected', 'no_selectable_text',
                     'unsupported_format', 'source_unavailable', 'index_unavailable', 'llm_unavailable',
                     'no_content_in_scope', 'timeout', 'other')),
    failure_message  text,
    retry_of_job_id  uuid REFERENCES jobs (id) ON DELETE RESTRICT,
    cancelled_by     uuid REFERENCES users (id) ON DELETE RESTRICT,
    scope_hash       text,
    -- Operational columns of the message loop (PI 5.5 to 5.8); not business data.
    last_status_seq  bigint NOT NULL DEFAULT 0,
    last_status_at   timestamptz,
    command_published_at timestamptz,
    result_parts     integer,
    counts           jsonb,
    warnings         jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    row_version      integer NOT NULL DEFAULT 1,
    CHECK ((status IN ('failed')) = (failure_code IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancelled_by IS NOT NULL))
);
-- One open document processing job per document version, so an upload event and a retry
-- command never start two parses of one version.
CREATE UNIQUE INDEX jobs_one_open_processing ON jobs (target_id)
    WHERE job_type = 'document_processing' AND status IN ('queued', 'running');
CREATE INDEX jobs_tender_queued ON jobs (tender_id, queued_at DESC);
CREATE INDEX jobs_open ON jobs (status, last_status_at) WHERE status IN ('queued', 'running');
CREATE INDEX jobs_target ON jobs (target_type, target_id);
CREATE TRIGGER jobs_touch BEFORE INSERT OR UPDATE ON jobs FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE job_steps (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    job_id      uuid NOT NULL REFERENCES jobs (id) ON DELETE RESTRICT,
    step_no     integer NOT NULL CHECK (step_no >= 1),
    name        text NOT NULL,
    status      text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    started_at  timestamptz,
    finished_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1,
    UNIQUE (job_id, step_no)
);
CREATE TRIGGER job_steps_touch BEFORE INSERT OR UPDATE ON job_steps FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE job_scope_items (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    job_id              uuid NOT NULL REFERENCES jobs (id) ON DELETE RESTRICT,
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    section_id          uuid REFERENCES document_sections (id) ON DELETE RESTRICT,
    page_from           integer CHECK (page_from >= 1),
    page_to             integer,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (page_to IS NULL OR page_to >= page_from)
);
CREATE INDEX job_scope_items_job ON job_scope_items (job_id);
