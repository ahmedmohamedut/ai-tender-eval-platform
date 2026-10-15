-- P5-8 (G15, G16; rule 11): the Chair's earlier decisions stay readable, and a reopened report keeps
-- the original decider. Reviewed by a separate agent before release (RLS and trigger change).

-- ---- history of the Chair's decision on a row ---------------------------------------------------------------------
-- One row per decision that a later one replaced. Written only by the guard trigger below, never
-- changed or deleted; the current decision stays in report_grades.
CREATE TABLE report_grade_history (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    report_grade_id uuid NOT NULL REFERENCES report_grades (id) ON DELETE RESTRICT,
    final_grade     text NOT NULL CHECK (final_grade IN ('fully_compliant', 'partially_compliant', 'non_compliant')),
    justification   text NOT NULL,
    decided_by      uuid REFERENCES users (id) ON DELETE RESTRICT,
    decided_at      timestamptz,
    superseded_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX report_grade_history_grade ON report_grade_history (report_grade_id, superseded_at DESC);

CREATE FUNCTION report_grade_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'the history of a final grade is never changed or deleted' USING ERRCODE = '23000';
END $$;
CREATE TRIGGER report_grade_history_immutable BEFORE UPDATE OR DELETE ON report_grade_history
    FOR EACH ROW EXECUTE FUNCTION report_grade_history_immutable();

ALTER TABLE report_grade_history ENABLE ROW LEVEL SECURITY;
-- Same audience as the grade itself; no write policy: only the definer trigger writes.
CREATE POLICY report_grade_history_read ON report_grade_history FOR SELECT TO tender_app
    USING (EXISTS (SELECT 1 FROM report_grades g WHERE g.id = report_grade_id));
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON report_grade_history FROM tender_app;
GRANT SELECT ON report_grade_history TO tender_app;

-- The Observer reads results through definer functions (P86); so does the history.
CREATE FUNCTION app_report_grade_history(p_version uuid, p_vendor uuid, p_requirement uuid)
RETURNS TABLE (final_grade text, justification text, decided_by uuid, decided_by_name text,
               decided_at timestamptz, superseded_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT h.final_grade, h.justification, h.decided_by, u.display_name, h.decided_at, h.superseded_at
    FROM report_grades g
    JOIN report_grade_history h ON h.report_grade_id = g.id
    LEFT JOIN users u ON u.id = h.decided_by
   WHERE g.report_version_id = p_version AND g.tender_vendor_id = p_vendor
     AND g.requirement_id = p_requirement
     AND app_can_read_report(p_version, p_vendor)
   ORDER BY h.superseded_at DESC, h.id DESC
$$;
REVOKE ALL ON FUNCTION app_report_grade_history(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_report_grade_history(uuid, uuid, uuid) TO tender_app;

-- ---- the guard: history on replacement, provenance on reopen ------------------------------------------------------
CREATE OR REPLACE FUNCTION report_grades_guard() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  strip text[] := ARRAY['final_grade', 'basis', 'justification', 'decided_by', 'decided_at',
                        'updated_at', 'row_version'];
  decision_changed boolean := false;
  is_copy boolean := false;
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
    IF NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL OR NEW.justification IS NOT NULL THEN
      RAISE EXCEPTION 'a derived grade carries no decider or justification' USING ERRCODE = '23000';
    END IF;
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
  IF NEW.basis = 'chair_decision' THEN
    decision_changed := TG_OP = 'INSERT' OR NEW.final_grade IS DISTINCT FROM OLD.final_grade
      OR NEW.justification IS DISTINCT FROM OLD.justification OR NEW.basis IS DISTINCT FROM OLD.basis
      OR NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at;
    -- A carried-over decision (a reopen) repeats, exactly, a decision of a finalized earlier version of
    -- the same tender: the original decider and time stay (UX-RPT-08, G16).
    IF TG_OP = 'INSERT' THEN
      is_copy := EXISTS (
        SELECT 1 FROM report_versions r2
          JOIN report_versions r ON r.id = NEW.report_version_id AND r.tender_id = r2.tender_id
          JOIN report_grades g2 ON g2.report_version_id = r2.id
          JOIN baseline_requirements br ON br.baseline_id = r.baseline_id
                                       AND br.requirement_id = NEW.requirement_id
         WHERE r2.id <> NEW.report_version_id AND r2.status IN ('finalized', 'superseded')
           AND g2.basis = 'chair_decision'
           AND g2.tender_vendor_id = NEW.tender_vendor_id AND g2.requirement_id = NEW.requirement_id
           AND g2.final_grade = NEW.final_grade
           AND g2.justification IS NOT DISTINCT FROM NEW.justification
           AND g2.decided_by IS NOT DISTINCT FROM NEW.decided_by
           AND g2.decided_at IS NOT DISTINCT FROM NEW.decided_at);
    END IF;
    IF decision_changed AND NOT is_copy THEN
      -- A new decision is the acting Chair's own, made now by a current Chair of the tender.
      IF NOT EXISTS (
           SELECT 1 FROM report_versions r JOIN tender_members m ON m.tender_id = r.tender_id
            WHERE r.id = NEW.report_version_id AND m.user_id = NEW.decided_by
              AND m.is_chair AND m.status = 'active') THEN
        RAISE EXCEPTION 'only the Chair of the tender records a final grade' USING ERRCODE = '23000';
      END IF;
      IF app_user_id() IS NOT NULL AND NEW.decided_by IS DISTINCT FROM app_user_id() THEN
        RAISE EXCEPTION 'a final grade is recorded in the name of the person recording it'
          USING ERRCODE = '23000';
      END IF;
    END IF;
  END IF;
  -- A decision that is replaced, or turned into a derived grade, keeps the earlier one (rule 11, G15).
  IF TG_OP = 'UPDATE' AND OLD.basis = 'chair_decision' AND (
       NEW.final_grade IS DISTINCT FROM OLD.final_grade OR NEW.justification IS DISTINCT FROM OLD.justification
       OR NEW.basis IS DISTINCT FROM OLD.basis OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
       OR NEW.decided_at IS DISTINCT FROM OLD.decided_at) THEN
    INSERT INTO report_grade_history (report_grade_id, final_grade, justification, decided_by, decided_at)
    VALUES (OLD.id, OLD.final_grade, OLD.justification, OLD.decided_by, OLD.decided_at);
  END IF;
  RETURN NEW;
END $$;
