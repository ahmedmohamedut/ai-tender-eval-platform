-- P1-1: audit trail and document access log (DM Audit trail and history), plus the
-- idempotency table PI 2.1 needs and the data model does not list (docs/decisions.md).
--
-- Both logs are append-only and partitioned by month. The application role has INSERT and
-- SELECT only; partitions are created ahead by ensure_monthly_partitions().

CREATE TABLE audit_events (
    id               uuid NOT NULL DEFAULT uuidv7(),
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    actor_user_id    uuid REFERENCES users (id) ON DELETE RESTRICT,
    actor_role       text CHECK (actor_role IN ('admin', 'member', 'observer', 'chair')),
    source           text NOT NULL CHECK (source IN ('person', 'ai', 'system')),
    tender_id        uuid REFERENCES tenders (id) ON DELETE RESTRICT,
    tender_vendor_id uuid REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    object_type      text NOT NULL,
    object_id        uuid,
    action           text NOT NULL CHECK (action IN (
        -- DM Enumerations and status values
        'tender_created', 'tender_updated', 'tender_opened', 'tender_archived', 'committee_changed',
        'user_invited', 'user_role_changed', 'user_deactivated', 'vendor_created', 'vendor_updated',
        'document_uploaded', 'document_version_uploaded', 'proposal_version_uploaded', 'conflict_declared',
        'requirement_created', 'requirement_edited', 'requirement_deleted', 'requirement_restored',
        'requirement_merged', 'requirement_split', 'requirement_reviewed', 'requirement_flag_resolved',
        'scoring_changed', 'baseline_confirmed', 'baseline_reopened', 'screening_recommended',
        'screening_decided', 'screening_reinstated', 'grade_agreed', 'grade_overridden', 'row_flagged',
        'rows_marked_outdated', 'evaluation_submitted', 'evaluation_reopened', 'member_excluded',
        'report_grade_recorded', 'report_finalized', 'report_reopened', 'export_created',
        'ai_run_completed', 'job_status_changed',
        -- Added in Phase 1 and 2: writes the design has no action name for (DM updated in the same commit)
        'user_updated', 'user_reactivated', 'user_invitation_resent', 'setting_changed',
        'tender_duplicated', 'tender_vendor_added', 'document_impact_decided', 'document_retry_requested',
        'job_cancelled', 'job_retried')),
    before           jsonb,
    after            jsonb,
    note             text,
    batch_id         uuid,
    request_id       text,
    PRIMARY KEY (id, occurred_at),
    CHECK ((source = 'person') = (actor_user_id IS NOT NULL)),
    -- Actions that carry a justification of at least 10 characters (DM, Actions that must carry a note).
    CHECK (
        action NOT IN ('requirement_edited', 'requirement_deleted', 'requirement_restored',
                       'grade_overridden', 'screening_reinstated', 'baseline_reopened',
                       'evaluation_reopened', 'report_reopened', 'member_excluded')
        OR char_length(btrim(coalesce(note, ''))) >= 10),
    CHECK (
        action <> 'screening_decided'
        OR coalesce(after ->> 'decision', '') NOT IN ('reject', 'request_resubmission')
        OR char_length(btrim(coalesce(note, ''))) >= 10),
    CHECK (
        action <> 'report_grade_recorded'
        OR coalesce(after ->> 'basis', '') <> 'chair_decision'
        OR char_length(btrim(coalesce(note, ''))) >= 10)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX audit_events_tender ON audit_events (tender_id, occurred_at);
CREATE INDEX audit_events_vendor ON audit_events (tender_vendor_id, occurred_at);
CREATE INDEX audit_events_actor ON audit_events (actor_user_id, occurred_at);
CREATE INDEX audit_events_action ON audit_events (action, occurred_at);
CREATE INDEX audit_events_object ON audit_events (object_type, object_id, occurred_at);

CREATE TABLE document_access_events (
    id                  uuid NOT NULL DEFAULT uuidv7(),
    user_id             uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    tender_vendor_id    uuid REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    opened_at           timestamptz NOT NULL DEFAULT now(),
    watermark_shown     boolean NOT NULL DEFAULT false,
    PRIMARY KEY (id, opened_at)
) PARTITION BY RANGE (opened_at);
CREATE INDEX document_access_events_user ON document_access_events (user_id, opened_at);
CREATE INDEX document_access_events_version ON document_access_events (document_version_id, opened_at);

-- Creates the month partitions of both logs from the current month to months_ahead later,
-- and a default partition. The application role gets no privilege on a partition itself: a
-- query through the parent is checked against the parent (and its policies), so a partition
-- addressed by name would otherwise bypass them.
CREATE FUNCTION ensure_monthly_partitions(months_ahead integer DEFAULT 24) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  parent text;
  col text;
  first_month date := date_trunc('month', now())::date;
  m date;
  part text;
  created integer := 0;
BEGIN
  FOREACH parent IN ARRAY ARRAY['audit_events', 'document_access_events'] LOOP
    FOR i IN 0 .. months_ahead LOOP
      m := (first_month + make_interval(months => i))::date;
      part := format('%s_y%sm%s', parent, to_char(m, 'YYYY'), to_char(m, 'MM'));
      IF to_regclass(part) IS NULL THEN
        EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                       part, parent, m, (m + interval '1 month')::date);
        EXECUTE format('REVOKE ALL ON %I FROM PUBLIC', part);
        EXECUTE format('REVOKE ALL ON %I FROM tender_app', part);
        created := created + 1;
      END IF;
    END LOOP;
    part := parent || '_default';
    IF to_regclass(part) IS NULL THEN
      EXECUTE format('CREATE TABLE %I PARTITION OF %I DEFAULT', part, parent);
      EXECUTE format('REVOKE ALL ON %I FROM tender_app', part);
      created := created + 1;
    END IF;
  END LOOP;
  RETURN created;
END $$;
REVOKE ALL ON FUNCTION ensure_monthly_partitions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ensure_monthly_partitions(integer) TO tender_app;

SELECT ensure_monthly_partitions(24);

-- Append-only for every role: insert and select only (UX-AUD-02).
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events, document_access_events FROM tender_app;

-- Idempotency-Key memory (PI 2.1, 24 hours). Not in the data model's 47 tables.
CREATE TABLE idempotency_keys (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id         uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 200),
    operation       text NOT NULL,
    request_hash    text NOT NULL,
    response_status integer,
    response_body   jsonb,
    location        text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    UNIQUE (user_id, idempotency_key, operation)
);
CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_at);
