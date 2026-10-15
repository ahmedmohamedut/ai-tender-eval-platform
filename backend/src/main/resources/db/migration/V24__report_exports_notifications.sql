-- P5-1: the report (versions, final grades, copies of member grades, vendor results), exports and
-- notifications (DM Entity catalogue "Report" and "Notifications"; Independence and access).
--
-- Reads: an in-progress version is read by the administrator and the Chair only, and only for a
-- vendor whose members have all submitted or been excluded (UX-RPT-01). A finalized or superseded
-- version is read by the administrator, the committee and the Observer. Writes: the Chair only,
-- while the version is in progress. A finalized version and its children never change (UX-RPT-08);
-- nothing is deleted. tender_app receives SELECT, INSERT and UPDATE by default privileges; no role
-- but tender_app touches these tables (the AI-ingest role has no privilege on any of them).

-- ---- report_versions ----------------------------------------------------------------------------------------------
CREATE TABLE report_versions (
    id                       uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id                uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    version_no               integer NOT NULL CHECK (version_no >= 1),
    baseline_id              uuid NOT NULL REFERENCES baselines (id) ON DELETE RESTRICT,
    scoring_model_id         uuid NOT NULL REFERENCES scoring_models (id) ON DELETE RESTRICT,
    status                   text NOT NULL DEFAULT 'in_progress'
                             CHECK (status IN ('in_progress', 'finalized', 'superseded')),
    created_by               uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    finalized_by             uuid REFERENCES users (id) ON DELETE RESTRICT,
    finalized_at             timestamptz,
    reopened_from_version_id uuid REFERENCES report_versions (id) ON DELETE RESTRICT,
    reopen_reason            text,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    row_version              integer NOT NULL DEFAULT 1,
    UNIQUE (tender_id, version_no),
    CHECK ((status = 'in_progress') = (finalized_at IS NULL)),
    CHECK ((finalized_at IS NULL) = (finalized_by IS NULL)),
    CHECK ((reopened_from_version_id IS NULL) = (reopen_reason IS NULL)),
    CHECK (reopen_reason IS NULL OR char_length(btrim(reopen_reason)) >= 10)
);
-- At most one in-progress and one finalized version per tender; older ones are superseded.
CREATE UNIQUE INDEX report_versions_one_in_progress ON report_versions (tender_id) WHERE status = 'in_progress';
CREATE UNIQUE INDEX report_versions_one_finalized ON report_versions (tender_id) WHERE status = 'finalized';
CREATE TRIGGER report_versions_touch BEFORE INSERT OR UPDATE ON report_versions
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- ---- report_grades -------------------------------------------------------------------------------------------------
CREATE TABLE report_grades (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    report_version_id uuid NOT NULL REFERENCES report_versions (id) ON DELETE RESTRICT,
    tender_vendor_id  uuid NOT NULL REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    requirement_id    uuid NOT NULL REFERENCES requirements (id) ON DELETE RESTRICT,
    ai_grade_id       uuid REFERENCES ai_grades (id) ON DELETE RESTRICT,
    ai_grade          text CHECK (ai_grade IN ('fully_compliant', 'partially_compliant', 'non_compliant')),
    final_grade       text NOT NULL CHECK (final_grade IN ('fully_compliant', 'partially_compliant', 'non_compliant')),
    basis             text NOT NULL CHECK (basis IN ('all_agree', 'chair_decision')),
    justification     text,
    decided_by        uuid REFERENCES users (id) ON DELETE RESTRICT,
    decided_at        timestamptz,
    has_disagreement  boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    row_version       integer NOT NULL DEFAULT 1,
    UNIQUE (report_version_id, tender_vendor_id, requirement_id),
    CHECK (basis <> 'chair_decision' OR
           (char_length(btrim(coalesce(justification, ''))) >= 10 AND decided_by IS NOT NULL
            AND decided_at IS NOT NULL))
);
CREATE INDEX report_grades_ai_grade ON report_grades (ai_grade_id);
CREATE TRIGGER report_grades_touch BEFORE INSERT OR UPDATE ON report_grades
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- ---- report_member_grades (append-only copies) ----------------------------------------------------------------
CREATE TABLE report_member_grades (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    report_grade_id uuid NOT NULL REFERENCES report_grades (id) ON DELETE RESTRICT,
    user_id         uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    member_grade    text CHECK (member_grade IN ('fully_compliant', 'partially_compliant', 'non_compliant')),
    note            text,
    review_state    text NOT NULL CHECK (review_state IN ('not_reviewed', 'agreed', 'overridden')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (report_grade_id, user_id),
    CHECK ((review_state = 'not_reviewed') = (member_grade IS NULL))
);

-- ---- report_vendor_results (computed once at finalization) -----------------------------------------------------
-- explanation holds the inputs of the score so the screen and the export show the same formula
-- (UX-SCO-03, decision P83); the data model table is updated in the same commit.
CREATE TABLE report_vendor_results (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    report_version_id  uuid NOT NULL REFERENCES report_versions (id) ON DELETE RESTRICT,
    tender_vendor_id   uuid NOT NULL REFERENCES tender_vendors (id) ON DELETE RESTRICT,
    outcome            text NOT NULL CHECK (outcome IN ('evaluated', 'rejected_at_screening')),
    rank               integer CHECK (rank >= 1),
    final_score        numeric(6, 2) CHECK (final_score BETWEEN 0 AND 100),
    compliance_percent numeric(5, 2) CHECK (compliance_percent BETWEEN 0 AND 100),
    mandatory_failures integer NOT NULL DEFAULT 0 CHECK (mandatory_failures >= 0),
    count_fully        integer NOT NULL DEFAULT 0 CHECK (count_fully >= 0),
    count_partially    integer NOT NULL DEFAULT 0 CHECK (count_partially >= 0),
    count_non          integer NOT NULL DEFAULT 0 CHECK (count_non >= 0),
    agreement_level    numeric(5, 2) CHECK (agreement_level BETWEEN 0 AND 100),
    override_count     integer NOT NULL DEFAULT 0 CHECK (override_count >= 0),
    override_percent   numeric(5, 2) CHECK (override_percent BETWEEN 0 AND 100),
    category_scores    jsonb NOT NULL DEFAULT '[]'::jsonb,
    explanation        jsonb NOT NULL DEFAULT '{}'::jsonb,
    reason             text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (report_version_id, tender_vendor_id),
    CHECK (outcome <> 'rejected_at_screening' OR (rank IS NULL AND final_score IS NULL AND reason IS NOT NULL))
);

-- ---- exports ------------------------------------------------------------------------------------------------------
CREATE TABLE exports (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id     uuid REFERENCES tenders (id) ON DELETE RESTRICT,
    export_type   text NOT NULL CHECK (export_type IN ('report', 'requirements_list', 'clarification_list', 'audit_log')),
    source_type   text NOT NULL CHECK (source_type IN ('report_version', 'baseline', 'tender_vendor', 'tender', 'audit_filter')),
    source_id     uuid,
    format        text NOT NULL CHECK (format IN ('pdf', 'xlsx', 'csv')),
    requested_by  uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ready', 'failed')),
    storage_key   text,
    failure_code  text,
    context       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    row_version   integer NOT NULL DEFAULT 1,
    CHECK ((status = 'ready') = (storage_key IS NOT NULL)),
    CHECK (status <> 'failed' OR failure_code IS NOT NULL),
    -- The key is the export's own: nobody can point a row at another person's file.
    CHECK (storage_key IS NULL OR storage_key LIKE 'exports/%/' || id::text || '.' || format)
);
CREATE INDEX exports_tender ON exports (tender_id, created_at DESC);
CREATE INDEX exports_requested_by ON exports (requested_by, created_at DESC);
CREATE TRIGGER exports_touch BEFORE INSERT OR UPDATE ON exports FOR EACH ROW EXECUTE FUNCTION touch_row();

-- ---- notifications ------------------------------------------------------------------------------------------------
CREATE TABLE notifications (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    tender_id         uuid REFERENCES tenders (id) ON DELETE RESTRICT,
    notification_type text NOT NULL CHECK (notification_type IN (
        'added_to_tender', 'tender_opened', 'job_completed', 'job_failed', 'requirements_confirmed',
        'requirements_reopened', 'proposal_screened', 'evaluation_ready', 'evaluation_reopened',
        'new_proposal_version', 'all_members_submitted', 'report_finalized', 'due_date_reminder',
        'manual_reminder', 'conflict_declared', 'resubmission_requested', 'document_added')),
    object_type       text,
    object_id         uuid,
    params            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    read_at           timestamptz,
    email_status      text NOT NULL DEFAULT 'not_requested'
                      CHECK (email_status IN ('not_requested', 'pending', 'sent', 'failed')),
    emailed_at        timestamptz,
    CHECK ((email_status = 'sent') = (emailed_at IS NOT NULL))
);
CREATE INDEX notifications_user_unread ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX notifications_email_pending ON notifications (created_at) WHERE email_status = 'pending';

CREATE TABLE notification_preferences (
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    notification_type text NOT NULL,
    email_enabled     boolean NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, notification_type),
    CHECK (notification_type IN (
        'added_to_tender', 'tender_opened', 'job_completed', 'job_failed', 'requirements_confirmed',
        'requirements_reopened', 'proposal_screened', 'evaluation_ready', 'evaluation_reopened',
        'new_proposal_version', 'all_members_submitted', 'report_finalized', 'due_date_reminder',
        'manual_reminder', 'conflict_declared', 'resubmission_requested', 'document_added'))
);

-- ---- allowed status changes -----------------------------------------------------------------------------------
INSERT INTO status_transitions (table_name, column_name, from_value, to_value, caused_by) VALUES
 ('report_versions', 'status', NULL, 'in_progress', 'The Chair records the first final grade, or reopens the report'),
 ('report_versions', 'status', 'in_progress', 'finalized', 'The Chair finalizes'),
 ('report_versions', 'status', 'finalized', 'superseded', 'The next version is finalized'),
 ('exports', 'status', NULL, 'pending', 'A person asks for an export'),
 ('exports', 'status', 'pending', 'ready', 'The file is written'),
 ('exports', 'status', 'pending', 'failed', 'The file could not be written'),
 ('notifications', 'email_status', NULL, 'not_requested', 'The recipient has not asked for email'),
 ('notifications', 'email_status', NULL, 'pending', 'The recipient asked for email'),
 ('notifications', 'email_status', 'pending', 'sent', 'The mail was delivered to the relay'),
 ('notifications', 'email_status', 'pending', 'failed', 'Delivery failed after retries'),
 ('notifications', 'email_status', 'failed', 'pending', 'An administrator retries');
CREATE TRIGGER report_versions_status BEFORE INSERT OR UPDATE OF status ON report_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');
CREATE TRIGGER exports_status BEFORE INSERT OR UPDATE OF status ON exports
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');
CREATE TRIGGER notifications_email_status BEFORE INSERT OR UPDATE OF email_status ON notifications
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('email_status');

-- ---- helper functions ------------------------------------------------------------------------------------------
-- SECURITY DEFINER with a fixed search_path, so a policy reads another table without that table's own
-- policies (V9 pattern).
CREATE FUNCTION app_tender_of_report(v uuid) RETURNS uuid LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT tender_id FROM report_versions WHERE id = v $$;

CREATE FUNCTION app_tender_of_report_grade(p_grade uuid) RETURNS uuid LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT r.tender_id FROM report_grades g JOIN report_versions r ON r.id = g.report_version_id
       WHERE g.id = p_grade $$;

CREATE FUNCTION app_report_in_progress(v uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM report_versions WHERE id = v AND status = 'in_progress') $$;

-- May the caller read the results of this report version for this vendor (UX-RPT-01)?
CREATE FUNCTION app_can_read_report(v uuid, tv uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT EXISTS (
    SELECT 1 FROM report_versions r
     WHERE r.id = v
       AND CASE
             WHEN r.status IN ('finalized', 'superseded') THEN
               app_role() IN ('admin', 'observer')
               OR (app_role() = 'member' AND app_is_member_of(r.tender_id))
             ELSE
               (app_role() = 'admin' OR (app_role() = 'member' AND app_is_chair_of(r.tender_id)))
               AND (tv IS NULL OR app_all_submitted_or_excluded(tv))
           END)
$$;

-- ---- the Chair's guard on versions -----------------------------------------------------------------------------
CREATE FUNCTION report_versions_guard() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  src report_versions%ROWTYPE;
  stage text;
  strip text[] := ARRAY['status', 'updated_at', 'row_version'];
BEGIN
  SELECT status INTO stage FROM tenders WHERE id = NEW.tender_id;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'in_progress' THEN
      RAISE EXCEPTION 'a report version starts in progress' USING ERRCODE = '23000';
    END IF;
    IF stage <> 'consolidation' THEN
      RAISE EXCEPTION 'a report version opens while the tender is in consolidation' USING ERRCODE = '23000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tender_members m WHERE m.tender_id = NEW.tender_id
                   AND m.user_id = NEW.created_by AND m.is_chair AND m.status = 'active') THEN
      RAISE EXCEPTION 'only the Chair of the tender opens a report version' USING ERRCODE = '23000';
    END IF;
    IF app_user_id() IS NOT NULL AND NEW.created_by <> app_user_id() THEN
      RAISE EXCEPTION 'a version is opened in the caller''s own name' USING ERRCODE = '23000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM baselines b WHERE b.id = NEW.baseline_id AND b.tender_id = NEW.tender_id)
       OR NOT EXISTS (SELECT 1 FROM scoring_models sm WHERE sm.id = NEW.scoring_model_id
                      AND sm.tender_id = NEW.tender_id) THEN
      RAISE EXCEPTION 'the baseline and scoring model belong to the tender' USING ERRCODE = '23000';
    END IF;
    IF NEW.reopened_from_version_id IS NOT NULL THEN
      SELECT * INTO src FROM report_versions WHERE id = NEW.reopened_from_version_id;
      IF NOT FOUND OR src.tender_id <> NEW.tender_id OR src.status <> 'finalized' THEN
        RAISE EXCEPTION 'only a finalized version is reopened' USING ERRCODE = '23000';
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'superseded' THEN
    RAISE EXCEPTION 'a superseded report version is locked' USING ERRCODE = '23000';
  END IF;
  IF OLD.status = 'finalized' THEN
    IF NEW.status <> 'superseded'
       OR (to_jsonb(NEW) - strip) IS DISTINCT FROM (to_jsonb(OLD) - strip) THEN
      RAISE EXCEPTION 'a finalized report version is locked' USING ERRCODE = '23000';
    END IF;
    -- Superseded only by its successor: a finalized report is never left without a replacement.
    IF NOT EXISTS (SELECT 1 FROM report_versions n WHERE n.tender_id = OLD.tender_id
                   AND n.status = 'in_progress' AND n.version_no > OLD.version_no) THEN
      RAISE EXCEPTION 'a finalized version is superseded only by the next version' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
  END IF;
  -- in progress: the identity of the version never changes; finalizing is the Chair's.
  IF (to_jsonb(NEW) - (strip || ARRAY['finalized_by', 'finalized_at']))
     IS DISTINCT FROM (to_jsonb(OLD) - (strip || ARRAY['finalized_by', 'finalized_at'])) THEN
    RAISE EXCEPTION 'a report version keeps its baseline, scoring model and reopen reason' USING ERRCODE = '23000';
  END IF;
  IF NEW.status = 'finalized' THEN
    IF NOT EXISTS (
         SELECT 1 FROM tender_members m WHERE m.tender_id = NEW.tender_id
         AND m.user_id = NEW.finalized_by AND m.is_chair AND m.status = 'active') THEN
      RAISE EXCEPTION 'only the Chair of the tender finalizes the report' USING ERRCODE = '23000';
    END IF;
    IF app_user_id() IS NOT NULL AND NEW.finalized_by <> app_user_id() THEN
      RAISE EXCEPTION 'a report is finalized in the caller''s own name' USING ERRCODE = '23000';
    END IF;
    IF stage <> 'consolidation' THEN
      RAISE EXCEPTION 'a report is finalized while the tender is in consolidation' USING ERRCODE = '23000';
    END IF;
    -- Complete: a result for every vendor with an evaluated proposal, and at least one evaluated.
    IF NOT EXISTS (SELECT 1 FROM report_vendor_results r WHERE r.report_version_id = NEW.id
                   AND r.outcome = 'evaluated')
       OR EXISTS (SELECT 1 FROM proposal_versions p JOIN tender_vendors v ON v.id = p.tender_vendor_id
                   WHERE v.tender_id = NEW.tender_id AND p.status = 'evaluated'
                     AND NOT EXISTS (SELECT 1 FROM report_vendor_results r
                                      WHERE r.report_version_id = NEW.id
                                        AND r.tender_vendor_id = p.tender_vendor_id)) THEN
      RAISE EXCEPTION 'every evaluated vendor needs a result before the report is finalized'
        USING ERRCODE = '23000';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER report_versions_guard BEFORE INSERT OR UPDATE ON report_versions
    FOR EACH ROW EXECUTE FUNCTION report_versions_guard();
CREATE FUNCTION report_versions_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'report versions are never deleted' USING ERRCODE = '23000';
END $$;
CREATE TRIGGER report_versions_keep BEFORE DELETE ON report_versions
    FOR EACH ROW EXECUTE FUNCTION report_versions_no_delete();

-- ---- children: written while in progress only, never deleted -----------------------------------------------------
CREATE FUNCTION report_grades_guard() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  strip text[] := ARRAY['final_grade', 'basis', 'justification', 'decided_by', 'decided_at',
                        'updated_at', 'row_version'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'report grades are never deleted' USING ERRCODE = '23000';
  END IF;
  IF NOT app_report_in_progress(NEW.report_version_id) THEN
    RAISE EXCEPTION 'a finalized report version is locked' USING ERRCODE = '23000';
  END IF;
  IF TG_OP = 'UPDATE' AND (to_jsonb(NEW) - strip) IS DISTINCT FROM (to_jsonb(OLD) - strip) THEN
    RAISE EXCEPTION 'only the decision of a report grade changes' USING ERRCODE = '23000';
  END IF;
  IF TG_OP = 'INSERT' AND NOT EXISTS (
       SELECT 1 FROM report_versions r
         JOIN tender_vendors v ON v.id = NEW.tender_vendor_id AND v.tender_id = r.tender_id
         JOIN requirements q ON q.id = NEW.requirement_id AND q.tender_id = r.tender_id
        WHERE r.id = NEW.report_version_id) THEN
    RAISE EXCEPTION 'the vendor and the requirement belong to the version''s tender' USING ERRCODE = '23000';
  END IF;
  -- Provenance: "all agree" is true only when every member's own row holds the final grade.
  IF NEW.basis = 'all_agree' THEN
    IF NEW.has_disagreement THEN
      RAISE EXCEPTION 'a grade with a disagreement is a Chair decision' USING ERRCODE = '23000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM member_evaluations e
                    WHERE e.tender_vendor_id = NEW.tender_vendor_id AND e.excluded_at IS NULL)
       OR EXISTS (SELECT 1 FROM member_evaluations e
                   WHERE e.tender_vendor_id = NEW.tender_vendor_id AND e.excluded_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM member_evaluation_rows mr
                                      WHERE mr.member_evaluation_id = e.id
                                        AND mr.requirement_id = NEW.requirement_id
                                        AND mr.member_grade = NEW.final_grade)) THEN
      RAISE EXCEPTION 'not every member graded this row the same' USING ERRCODE = '23000';
    END IF;
  END IF;
  IF NEW.basis = 'chair_decision' AND NOT EXISTS (
       SELECT 1 FROM report_versions r JOIN tender_members m ON m.tender_id = r.tender_id
        WHERE r.id = NEW.report_version_id AND m.user_id = NEW.decided_by
          AND m.is_chair AND m.status = 'active') THEN
    RAISE EXCEPTION 'only the Chair of the tender records a final grade' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER report_grades_guard BEFORE INSERT OR UPDATE OR DELETE ON report_grades
    FOR EACH ROW EXECUTE FUNCTION report_grades_guard();

CREATE FUNCTION report_member_grades_guard() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'report member grades are append-only' USING ERRCODE = '23000';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM report_grades g WHERE g.id = NEW.report_grade_id
                 AND app_report_in_progress(g.report_version_id)) THEN
    RAISE EXCEPTION 'a finalized report version is locked' USING ERRCODE = '23000';
  END IF;
  -- A copy is exactly the member's own row, from an evaluation that is not excluded.
  IF NOT EXISTS (
       SELECT 1 FROM report_grades g
         JOIN member_evaluations e ON e.tender_vendor_id = g.tender_vendor_id AND e.user_id = NEW.user_id
         JOIN member_evaluation_rows mr ON mr.member_evaluation_id = e.id
                                       AND mr.requirement_id = g.requirement_id
        WHERE g.id = NEW.report_grade_id AND e.excluded_at IS NULL
          AND mr.review_state = NEW.review_state
          AND mr.member_grade IS NOT DISTINCT FROM NEW.member_grade
          AND mr.note IS NOT DISTINCT FROM NEW.note) THEN
    RAISE EXCEPTION 'a copy is the member''s own decision of that row' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER report_member_grades_guard BEFORE INSERT OR UPDATE OR DELETE ON report_member_grades
    FOR EACH ROW EXECUTE FUNCTION report_member_grades_guard();

CREATE FUNCTION report_vendor_results_guard() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'report vendor results are written once' USING ERRCODE = '23000';
  END IF;
  IF NOT app_report_in_progress(NEW.report_version_id) THEN
    RAISE EXCEPTION 'a finalized report version is locked' USING ERRCODE = '23000';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM report_versions r JOIN tender_vendors v ON v.tender_id = r.tender_id
                  WHERE r.id = NEW.report_version_id AND v.id = NEW.tender_vendor_id) THEN
    RAISE EXCEPTION 'the vendor belongs to the version''s tender' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER report_vendor_results_guard BEFORE INSERT OR UPDATE OR DELETE ON report_vendor_results
    FOR EACH ROW EXECUTE FUNCTION report_vendor_results_guard();

-- ---- tender stage moves of the report ---------------------------------------------------------------------------
-- The tenders table is administrator-write, so these moves are definer functions (V17 pattern).
-- The Chair finalizes (consolidation -> finalized) and reopens (finalized -> consolidation), rule 2.
CREATE FUNCTION app_chair_report_stage(p_tender uuid, p_to text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  current_status text;
BEGIN
  IF app_role() IS DISTINCT FROM 'member' OR NOT app_is_chair_of(p_tender) THEN
    RAISE EXCEPTION 'only the Chair moves this stage' USING ERRCODE = '42501';
  END IF;
  SELECT status INTO current_status FROM tenders WHERE id = p_tender FOR UPDATE;
  IF NOT ((current_status = 'consolidation' AND p_to = 'finalized')
          OR (current_status = 'finalized' AND p_to = 'consolidation')) THEN
    RAISE EXCEPTION 'the Chair cannot move % to %', current_status, p_to USING ERRCODE = '23514';
  END IF;
  UPDATE tenders SET status = p_to WHERE id = p_tender;
END $$;
REVOKE ALL ON FUNCTION app_chair_report_stage(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_chair_report_stage(uuid, text) TO tender_app;

-- evaluation -> consolidation when every accepted vendor has all evaluations submitted or excluded
-- and at least one vendor was evaluated. Any committee member or the administrator may trigger the
-- check (it moves the tender only when the rule holds). Returns whether the tender moved.
CREATE FUNCTION app_advance_to_consolidation(p_tender uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  current_status text;
BEGIN
  IF app_user_id() IS NULL OR NOT app_can_use_tender(p_tender) THEN
    RAISE EXCEPTION 'not a member of this tender' USING ERRCODE = '42501';
  END IF;
  SELECT status INTO current_status FROM tenders WHERE id = p_tender FOR UPDATE;
  IF current_status <> 'evaluation' THEN
    RETURN false;
  END IF;
  IF EXISTS (SELECT 1 FROM proposal_versions p JOIN tender_vendors v ON v.id = p.tender_vendor_id
              WHERE v.tender_id = p_tender AND p.status IN ('accepted', 'evaluating')) THEN
    RETURN false;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM proposal_versions p JOIN tender_vendors v ON v.id = p.tender_vendor_id
                  WHERE v.tender_id = p_tender AND p.status = 'evaluated') THEN
    RETURN false;
  END IF;
  IF EXISTS (SELECT 1 FROM proposal_versions p JOIN tender_vendors v ON v.id = p.tender_vendor_id
              WHERE v.tender_id = p_tender AND p.status = 'evaluated'
                AND NOT app_all_submitted_or_excluded(p.tender_vendor_id)) THEN
    RETURN false;
  END IF;
  UPDATE tenders SET status = 'consolidation' WHERE id = p_tender;
  RETURN true;
END $$;
REVOKE ALL ON FUNCTION app_advance_to_consolidation(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_advance_to_consolidation(uuid) TO tender_app;

-- ---- notify -------------------------------------------------------------------------------------------------------
-- A person may notify only someone who can see the tender, and only through this function: the table
-- has no insert policy, so nobody can write a notification in another person's name directly. The
-- email status follows the recipient's preference (in-app only when there is none, DM question 12).
CREATE FUNCTION app_notify(p_user uuid, p_tender uuid, p_type text, p_object_type text,
                           p_object_id uuid, p_params jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  recipient users%ROWTYPE;
  new_id uuid;
  want_email boolean;
BEGIN
  IF app_user_id() IS NULL OR app_role() IS NULL THEN
    RAISE EXCEPTION 'sign in first' USING ERRCODE = '42501';
  END IF;
  IF p_tender IS NULL OR NOT app_can_use_tender(p_tender) THEN
    RAISE EXCEPTION 'not a member of this tender' USING ERRCODE = '42501';
  END IF;
  -- The administrator and the Chair notify the committee; a member's own actions raise only these.
  IF NOT (app_role() = 'admin' OR app_is_chair_of(p_tender)
          OR p_type IN ('all_members_submitted', 'conflict_declared', 'job_completed', 'job_failed')) THEN
    RAISE EXCEPTION 'this notification is not yours to send' USING ERRCODE = '42501';
  END IF;
  -- Values for a template, never grades, notes or document text (rule 10).
  IF octet_length(coalesce(p_params, '{}'::jsonb)::text) > 2000 THEN
    RAISE EXCEPTION 'notification values are small' USING ERRCODE = '22023';
  END IF;
  SELECT * INTO recipient FROM users WHERE id = p_user AND status = 'active';
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;
  IF recipient.role = 'member' AND NOT EXISTS (
       SELECT 1 FROM tender_members m WHERE m.tender_id = p_tender AND m.user_id = p_user
       AND m.status = 'active') THEN
    RETURN NULL;
  END IF;
  SELECT email_enabled INTO want_email FROM notification_preferences
   WHERE user_id = p_user AND notification_type = p_type;
  INSERT INTO notifications (user_id, tender_id, notification_type, object_type, object_id, params,
                             email_status)
  VALUES (p_user, p_tender, p_type, p_object_type, p_object_id, coalesce(p_params, '{}'::jsonb),
          CASE WHEN coalesce(want_email, false) THEN 'pending' ELSE 'not_requested' END)
  RETURNING id INTO new_id;
  RETURN new_id;
END $$;
REVOKE ALL ON FUNCTION app_notify(uuid, uuid, text, text, uuid, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_notify(uuid, uuid, text, text, uuid, jsonb) TO tender_app;

-- ---- row-level security ---------------------------------------------------------------------------------------
ALTER TABLE report_versions          ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_grades            ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_member_grades     ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_vendor_results    ENABLE ROW LEVEL SECURITY;
ALTER TABLE exports                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications            ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;

-- The version row (number, status, dates) is readable by the committee and the administrator; the
-- Observer reads published versions. The results are gated separately, per vendor.
CREATE POLICY report_versions_read ON report_versions FOR SELECT TO tender_app USING (
    app_role() = 'admin'
    OR (app_role() = 'member' AND app_is_member_of(tender_id))
    OR (app_role() = 'observer' AND status IN ('finalized', 'superseded')));
CREATE POLICY report_versions_chair_insert ON report_versions FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id) AND created_by = app_user_id());
CREATE POLICY report_versions_chair_update ON report_versions FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND app_is_chair_of(tender_id))
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id));

CREATE POLICY report_grades_read ON report_grades FOR SELECT TO tender_app
    USING (app_can_read_report(report_version_id, tender_vendor_id));
CREATE POLICY report_grades_chair_insert ON report_grades FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(app_tender_of_report(report_version_id)));
CREATE POLICY report_grades_chair_update ON report_grades FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND app_is_chair_of(app_tender_of_report(report_version_id)))
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(app_tender_of_report(report_version_id)));

CREATE POLICY report_member_grades_read ON report_member_grades FOR SELECT TO tender_app USING (
    EXISTS (SELECT 1 FROM report_grades g WHERE g.id = report_grade_id));
CREATE POLICY report_member_grades_chair_insert ON report_member_grades FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member'
                AND app_is_chair_of(app_tender_of_report_grade(report_grade_id)));

CREATE POLICY report_vendor_results_read ON report_vendor_results FOR SELECT TO tender_app
    USING (app_can_read_report(report_version_id, tender_vendor_id));
CREATE POLICY report_vendor_results_chair_insert ON report_vendor_results FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(app_tender_of_report(report_version_id)));

-- Exports: the person who asked reads and finishes their own; the administrator reads all.
CREATE POLICY exports_read ON exports FOR SELECT TO tender_app
    USING (requested_by = app_user_id() OR app_role() = 'admin');
CREATE POLICY exports_insert ON exports FOR INSERT TO tender_app
    WITH CHECK (requested_by = app_user_id()
                AND (tender_id IS NULL OR app_role() = 'observer' OR app_can_use_tender(tender_id)));
CREATE POLICY exports_update ON exports FOR UPDATE TO tender_app
    USING (requested_by = app_user_id()) WITH CHECK (requested_by = app_user_id());

-- Notifications: a user reads and marks only their own; rows are created through app_notify only.
CREATE POLICY notifications_read ON notifications FOR SELECT TO tender_app
    USING (user_id = app_user_id()
           AND (tender_id IS NULL OR app_role() IN ('admin', 'observer') OR app_is_member_of(tender_id)));
CREATE POLICY notifications_mark ON notifications FOR UPDATE TO tender_app
    USING (user_id = app_user_id()) WITH CHECK (user_id = app_user_id());
REVOKE INSERT, UPDATE ON notifications FROM tender_app;
GRANT UPDATE (read_at) ON notifications TO tender_app;

CREATE POLICY notification_preferences_own ON notification_preferences FOR ALL TO tender_app
    USING (user_id = app_user_id()) WITH CHECK (user_id = app_user_id());

-- Copies are written once.
REVOKE UPDATE ON report_member_grades, report_vendor_results FROM tender_app;

-- An export changes only its progress columns, and only while it is pending.
REVOKE UPDATE ON exports FROM tender_app;
GRANT UPDATE (status, storage_key, failure_code) ON exports TO tender_app;
CREATE FUNCTION exports_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'pending' THEN
    RAISE EXCEPTION 'a finished export does not change' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER exports_guard BEFORE UPDATE ON exports FOR EACH ROW EXECUTE FUNCTION exports_guard();

-- Email delivery (the mailer runs as the application role without a signed-in person): it reads
-- the next pending rows and records the outcome. It can neither read the notification text nor
-- change anything but the delivery status, which the status trigger holds to the listed moves.
CREATE FUNCTION app_notifications_email_next(p_limit integer)
RETURNS TABLE (id uuid, user_id uuid, tender_id uuid, notification_type text, params jsonb)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT n.id, n.user_id, n.tender_id, n.notification_type, n.params
        FROM notifications n WHERE n.email_status = 'pending' AND app_user_id() IS NULL
       ORDER BY n.created_at LIMIT least(greatest(p_limit, 1), 100) $$;
CREATE FUNCTION app_notification_email_result(p_id uuid, p_status text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF p_status NOT IN ('sent', 'failed') THEN
    RAISE EXCEPTION 'sent or failed' USING ERRCODE = '22023';
  END IF;
  UPDATE notifications SET email_status = p_status,
         emailed_at = CASE WHEN p_status = 'sent' THEN now() END
   WHERE id = p_id AND email_status = 'pending' AND app_user_id() IS NULL;
END $$;
CREATE FUNCTION app_notification_email_retry(p_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF app_role() IS DISTINCT FROM 'admin' THEN
    RAISE EXCEPTION 'the administrator retries email' USING ERRCODE = '42501';
  END IF;
  UPDATE notifications SET email_status = 'pending' WHERE id = p_id AND email_status = 'failed';
END $$;

-- The definer helpers keep no default PUBLIC execute (V9 pattern).
REVOKE ALL ON FUNCTION app_tender_of_report(uuid), app_tender_of_report_grade(uuid),
    app_report_in_progress(uuid), app_can_read_report(uuid, uuid),
    app_notifications_email_next(integer), app_notification_email_result(uuid, text),
    app_notification_email_retry(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_tender_of_report(uuid), app_tender_of_report_grade(uuid),
    app_report_in_progress(uuid), app_can_read_report(uuid, uuid),
    app_notifications_email_next(integer), app_notification_email_result(uuid, text),
    app_notification_email_retry(uuid) TO tender_app;
