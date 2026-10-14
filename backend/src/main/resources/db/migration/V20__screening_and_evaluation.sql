-- P4-1: screening and evaluation (DM Entity catalogue "Screening", "Evaluation"; DM Independence
-- and access; roadmap Phase 4 exit criteria).
--
-- What this migration decides, so a reviewer can find it in one place:
--   * A member's rows (member_evaluation_rows) are read by the owner always, by another member or the
--     Chair once they have submitted their own evaluation of that vendor for the first time and the
--     owner's evaluation is submitted, and by the administrator once every member of that vendor has
--     submitted or been excluded. The Observer never reads them. The AI-ingest role has no privilege
--     on member_evaluations or member_evaluation_rows at all: it reaches them only through two
--     SECURITY DEFINER functions that create or re-point rows and return nothing.
--   * Members and the Chair read proposal files, AI grades and citations only with a conflict
--     declaration of "no conflict" for that vendor and without an exclusion.
--   * Screening statuses of a proposal version move by triggers on the screening tables, not by a
--     member updating proposal_versions (that table stays administrator-only).
--   * The evaluation job moves the proposal version accepted -> evaluating -> evaluated by trigger.
--   * A graded AI row that needs evidence has at least one citation at commit time (deferred
--     constraint trigger), so no row is ever stored as graded without evidence.
-- tender_app receives SELECT, INSERT and UPDATE on every new table by default privileges; row-level
-- security below decides what is allowed. DELETE is never granted.

-- ---- audit actions this phase adds (DM updated in the same commit) -----------------------------------
DO $$
DECLARE
  def text;
  cname text;
BEGIN
  SELECT c.conname, pg_get_constraintdef(c.oid) INTO cname, def
    FROM pg_constraint c
   WHERE c.conrelid = 'audit_events'::regclass AND c.contype = 'c'
     AND pg_get_constraintdef(c.oid) LIKE '%tender_created%';
  IF cname IS NULL THEN
    RAISE EXCEPTION 'the audit action constraint was not found';
  END IF;
  def := replace(def, '''category_updated''',
                 '''category_updated'', ''screening_checklist_changed'', ''screening_checks_saved'', ''ai_feedback_sent''');
  EXECUTE format('ALTER TABLE audit_events DROP CONSTRAINT %I', cname);
  EXECUTE format('ALTER TABLE audit_events ADD CONSTRAINT %I %s', cname, def);
END $$;

-- ---- screening -------------------------------------------------------------------------------------------------
CREATE TABLE screening_checklist_items (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id   uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    label       text NOT NULL CHECK (btrim(label) <> '' AND char_length(label) <= 200),
    sort_order  integer NOT NULL DEFAULT 0,
    is_default  boolean NOT NULL DEFAULT false,
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1
);
CREATE INDEX screening_checklist_items_tender ON screening_checklist_items (tender_id, sort_order);
CREATE TRIGGER screening_checklist_items_touch BEFORE INSERT OR UPDATE ON screening_checklist_items
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE screening_checks (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    proposal_version_id uuid NOT NULL REFERENCES proposal_versions (id) ON DELETE RESTRICT,
    checklist_item_id   uuid NOT NULL REFERENCES screening_checklist_items (id) ON DELETE RESTRICT,
    user_id             uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    is_checked          boolean NOT NULL,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    row_version         integer NOT NULL DEFAULT 1,
    UNIQUE (proposal_version_id, checklist_item_id, user_id)
);
CREATE TRIGGER screening_checks_touch BEFORE INSERT OR UPDATE ON screening_checks
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE screening_prechecks (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    -- The producer's own id: a redelivered batch inserts nothing twice.
    source_item_id      uuid NOT NULL UNIQUE,
    proposal_version_id uuid NOT NULL REFERENCES proposal_versions (id) ON DELETE RESTRICT,
    ai_run_id           uuid REFERENCES ai_runs (id) ON DELETE RESTRICT,
    expected_section    text NOT NULL CHECK (btrim(expected_section) <> ''),
    found               boolean NOT NULL,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX screening_prechecks_version ON screening_prechecks (proposal_version_id);

CREATE TABLE screening_recommendations (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    proposal_version_id uuid NOT NULL REFERENCES proposal_versions (id) ON DELETE RESTRICT,
    user_id             uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    recommendation      text NOT NULL CHECK (recommendation IN ('accept', 'reject', 'request_resubmission')),
    reason              text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    row_version         integer NOT NULL DEFAULT 1,
    UNIQUE (proposal_version_id, user_id),
    CHECK (recommendation = 'accept' OR char_length(btrim(coalesce(reason, ''))) >= 1)
);
CREATE TRIGGER screening_recommendations_touch BEFORE INSERT OR UPDATE ON screening_recommendations
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE screening_decisions (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    proposal_version_id uuid NOT NULL REFERENCES proposal_versions (id) ON DELETE RESTRICT,
    decision            text NOT NULL CHECK (decision IN ('accept', 'reject', 'request_resubmission')),
    reason              text,
    decided_by          uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    decided_at          timestamptz NOT NULL DEFAULT now(),
    is_reinstatement    boolean NOT NULL DEFAULT false,
    CHECK (decision = 'accept' OR char_length(btrim(coalesce(reason, ''))) >= 10),
    CHECK (NOT is_reinstatement OR (decision = 'accept' AND char_length(btrim(coalesce(reason, ''))) >= 10))
);
CREATE INDEX screening_decisions_version ON screening_decisions (proposal_version_id, decided_at DESC);

-- ---- evaluation: AI grades and citations -----------------------------------------------------------------
CREATE TABLE ai_grades (
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    -- The producer's own id: a redelivered batch inserts nothing twice.
    source_item_id       uuid NOT NULL UNIQUE,
    ai_run_id            uuid NOT NULL REFERENCES ai_runs (id) ON DELETE RESTRICT,
    proposal_version_id  uuid NOT NULL REFERENCES proposal_versions (id) ON DELETE RESTRICT,
    baseline_id          uuid NOT NULL REFERENCES baselines (id) ON DELETE RESTRICT,
    requirement_id       uuid NOT NULL REFERENCES requirements (id) ON DELETE RESTRICT,
    outcome              text NOT NULL CHECK (outcome IN ('graded', 'failed')),
    failure_code         text CHECK (failure_code IN ('ungrounded', 'invalid_output', 'model_error')),
    grade                text CHECK (grade IN ('fully_compliant', 'partially_compliant', 'non_compliant')),
    non_compliance_reason text CHECK (non_compliance_reason IN ('explicitly_excluded', 'not_addressed')),
    rationale_summary    text,
    rationale            text,
    missing_elements     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(missing_elements) = 'array'),
    confidence           numeric(3, 2) CHECK (confidence BETWEEN 0 AND 1),
    requirement_hash     text NOT NULL CHECK (requirement_hash ~ '^[0-9a-f]{64}$'),
    is_current           boolean NOT NULL DEFAULT true,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CHECK ((outcome = 'failed') = (failure_code IS NOT NULL)),
    CHECK ((outcome = 'graded') = (grade IS NOT NULL)),
    CHECK ((grade = 'non_compliant') = (non_compliance_reason IS NOT NULL)),
    CHECK (grade IS DISTINCT FROM 'partially_compliant' OR jsonb_array_length(missing_elements) > 0)
);
CREATE UNIQUE INDEX ai_grades_one_current ON ai_grades (proposal_version_id, requirement_id)
    WHERE is_current;
CREATE INDEX ai_grades_run ON ai_grades (ai_run_id);

-- Immutable: only is_current may change, and only from true to false.
CREATE FUNCTION ai_grades_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'an AI grade is never deleted' USING ERRCODE = '23000';
  END IF;
  IF to_jsonb(NEW) - 'is_current' IS DISTINCT FROM to_jsonb(OLD) - 'is_current'
     OR (NEW.is_current AND NOT OLD.is_current) THEN
    RAISE EXCEPTION 'an AI grade is immutable; only is_current may change, from true to false'
      USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ai_grades_frozen BEFORE UPDATE OR DELETE ON ai_grades
    FOR EACH ROW EXECUTE FUNCTION ai_grades_immutable();

-- A grade names the requirement and hash of the baseline it was made against, in the run of the
-- evaluation job of this proposal version.
CREATE FUNCTION ai_grades_insert_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM baseline_requirements br
         JOIN baselines b ON b.id = br.baseline_id
         JOIN proposal_versions p ON p.id = NEW.proposal_version_id
         JOIN tender_vendors tv ON tv.id = p.tender_vendor_id
        WHERE br.baseline_id = NEW.baseline_id AND br.requirement_id = NEW.requirement_id
          AND br.content_hash = NEW.requirement_hash AND b.tender_id = tv.tender_id) THEN
    RAISE EXCEPTION 'the requirement and its hash must be those of the tender''s baseline'
      USING ERRCODE = '23000';
  END IF;
  IF NOT EXISTS (
       SELECT 1 FROM ai_runs r JOIN jobs j ON j.id = r.job_id
        WHERE r.id = NEW.ai_run_id AND j.job_type = 'evaluation' AND j.target_id = NEW.proposal_version_id) THEN
    RAISE EXCEPTION 'the run belongs to the evaluation of another proposal version' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ai_grades_check BEFORE INSERT ON ai_grades
    FOR EACH ROW EXECUTE FUNCTION ai_grades_insert_check();

CREATE TABLE grade_citations (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    ai_grade_id         uuid NOT NULL REFERENCES ai_grades (id) ON DELETE RESTRICT,
    citation_no         integer NOT NULL CHECK (citation_no >= 1),
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    passage_id          uuid NOT NULL REFERENCES passages (id) ON DELETE RESTRICT,
    page_no             integer NOT NULL CHECK (page_no >= 1),
    excerpt             text NOT NULL CHECK (btrim(excerpt) <> ''),
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ai_grade_id, citation_no)
);
CREATE INDEX grade_citations_passage ON grade_citations (passage_id);

-- The cited passage belongs to a document version that is a file of the grade's proposal version.
CREATE FUNCTION grade_citations_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'a citation is never changed or deleted' USING ERRCODE = '23000';
  END IF;
  IF NOT EXISTS (
       SELECT 1 FROM passages p WHERE p.id = NEW.passage_id AND p.document_version_id = NEW.document_version_id
          AND p.page_no = NEW.page_no AND position(NEW.excerpt IN p.text) > 0) THEN
    RAISE EXCEPTION 'the passage, its page and the excerpt must be those of the cited document version'
      USING ERRCODE = '23000';
  END IF;
  -- Evidence is attached when the grade is stored, not later.
  IF NOT EXISTS (SELECT 1 FROM ai_grades g WHERE g.id = NEW.ai_grade_id AND g.created_at = now()) THEN
    RAISE EXCEPTION 'a citation is stored with its grade' USING ERRCODE = '23000';
  END IF;
  IF NOT EXISTS (
       SELECT 1 FROM ai_grades g
       JOIN proposal_version_files f ON f.proposal_version_id = g.proposal_version_id
       WHERE g.id = NEW.ai_grade_id AND f.document_version_id = NEW.document_version_id) THEN
    RAISE EXCEPTION 'the cited document is not a file of the graded proposal version'
      USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER grade_citations_guard BEFORE INSERT OR UPDATE OR DELETE ON grade_citations
    FOR EACH ROW EXECUTE FUNCTION grade_citations_check();

-- No row is stored as graded without evidence: at commit, a grade other than not_addressed has at
-- least one citation. The backend inserts a batch of grades and its citations in one transaction.
CREATE FUNCTION ai_grades_need_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.outcome = 'graded' AND NEW.non_compliance_reason IS DISTINCT FROM 'not_addressed'
     AND NOT EXISTS (SELECT 1 FROM grade_citations c WHERE c.ai_grade_id = NEW.id) THEN
    RAISE EXCEPTION 'a graded row needs at least one citation' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ai_grades_evidence AFTER INSERT ON ai_grades
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ai_grades_need_evidence();

CREATE TABLE ai_grade_feedback (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    ai_grade_id uuid NOT NULL REFERENCES ai_grades (id) ON DELETE RESTRICT,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    is_useful   boolean NOT NULL,
    reason_tags text[] NOT NULL DEFAULT '{}'
                CHECK (reason_tags <@ ARRAY['wrong_page', 'missed_evidence', 'wrong_grade',
                                           'unclear_rationale', 'other']::text[]),
    comment     text CHECK (comment IS NULL OR char_length(comment) <= 1000),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    row_version integer NOT NULL DEFAULT 1,
    UNIQUE (ai_grade_id, user_id)
);
CREATE TRIGGER ai_grade_feedback_touch BEFORE INSERT OR UPDATE ON ai_grade_feedback
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- ---- evaluation: the member's own review ------------------------------------------------------------------
CREATE TABLE member_evaluations (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_vendor_id   uuid NOT NULL REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    user_id            uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    status             text NOT NULL DEFAULT 'not_started'
                       CHECK (status IN ('not_started', 'in_progress', 'submitted', 'reopened', 'outdated')),
    first_submitted_at timestamptz,
    submitted_at       timestamptz,
    reopened_at        timestamptz,
    reopened_by        uuid REFERENCES users (id) ON DELETE RESTRICT,
    reopen_reason      text,
    excluded_at        timestamptz,
    excluded_by        uuid REFERENCES users (id) ON DELETE RESTRICT,
    exclusion_reason   text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    row_version        integer NOT NULL DEFAULT 1,
    UNIQUE (tender_vendor_id, user_id),
    CHECK (excluded_at IS NULL OR char_length(btrim(coalesce(exclusion_reason, ''))) >= 10),
    CHECK ((excluded_at IS NULL) = (excluded_by IS NULL)),
    CHECK (reopened_at IS NULL OR char_length(btrim(coalesce(reopen_reason, ''))) >= 10),
    CHECK (status <> 'submitted' OR submitted_at IS NOT NULL)
);
CREATE TRIGGER member_evaluations_touch BEFORE INSERT OR UPDATE ON member_evaluations
    FOR EACH ROW EXECUTE FUNCTION touch_row();

CREATE TABLE member_evaluation_rows (
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    member_evaluation_id uuid NOT NULL REFERENCES member_evaluations (id) ON DELETE RESTRICT,
    requirement_id       uuid NOT NULL REFERENCES requirements (id) ON DELETE RESTRICT,
    ai_grade_id          uuid REFERENCES ai_grades (id) ON DELETE RESTRICT,
    review_state         text NOT NULL DEFAULT 'not_reviewed'
                         CHECK (review_state IN ('not_reviewed', 'agreed', 'overridden')),
    member_grade         text CHECK (member_grade IN ('fully_compliant', 'partially_compliant', 'non_compliant')),
    note                 text,
    needs_clarification  boolean NOT NULL DEFAULT false,
    clarification_note   text,
    outdated_at          timestamptz,
    outdated_reason      text CHECK (outdated_reason IN ('baseline_changed', 'proposal_changed')),
    reviewed_at          timestamptz,
    -- The member's own earlier decision, kept on the row when it becomes Outdated (the audit trail
    -- must not carry grades: the administrator and the Observer read it).
    previous_review_state text,
    previous_member_grade text,
    previous_note         text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    row_version          integer NOT NULL DEFAULT 1,
    UNIQUE (member_evaluation_id, requirement_id),
    CHECK ((review_state = 'not_reviewed') = (member_grade IS NULL)),
    -- An override keeps its justification: at least 10 characters after trimming (UX-EVA-07).
    CHECK (review_state <> 'overridden' OR char_length(btrim(coalesce(note, ''))) >= 10),
    CHECK ((outdated_at IS NULL) = (outdated_reason IS NULL))
);
CREATE INDEX member_evaluation_rows_ai_grade ON member_evaluation_rows (ai_grade_id);
CREATE TRIGGER member_evaluation_rows_touch BEFORE INSERT OR UPDATE ON member_evaluation_rows
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- ---- allowed status changes ---------------------------------------------------------------------------------
INSERT INTO status_transitions (table_name, column_name, from_value, to_value, caused_by) VALUES
 ('member_evaluations', 'status', NULL, 'not_started', 'The vendor''s evaluation job completes'),
 ('member_evaluations', 'status', 'not_started', 'in_progress', 'The member decides the first row'),
 ('member_evaluations', 'status', 'in_progress', 'submitted', 'The member submits after the pre-checks'),
 ('member_evaluations', 'status', 'reopened', 'submitted', 'The member submits again'),
 ('member_evaluations', 'status', 'submitted', 'reopened', 'The Chair reopens with a reason'),
 ('member_evaluations', 'status', 'in_progress', 'outdated', 'A requirement or the proposal changed'),
 ('member_evaluations', 'status', 'submitted', 'outdated', 'A requirement or the proposal changed'),
 ('member_evaluations', 'status', 'outdated', 'in_progress', 'The member decides an outdated row'),
 ('member_evaluation_rows', 'review_state', NULL, 'not_reviewed', 'The evaluation is created'),
 ('member_evaluation_rows', 'review_state', 'not_reviewed', 'agreed', 'The member agrees with the AI grade'),
 ('member_evaluation_rows', 'review_state', 'not_reviewed', 'overridden', 'The member overrides with a note'),
 ('member_evaluation_rows', 'review_state', 'agreed', 'overridden', 'The member changes the decision'),
 ('member_evaluation_rows', 'review_state', 'overridden', 'agreed', 'The member changes the decision'),
 ('member_evaluation_rows', 'review_state', 'agreed', 'not_reviewed', 'The row became Outdated'),
 ('member_evaluation_rows', 'review_state', 'overridden', 'not_reviewed', 'The row became Outdated');
CREATE TRIGGER member_evaluations_status BEFORE INSERT OR UPDATE OF status ON member_evaluations
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');
CREATE TRIGGER member_evaluation_rows_state BEFORE INSERT OR UPDATE OF review_state ON member_evaluation_rows
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('review_state');

-- ---- helper functions ------------------------------------------------------------------------------------------------
-- All SECURITY DEFINER with a fixed search_path, so a policy on one table can read another without
-- being evaluated by that table's own policies (V9 pattern).
CREATE FUNCTION app_tender_of_vendor(tv uuid) RETURNS uuid LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT tender_id FROM tender_vendors WHERE id = tv $$;

CREATE FUNCTION app_tender_of_proposal(pv uuid) RETURNS uuid LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT tv.tender_id FROM proposal_versions p JOIN tender_vendors tv ON tv.id = p.tender_vendor_id
       WHERE p.id = pv $$;

-- Has the signed-in member declared "no conflict" for the vendor and is not excluded from it?
CREATE FUNCTION app_cleared_for_vendor(tv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT app_role() = 'member'
     AND app_is_member_of(app_tender_of_vendor(tv))
     AND EXISTS (SELECT 1 FROM conflict_declarations c
                  WHERE c.tender_vendor_id = tv AND c.user_id = app_user_id() AND NOT c.has_conflict)
     AND NOT EXISTS (SELECT 1 FROM member_evaluations e
                      WHERE e.tender_vendor_id = tv AND e.user_id = app_user_id() AND e.excluded_at IS NOT NULL)
$$;

-- Proposal files and everything derived from a proposal (V9 function, replaced here to add the
-- exclusion rule and to keep one definition of "may read this vendor's proposal").
CREATE OR REPLACE FUNCTION app_can_read_document_version(dv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT CASE
    WHEN app_role() = 'admin' THEN true
    WHEN app_role() <> 'member' OR app_role() IS NULL THEN false
    ELSE EXISTS (
      SELECT 1
      FROM document_versions v
      JOIN documents d ON d.id = v.document_id
      WHERE v.id = dv
        AND d.deleted_at IS NULL
        AND app_is_member_of(d.tender_id)
        AND (d.doc_type <> 'proposal' OR EXISTS (
              SELECT 1
              FROM proposal_version_files f
              JOIN proposal_versions pv ON pv.id = f.proposal_version_id
              WHERE f.document_version_id = v.id
                AND app_cleared_for_vendor(pv.tender_vendor_id))))
  END
$$;

CREATE FUNCTION app_can_read_proposal_version(pv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT CASE
    WHEN app_role() = 'admin' THEN app_can_use_tender(app_tender_of_proposal(pv))
    WHEN app_role() = 'member' THEN
      EXISTS (SELECT 1 FROM proposal_versions p WHERE p.id = pv AND app_cleared_for_vendor(p.tender_vendor_id))
    ELSE false
  END
$$;

-- Every member of the vendor has submitted or been excluded (the administrator's release rule).
CREATE FUNCTION app_all_submitted_or_excluded(tv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT EXISTS (SELECT 1 FROM member_evaluations e WHERE e.tender_vendor_id = tv)
     AND NOT EXISTS (SELECT 1 FROM member_evaluations e
                      WHERE e.tender_vendor_id = tv AND e.excluded_at IS NULL AND e.status <> 'submitted')
$$;

-- AI grades and citations: members with a clearance; the administrator once every evaluation is in.
CREATE FUNCTION app_can_read_grades(pv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT CASE
    WHEN app_role() = 'admin' THEN
      EXISTS (SELECT 1 FROM proposal_versions p WHERE p.id = pv AND app_all_submitted_or_excluded(p.tender_vendor_id))
    WHEN app_role() = 'member' THEN
      EXISTS (SELECT 1 FROM proposal_versions p WHERE p.id = pv AND app_cleared_for_vendor(p.tender_vendor_id))
    ELSE false
  END
$$;

-- A member's rows (grades, notes, flags): see the header of this file.
CREATE FUNCTION app_can_read_member_rows(me uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT EXISTS (
    SELECT 1 FROM member_evaluations o
    WHERE o.id = me
      AND (   o.user_id = app_user_id()
           OR (app_role() = 'member'
               AND app_cleared_for_vendor(o.tender_vendor_id)
               AND o.status = 'submitted'
               AND EXISTS (SELECT 1 FROM member_evaluations mine
                            WHERE mine.tender_vendor_id = o.tender_vendor_id
                              AND mine.user_id = app_user_id() AND mine.first_submitted_at IS NOT NULL))
           OR (app_role() = 'admin' AND app_all_submitted_or_excluded(o.tender_vendor_id))))
$$;

CREATE FUNCTION app_screening_started(t uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT EXISTS (SELECT 1 FROM screening_checks c
                  JOIN proposal_versions p ON p.id = c.proposal_version_id
                  JOIN tender_vendors tv ON tv.id = p.tender_vendor_id WHERE tv.tender_id = t)
      OR EXISTS (SELECT 1 FROM screening_recommendations r
                  JOIN proposal_versions p ON p.id = r.proposal_version_id
                  JOIN tender_vendors tv ON tv.id = p.tender_vendor_id WHERE tv.tender_id = t)
$$;

REVOKE ALL ON FUNCTION app_tender_of_vendor(uuid), app_tender_of_proposal(uuid), app_cleared_for_vendor(uuid),
    app_can_read_proposal_version(uuid), app_all_submitted_or_excluded(uuid), app_can_read_grades(uuid),
    app_can_read_member_rows(uuid), app_screening_started(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_tender_of_vendor(uuid), app_tender_of_proposal(uuid), app_cleared_for_vendor(uuid),
    app_can_read_proposal_version(uuid), app_all_submitted_or_excluded(uuid), app_can_read_grades(uuid),
    app_can_read_member_rows(uuid), app_screening_started(uuid) TO tender_app;

-- ---- triggers: screening ----------------------------------------------------------------------------------------
-- The five default checklist items (UX-SCR-02) for every new tender, and for the existing ones.
CREATE FUNCTION screening_seed_checklist() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  INSERT INTO screening_checklist_items (tender_id, label, sort_order, is_default) VALUES
    (NEW.id, 'All mandatory documents are present', 1, true),
    (NEW.id, 'Signed where required', 2, true),
    (NEW.id, 'In the required language', 3, true),
    (NEW.id, 'Within any page limit', 4, true),
    (NEW.id, 'No financial information is included', 5, true);
  RETURN NEW;
END $$;
CREATE TRIGGER tenders_seed_checklist AFTER INSERT ON tenders
    FOR EACH ROW EXECUTE FUNCTION screening_seed_checklist();
INSERT INTO screening_checklist_items (tender_id, label, sort_order, is_default)
SELECT t.id, d.label, d.sort_order, true
  FROM tenders t
 CROSS JOIN (VALUES ('All mandatory documents are present', 1), ('Signed where required', 2),
                    ('In the required language', 3), ('Within any page limit', 4),
                    ('No financial information is included', 5)) AS d (label, sort_order);

-- Items can be edited by the Chair before screening starts and are never deleted.
CREATE FUNCTION screening_checklist_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a checklist item is deactivated, never deleted' USING ERRCODE = '23000';
  END IF;
  IF current_user = 'tender_owner' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' AND NEW.tender_id <> OLD.tender_id THEN
    RAISE EXCEPTION 'a checklist item stays with its tender' USING ERRCODE = '23000';
  END IF;
  IF NOT app_is_chair_of(NEW.tender_id) THEN
    RAISE EXCEPTION 'only the Chair edits the checklist' USING ERRCODE = '23000';
  END IF;
  IF app_screening_started(NEW.tender_id) THEN
    RAISE EXCEPTION 'the checklist is locked once screening has started' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER screening_checklist_items_guard BEFORE INSERT OR UPDATE OR DELETE ON screening_checklist_items
    FOR EACH ROW EXECUTE FUNCTION screening_checklist_guard();

-- The first checklist item or recommendation moves the proposal to in_screening.
CREATE FUNCTION screening_start() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  UPDATE proposal_versions SET status = 'in_screening'
   WHERE id = NEW.proposal_version_id AND status = 'uploaded';
  RETURN NEW;
END $$;
CREATE TRIGGER screening_checks_start AFTER INSERT ON screening_checks
    FOR EACH ROW EXECUTE FUNCTION screening_start();
CREATE TRIGGER screening_recommendations_start AFTER INSERT ON screening_recommendations
    FOR EACH ROW EXECUTE FUNCTION screening_start();

-- A decision is the Chair's, appended, never changed; it moves the proposal version.
CREATE FUNCTION screening_decisions_guard() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  t uuid;
  st text;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'screening decisions are appended, never changed' USING ERRCODE = '23000';
  END IF;
  SELECT tv.tender_id, p.status INTO t, st
    FROM proposal_versions p JOIN tender_vendors tv ON tv.id = p.tender_vendor_id
   WHERE p.id = NEW.proposal_version_id;
  IF t IS NULL OR NOT EXISTS (SELECT 1 FROM tender_members m WHERE m.tender_id = t
        AND m.user_id = NEW.decided_by AND m.is_chair AND m.status = 'active') THEN
    RAISE EXCEPTION 'only the Chair of the tender confirms a screening decision' USING ERRCODE = '23000';
  END IF;
  IF NEW.is_reinstatement THEN
    IF st <> 'rejected' THEN
      RAISE EXCEPTION 'only a rejected vendor is reinstated' USING ERRCODE = '23000';
    END IF;
  ELSIF st <> 'in_screening' THEN
    RAISE EXCEPTION 'a decision needs a proposal that is in screening' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER screening_decisions_check BEFORE INSERT OR UPDATE OR DELETE ON screening_decisions
    FOR EACH ROW EXECUTE FUNCTION screening_decisions_guard();

CREATE FUNCTION screening_decisions_apply() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  UPDATE proposal_versions SET status = CASE NEW.decision
        WHEN 'accept' THEN 'accepted' WHEN 'reject' THEN 'rejected' ELSE 'resubmission_requested' END
   WHERE id = NEW.proposal_version_id;
  RETURN NEW;
END $$;
CREATE TRIGGER screening_decisions_move AFTER INSERT ON screening_decisions
    FOR EACH ROW EXECUTE FUNCTION screening_decisions_apply();

-- A member's check or recommendation belongs to one proposal version, one member (and one checklist
-- item of the same tender), and can be written only while the proposal is still being screened.
CREATE FUNCTION app_proposal_open_for_screening(pv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM proposal_versions p WHERE p.id = pv
                      AND p.status IN ('uploaded', 'in_screening')) $$;
REVOKE ALL ON FUNCTION app_proposal_open_for_screening(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_proposal_open_for_screening(uuid) TO tender_app;

CREATE FUNCTION screening_member_rows_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  item uuid;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.proposal_version_id <> OLD.proposal_version_id OR NEW.user_id <> OLD.user_id THEN
      RAISE EXCEPTION 'a screening row stays with its proposal version and member' USING ERRCODE = '23000';
    END IF;
  END IF;
  IF current_user <> 'tender_owner' AND NOT app_proposal_open_for_screening(NEW.proposal_version_id) THEN
    RAISE EXCEPTION 'screening of this proposal version is closed' USING ERRCODE = '23000';
  END IF;
  IF TG_TABLE_NAME = 'screening_checks' THEN
    item := (to_jsonb(NEW) ->> 'checklist_item_id')::uuid;
    IF TG_OP = 'UPDATE' AND item IS DISTINCT FROM (to_jsonb(OLD) ->> 'checklist_item_id')::uuid THEN
      RAISE EXCEPTION 'a check stays with its checklist item' USING ERRCODE = '23000';
    END IF;
    IF (SELECT i.tender_id FROM screening_checklist_items i WHERE i.id = item)
       IS DISTINCT FROM (SELECT tv.tender_id FROM proposal_versions p
                          JOIN tender_vendors tv ON tv.id = p.tender_vendor_id
                         WHERE p.id = NEW.proposal_version_id) THEN
      RAISE EXCEPTION 'the checklist item belongs to another tender' USING ERRCODE = '23000';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER screening_checks_guard BEFORE INSERT OR UPDATE ON screening_checks
    FOR EACH ROW EXECUTE FUNCTION screening_member_rows_guard();
CREATE TRIGGER screening_recommendations_guard BEFORE INSERT OR UPDATE ON screening_recommendations
    FOR EACH ROW EXECUTE FUNCTION screening_member_rows_guard();

-- ---- triggers: the evaluation job moves the proposal version ----------------------------------------------------
CREATE FUNCTION jobs_evaluation_proposal_status() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF NEW.job_type <> 'evaluation' OR NEW.target_type <> 'proposal_version' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'INSERT' THEN
    UPDATE proposal_versions SET status = 'evaluating' WHERE id = NEW.target_id AND status = 'accepted';
  ELSIF NEW.status IN ('completed', 'completed_with_warnings') AND OLD.status <> NEW.status THEN
    UPDATE proposal_versions SET status = 'evaluated'
     WHERE id = NEW.target_id AND status = 'evaluating'
       AND EXISTS (SELECT 1 FROM ai_grades g WHERE g.proposal_version_id = NEW.target_id);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER jobs_evaluation_status AFTER INSERT OR UPDATE OF status ON jobs
    FOR EACH ROW EXECUTE FUNCTION jobs_evaluation_proposal_status();

-- A declared conflict excludes the member from the vendor, also when the evaluation already exists.
CREATE FUNCTION conflict_declared_excludes() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF NEW.has_conflict THEN
    UPDATE member_evaluations
       SET excluded_at = now(), excluded_by = NEW.user_id,
           exclusion_reason = 'Declared a conflict of interest'
     WHERE tender_vendor_id = NEW.tender_vendor_id AND user_id = NEW.user_id AND excluded_at IS NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER conflict_declarations_exclude AFTER INSERT ON conflict_declarations
    FOR EACH ROW EXECUTE FUNCTION conflict_declared_excludes();

-- ---- triggers: the member's evaluation -----------------------------------------------------------------------------
-- Owner submits; only the Chair reopens or excludes, with a reason; first_submitted_at is set once.
-- Writes made inside the SECURITY DEFINER functions below run as the table owner and are the
-- system's own (outdating, exclusion), so they pass this guard.
CREATE FUNCTION member_evaluations_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  t uuid;
  me uuid := app_user_id();
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'an evaluation is never deleted' USING ERRCODE = '23000';
  END IF;
  IF TG_OP = 'INSERT' THEN
    RETURN NEW;  -- created by ingest_sync_member_evaluations only (there is no INSERT policy)
  END IF;
  IF NEW.tender_vendor_id <> OLD.tender_vendor_id OR NEW.user_id <> OLD.user_id THEN
    RAISE EXCEPTION 'an evaluation stays with its member and vendor' USING ERRCODE = '23000';
  END IF;
  IF OLD.first_submitted_at IS NOT NULL AND NEW.first_submitted_at IS DISTINCT FROM OLD.first_submitted_at THEN
    RAISE EXCEPTION 'first_submitted_at is set once' USING ERRCODE = '23000';
  END IF;

  IF current_user <> 'tender_owner' THEN
    t := app_tender_of_vendor(NEW.tender_vendor_id);
    IF NEW.excluded_at IS DISTINCT FROM OLD.excluded_at OR NEW.excluded_by IS DISTINCT FROM OLD.excluded_by
       OR NEW.exclusion_reason IS DISTINCT FROM OLD.exclusion_reason THEN
      -- an exclusion: the Chair, of another member, with a reason, and nothing else changes
      IF OLD.excluded_at IS NOT NULL OR NOT app_is_chair_of(t) OR OLD.user_id = me
         OR NEW.excluded_by IS DISTINCT FROM me THEN
        RAISE EXCEPTION 'only the Chair of the tender excludes another member' USING ERRCODE = '23000';
      END IF;
      IF (to_jsonb(NEW) - ARRAY['excluded_at', 'excluded_by', 'exclusion_reason', 'updated_at', 'row_version'])
         IS DISTINCT FROM
         (to_jsonb(OLD) - ARRAY['excluded_at', 'excluded_by', 'exclusion_reason', 'updated_at', 'row_version']) THEN
        RAISE EXCEPTION 'an exclusion changes nothing else' USING ERRCODE = '23000';
      END IF;
    ELSIF NEW.status = 'reopened' AND OLD.status = 'submitted' THEN
      -- a reopen: the Chair, of another member, and nothing else changes
      IF NOT app_is_chair_of(t) OR OLD.user_id = me OR NEW.reopened_by IS DISTINCT FROM me THEN
        RAISE EXCEPTION 'only the Chair of the tender reopens another member''s evaluation'
          USING ERRCODE = '23000';
      END IF;
      IF (to_jsonb(NEW) - ARRAY['status', 'reopened_at', 'reopened_by', 'reopen_reason', 'updated_at', 'row_version'])
         IS DISTINCT FROM
         (to_jsonb(OLD) - ARRAY['status', 'reopened_at', 'reopened_by', 'reopen_reason', 'updated_at', 'row_version']) THEN
        RAISE EXCEPTION 'a reopen changes nothing else' USING ERRCODE = '23000';
      END IF;
      NEW.reopened_at := now();
    ELSE
      -- the member's own change: the status only (submit), while cleared and not excluded
      IF OLD.user_id <> me OR OLD.excluded_at IS NOT NULL OR NOT app_cleared_for_vendor(NEW.tender_vendor_id) THEN
        RAISE EXCEPTION 'only a cleared member changes their own evaluation' USING ERRCODE = '23000';
      END IF;
      IF NEW.status = 'outdated' AND OLD.status <> 'outdated' THEN
        RAISE EXCEPTION 'an evaluation becomes outdated only by a requirement or proposal change'
          USING ERRCODE = '23000';
      END IF;
      IF (to_jsonb(NEW) - ARRAY['status', 'updated_at', 'row_version'])
         IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'updated_at', 'row_version']) THEN
        RAISE EXCEPTION 'a member changes only the status of their evaluation' USING ERRCODE = '23000';
      END IF;
    END IF;
  END IF;

  IF NEW.status = 'submitted' AND OLD.status <> 'submitted' THEN
    NEW.submitted_at := now();
    IF NEW.first_submitted_at IS NULL THEN
      NEW.first_submitted_at := now();
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER member_evaluations_check BEFORE INSERT OR UPDATE OR DELETE ON member_evaluations
    FOR EACH ROW EXECUTE FUNCTION member_evaluations_guard();

-- Rows: the member decides them while the evaluation is not submitted; an "agreed" row carries the
-- AI grade; deciding an outdated row clears the outdated mark.
CREATE FUNCTION member_evaluation_rows_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ev member_evaluations%ROWTYPE;
  ai text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'an evaluation row is never deleted' USING ERRCODE = '23000';
  END IF;
  IF TG_OP = 'INSERT' THEN
    RETURN NEW;  -- created by ingest_sync_member_evaluations only (there is no INSERT policy)
  END IF;
  IF NEW.review_state = 'agreed' THEN
    SELECT g.grade INTO ai FROM ai_grades g WHERE g.id = NEW.ai_grade_id;
    IF ai IS NULL OR NEW.member_grade IS DISTINCT FROM ai THEN
      RAISE EXCEPTION 'agreeing takes the AI grade; there is none to agree with' USING ERRCODE = '23000';
    END IF;
  END IF;
  IF current_user <> 'tender_owner' THEN
    SELECT * INTO ev FROM member_evaluations WHERE id = NEW.member_evaluation_id;
    IF ev.status = 'submitted' OR ev.excluded_at IS NOT NULL THEN
      RAISE EXCEPTION 'a submitted evaluation is read only' USING ERRCODE = '23000';
    END IF;
    -- these are the member's to change; the requirement, the AI grade and the system marks are not
    IF (to_jsonb(NEW) - ARRAY['review_state', 'member_grade', 'note', 'needs_clarification',
                              'clarification_note', 'updated_at', 'row_version'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['review_state', 'member_grade', 'note', 'needs_clarification',
                              'clarification_note', 'updated_at', 'row_version']) THEN
      RAISE EXCEPTION 'a member changes only the decision, the note and the flags of a row'
        USING ERRCODE = '23000';
    END IF;
  END IF;
  -- Deciding a row clears its Outdated mark.
  IF OLD.review_state = 'not_reviewed' AND NEW.review_state <> 'not_reviewed' THEN
    NEW.outdated_at := NULL;
    NEW.outdated_reason := NULL;
    NEW.reviewed_at := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER member_evaluation_rows_check BEFORE INSERT OR UPDATE OR DELETE ON member_evaluation_rows
    FOR EACH ROW EXECUTE FUNCTION member_evaluation_rows_guard();

-- The first decided row starts the evaluation; deciding an outdated row resumes it.
CREATE FUNCTION member_evaluation_rows_progress() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF OLD.review_state = 'not_reviewed' AND NEW.review_state <> 'not_reviewed' THEN
    UPDATE member_evaluations SET status = 'in_progress'
     WHERE id = NEW.member_evaluation_id AND status IN ('not_started', 'outdated');
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER member_evaluation_rows_progress AFTER UPDATE OF review_state ON member_evaluation_rows
    FOR EACH ROW EXECUTE FUNCTION member_evaluation_rows_progress();

-- ---- functions that write other people's rows (return counts, never grades) -------------------------------
-- Called by the result handler when a vendor's evaluation job completes (AI-ingest connection), and
-- again after a re-run: creates the missing evaluations (every active member of the tender, excluded
-- when they declared a conflict) and the missing rows (one per requirement with a current AI grade),
-- and re-points rows to the current AI grade. It reads and writes member tables as the owner and
-- returns only how many evaluations and rows were created.
CREATE FUNCTION ingest_sync_member_evaluations(p_proposal_version uuid)
RETURNS TABLE (evaluations_created integer, rows_created integer, rows_repointed integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  tv uuid;
  t uuid;
  n_eval integer;
  n_rows integer;
  n_point integer;
BEGIN
  -- Only the vendor's live proposal version: an older one passed in would move rows back.
  SELECT p.tender_vendor_id, x.tender_id INTO tv, t
    FROM proposal_versions p JOIN tender_vendors x ON x.id = p.tender_vendor_id
   WHERE p.id = p_proposal_version AND p.status <> 'superseded';
  IF tv IS NULL THEN
    RAISE EXCEPTION 'unknown or superseded proposal version' USING ERRCODE = '23503';
  END IF;

  WITH ins AS (
    INSERT INTO member_evaluations (tender_vendor_id, user_id, excluded_at, excluded_by, exclusion_reason)
    SELECT tv, m.user_id,
           CASE WHEN c.has_conflict THEN now() END,
           CASE WHEN c.has_conflict THEN m.user_id END,
           CASE WHEN c.has_conflict THEN 'Declared a conflict of interest' END
      FROM tender_members m
      LEFT JOIN conflict_declarations c ON c.tender_vendor_id = tv AND c.user_id = m.user_id
     WHERE m.tender_id = t AND m.status = 'active'
    ON CONFLICT (tender_vendor_id, user_id) DO NOTHING
    RETURNING 1)
  SELECT count(*) INTO n_eval FROM ins;

  -- Rows for requirements that have none yet; an evaluation that gains a row has work to do again.
  WITH ins AS (
    INSERT INTO member_evaluation_rows (member_evaluation_id, requirement_id, ai_grade_id)
    SELECT e.id, g.requirement_id, g.id
      FROM member_evaluations e
      JOIN ai_grades g ON g.proposal_version_id = p_proposal_version AND g.is_current
     WHERE e.tender_vendor_id = tv AND e.excluded_at IS NULL
    ON CONFLICT (member_evaluation_id, requirement_id) DO NOTHING
    RETURNING member_evaluation_id)
  SELECT count(*) INTO n_rows FROM ins;
  UPDATE member_evaluations e SET status = 'outdated'
   WHERE e.tender_vendor_id = tv AND e.status IN ('in_progress', 'submitted')
     AND EXISTS (SELECT 1 FROM member_evaluation_rows r WHERE r.member_evaluation_id = e.id
                  AND r.review_state = 'not_reviewed' AND r.outdated_at IS NULL
                  AND r.created_at >= now());

  -- A re-run replaced some grades: the rows follow the current one, except in a submitted
  -- evaluation (it is outdated first, by app_mark_rows_outdated). An agreed row cannot keep a
  -- grade that no longer matches, so it returns to not reviewed, with its earlier decision kept.
  WITH moved AS (
    UPDATE member_evaluation_rows r
       SET ai_grade_id = g.id,
           previous_review_state = CASE WHEN r.review_state = 'agreed' THEN r.review_state ELSE r.previous_review_state END,
           previous_member_grade = CASE WHEN r.review_state = 'agreed' THEN r.member_grade ELSE r.previous_member_grade END,
           previous_note = CASE WHEN r.review_state = 'agreed' THEN r.note ELSE r.previous_note END,
           review_state = CASE WHEN r.review_state = 'agreed' THEN 'not_reviewed' ELSE r.review_state END,
           member_grade = CASE WHEN r.review_state = 'agreed' THEN NULL ELSE r.member_grade END,
           outdated_at = CASE WHEN r.review_state = 'agreed' THEN now() ELSE r.outdated_at END,
           outdated_reason = CASE WHEN r.review_state = 'agreed' THEN 'proposal_changed' ELSE r.outdated_reason END
      FROM member_evaluations e, ai_grades g
     WHERE r.member_evaluation_id = e.id AND e.tender_vendor_id = tv AND e.status <> 'submitted'
       AND g.proposal_version_id = p_proposal_version AND g.is_current
       AND g.requirement_id = r.requirement_id AND r.ai_grade_id IS DISTINCT FROM g.id
    RETURNING 1)
  SELECT count(*) INTO n_point FROM moved;

  RETURN QUERY SELECT n_eval, n_rows, n_point;
END $$;

-- Marks rows Outdated (DM How Outdated is detected). A new baseline outdates rows of the changed
-- requirements for every evaluation of every vendor of the tender (the Chair confirms it); a new
-- proposal version outdates every row of that vendor (the administrator, with a reason). The
-- caller's earlier decision is written to audit_events here, because nobody else may read it.
CREATE FUNCTION app_mark_rows_outdated(
    p_tender_vendor uuid, p_reason text, p_requirement_ids uuid[], p_note text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  t uuid := app_tender_of_vendor(p_tender_vendor);
  live uuid;
  current_pv uuid;
  n integer := 0;
  r record;
BEGIN
  IF t IS NULL THEN
    RAISE EXCEPTION 'unknown vendor' USING ERRCODE = '23503';
  END IF;
  IF p_reason = 'baseline_changed' AND NOT (app_role() = 'member' AND app_is_chair_of(t)) THEN
    RAISE EXCEPTION 'only the Chair of the tender outdates rows after a baseline change' USING ERRCODE = '23000';
  ELSIF p_reason = 'proposal_changed' AND NOT (app_role() = 'admin') THEN
    RAISE EXCEPTION 'only the administrator outdates rows after a proposal change' USING ERRCODE = '23000';
  ELSIF p_reason NOT IN ('baseline_changed', 'proposal_changed') THEN
    RAISE EXCEPTION 'unknown reason' USING ERRCODE = '23514';
  END IF;
  IF char_length(btrim(coalesce(p_note, ''))) < 10 THEN
    RAISE EXCEPTION 'a note of at least 10 characters is required' USING ERRCODE = '23514';
  END IF;
  SELECT b.id INTO live FROM baselines b WHERE b.tender_id = t AND b.status = 'confirmed';
  SELECT pv.id INTO current_pv FROM proposal_versions pv
   WHERE pv.tender_vendor_id = p_tender_vendor AND pv.status <> 'superseded';

  -- Only rows that really changed: the requirement text of the confirmed baseline differs from the
  -- one graded, or the grade belongs to a proposal version that is no longer the vendor's current one.
  FOR r IN
    SELECT w.id, w.review_state, w.member_grade, w.note, e.id AS eval_id
      FROM member_evaluation_rows w
      JOIN member_evaluations e ON e.id = w.member_evaluation_id
      JOIN ai_grades g ON g.id = w.ai_grade_id
     WHERE e.tender_vendor_id = p_tender_vendor
       AND (p_requirement_ids IS NULL OR w.requirement_id = ANY (p_requirement_ids))
       AND w.outdated_at IS NULL
       AND ((p_reason = 'baseline_changed' AND live IS NOT NULL AND EXISTS (
                SELECT 1 FROM baseline_requirements br
                 WHERE br.baseline_id = live AND br.requirement_id = w.requirement_id
                   AND br.content_hash <> g.requirement_hash))
            OR (p_reason = 'proposal_changed' AND current_pv IS NOT NULL
                AND g.proposal_version_id <> current_pv))
     FOR UPDATE OF w
  LOOP
    UPDATE member_evaluation_rows
       SET outdated_at = now(), outdated_reason = p_reason,
           previous_review_state = r.review_state, previous_member_grade = r.member_grade,
           previous_note = r.note,
           review_state = 'not_reviewed', member_grade = NULL
     WHERE id = r.id;
    -- The audit event carries no grade: the administrator and the Observer read audit_events.
    INSERT INTO audit_events (actor_user_id, actor_role, source, tender_id, tender_vendor_id, object_type,
                              object_id, action, after, note)
    VALUES (app_user_id(), CASE WHEN app_role() = 'admin' THEN 'admin' ELSE 'chair' END, 'person', t,
            p_tender_vendor, 'member_evaluation_row', r.id, 'rows_marked_outdated',
            jsonb_build_object('outdated_reason', p_reason), p_note);
    n := n + 1;
  END LOOP;

  IF n > 0 THEN
    UPDATE member_evaluations SET status = 'outdated'
     WHERE tender_vendor_id = p_tender_vendor AND status IN ('in_progress', 'submitted');
  END IF;
  RETURN n;
END $$;

REVOKE ALL ON FUNCTION ingest_sync_member_evaluations(uuid), app_mark_rows_outdated(uuid, text, uuid[], text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ingest_sync_member_evaluations(uuid) TO tender_ai_ingest;
GRANT EXECUTE ON FUNCTION app_mark_rows_outdated(uuid, text, uuid[], text) TO tender_app;

-- ---- row-level security -----------------------------------------------------------------------------------------------
ALTER TABLE screening_checklist_items  ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_checks           ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_prechecks        ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_recommendations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_decisions        ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_grades                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE grade_citations            ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_grade_feedback          ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_evaluations         ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_evaluation_rows     ENABLE ROW LEVEL SECURITY;

-- Checklist: the committee and the administrator read; the Chair edits (the trigger adds "not started").
CREATE POLICY checklist_read ON screening_checklist_items FOR SELECT TO tender_app
    USING (app_can_use_tender(tender_id));
CREATE POLICY checklist_insert ON screening_checklist_items FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id));
CREATE POLICY checklist_update ON screening_checklist_items FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND app_is_chair_of(tender_id))
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id));

-- Checks and recommendations: the committee reads (not the administrator, who reads decisions and
-- progress only); a member writes their own for a vendor they are cleared for.
CREATE POLICY checks_read ON screening_checks FOR SELECT TO tender_app
    USING (app_role() = 'member' AND app_is_member_of(app_tender_of_proposal(proposal_version_id)));
CREATE POLICY checks_insert ON screening_checks FOR INSERT TO tender_app
    WITH CHECK (user_id = app_user_id() AND app_can_read_proposal_version(proposal_version_id)
                AND app_role() = 'member');
CREATE POLICY checks_update ON screening_checks FOR UPDATE TO tender_app
    USING (user_id = app_user_id() AND app_role() = 'member')
    WITH CHECK (user_id = app_user_id() AND app_role() = 'member');

CREATE POLICY recommendations_read ON screening_recommendations FOR SELECT TO tender_app
    USING (app_role() = 'member' AND app_is_member_of(app_tender_of_proposal(proposal_version_id)));
CREATE POLICY recommendations_insert ON screening_recommendations FOR INSERT TO tender_app
    WITH CHECK (user_id = app_user_id() AND app_can_read_proposal_version(proposal_version_id)
                AND app_role() = 'member');
CREATE POLICY recommendations_update ON screening_recommendations FOR UPDATE TO tender_app
    USING (user_id = app_user_id() AND app_role() = 'member')
    WITH CHECK (user_id = app_user_id() AND app_role() = 'member');

-- Pre-checks describe the proposal's structure: read like the proposal itself.
CREATE POLICY prechecks_read ON screening_prechecks FOR SELECT TO tender_app
    USING (app_can_read_proposal_version(proposal_version_id));

-- Decisions: the committee and the administrator read; the Chair inserts (trigger checks the Chair).
CREATE POLICY decisions_read ON screening_decisions FOR SELECT TO tender_app
    USING (app_can_use_tender(app_tender_of_proposal(proposal_version_id)));
CREATE POLICY decisions_insert ON screening_decisions FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND decided_by = app_user_id()
                AND app_is_chair_of(app_tender_of_proposal(proposal_version_id)));

-- AI grades and citations: written by the AI-ingest connection only.
CREATE POLICY ai_grades_read ON ai_grades FOR SELECT TO tender_app
    USING (app_can_read_grades(proposal_version_id));
CREATE POLICY grade_citations_read ON grade_citations FOR SELECT TO tender_app
    USING (EXISTS (SELECT 1 FROM ai_grades g WHERE g.id = ai_grade_id));

-- Feedback: only the member who gave it.
CREATE POLICY feedback_read ON ai_grade_feedback FOR SELECT TO tender_app
    USING (user_id = app_user_id() AND app_role() = 'member');
CREATE POLICY feedback_insert ON ai_grade_feedback FOR INSERT TO tender_app
    WITH CHECK (user_id = app_user_id() AND app_role() = 'member'
                AND EXISTS (SELECT 1 FROM ai_grades g WHERE g.id = ai_grade_id));
CREATE POLICY feedback_update ON ai_grade_feedback FOR UPDATE TO tender_app
    USING (user_id = app_user_id() AND app_role() = 'member')
    WITH CHECK (user_id = app_user_id() AND app_role() = 'member');

-- Evaluations (status and dates only: the progress matrix): the committee and the administrator.
-- Updates: the owner (submit) or the Chair (reopen, exclude); the trigger decides which columns.
CREATE POLICY member_evaluations_read ON member_evaluations FOR SELECT TO tender_app
    USING (app_can_use_tender(app_tender_of_vendor(tender_vendor_id)));
CREATE POLICY member_evaluations_update ON member_evaluations FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND (user_id = app_user_id()
                                      OR app_is_chair_of(app_tender_of_vendor(tender_vendor_id))))
    WITH CHECK (app_role() = 'member' AND (user_id = app_user_id()
                                           OR app_is_chair_of(app_tender_of_vendor(tender_vendor_id))));

-- Rows (grades, notes, flags): see app_can_read_member_rows. Only the owner writes, and only while
-- the evaluation is not submitted. Nobody inserts directly: ingest_sync_member_evaluations does.
CREATE POLICY member_rows_read ON member_evaluation_rows FOR SELECT TO tender_app
    USING (app_can_read_member_rows(member_evaluation_id));
CREATE POLICY member_rows_update ON member_evaluation_rows FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND EXISTS (
             SELECT 1 FROM member_evaluations e WHERE e.id = member_evaluation_id
              AND e.user_id = app_user_id() AND e.status <> 'submitted' AND e.excluded_at IS NULL
              AND app_cleared_for_vendor(e.tender_vendor_id)))
    WITH CHECK (app_role() = 'member' AND EXISTS (
             SELECT 1 FROM member_evaluations e WHERE e.id = member_evaluation_id
              AND e.user_id = app_user_id() AND e.status <> 'submitted' AND e.excluded_at IS NULL
              AND app_cleared_for_vendor(e.tender_vendor_id)));

-- ---- the AI-ingest connection ---------------------------------------------------------------------------------------------
-- It stores what the agent produced: pre-check items, grades, citations. It reads its own output (to
-- swap is_current and to check counts) and the facts it needs to validate a batch. It has no
-- privilege on any member table; the only way to those is ingest_sync_member_evaluations, which
-- returns counts.
GRANT SELECT ON ai_grades, grade_citations, screening_prechecks, proposal_versions, tender_vendors,
                proposal_version_files, requirements, baseline_requirements TO tender_ai_ingest;
CREATE POLICY ingest_read_baseline_requirements ON baseline_requirements FOR SELECT TO tender_ai_ingest
    USING (true);
GRANT INSERT ON ai_grades, grade_citations, screening_prechecks TO tender_ai_ingest;
GRANT UPDATE (is_current) ON ai_grades TO tender_ai_ingest;
CREATE POLICY ingest_read_grades ON ai_grades FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_grades ON ai_grades FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_update_grades ON ai_grades FOR UPDATE TO tender_ai_ingest USING (true) WITH CHECK (true);
CREATE POLICY ingest_read_citations ON grade_citations FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_citations ON grade_citations FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_read_prechecks ON screening_prechecks FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_prechecks ON screening_prechecks FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_read_proposal_versions ON proposal_versions FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_read_tender_vendors ON tender_vendors FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_read_proposal_files ON proposal_version_files FOR SELECT TO tender_ai_ingest USING (true);
