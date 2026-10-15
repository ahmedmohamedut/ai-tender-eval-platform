-- P5-2 (approved by the project owner): the Observer reads a finalized report (UX-RPT-01), but
-- row-level security hides the baseline, its requirements, the scoring model and the vendor names
-- from the Observer role. No policy changes: these read functions are SECURITY DEFINER, return rows
-- only for a report version the caller may read (app_can_read_report), and expose nothing of a
-- member's rows, a proposal or an AI grade. They are STABLE, owned by the schema owner and granted
-- to tender_app only.

-- The versions of a tender the caller may see, with the baseline number.
CREATE FUNCTION app_report_versions(p_tender uuid)
RETURNS TABLE (id uuid, version_no integer, status text, baseline_id uuid, baseline_no integer,
               scoring_model_id uuid, finalized_at timestamptz, finalized_by uuid,
               reopen_reason text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT rv.id, rv.version_no, rv.status, rv.baseline_id, b.version_no, rv.scoring_model_id,
         rv.finalized_at, rv.finalized_by, rv.reopen_reason
    FROM report_versions rv JOIN baselines b ON b.id = rv.baseline_id
   WHERE rv.tender_id = p_tender
     AND (app_role() = 'admin'
          OR (app_role() = 'member' AND app_is_member_of(p_tender))
          OR (app_role() = 'observer' AND rv.status IN ('finalized', 'superseded')))
$$;

-- The requirements of the baseline the version was built on.
CREATE FUNCTION app_report_requirements(p_version uuid)
RETURNS TABLE (requirement_id uuid, display_no text, text text, category_name text,
               importance text, acceptance_criteria text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT br.requirement_id, br.display_no, br.text, br.category_name, br.importance,
         br.acceptance_criteria
    FROM report_versions rv JOIN baseline_requirements br ON br.baseline_id = rv.baseline_id
   WHERE rv.id = p_version AND app_can_read_report(p_version, NULL)
$$;

-- The scoring model of the version, with the category weights by category name.
CREATE FUNCTION app_report_scoring(p_version uuid)
RETURNS TABLE (points_fully numeric, points_partially numeric, points_non numeric,
               baseline_id uuid, row_version integer, weights jsonb)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT sm.points_fully, sm.points_partially, sm.points_non, sm.baseline_id, sm.row_version,
         coalesce((SELECT jsonb_agg(jsonb_build_object('category_id', c.id, 'name', c.name,
                                                       'weight', w.weight)
                                    ORDER BY c.sort_order, c.name)
                     FROM scoring_category_weights w JOIN requirement_categories c ON c.id = w.category_id
                    WHERE w.scoring_model_id = sm.id), '[]'::jsonb)
    FROM report_versions rv JOIN scoring_models sm ON sm.id = rv.scoring_model_id
   WHERE rv.id = p_version AND app_can_read_report(p_version, NULL)
$$;

-- The vendors the version holds a result or a grade for, with their names.
CREATE FUNCTION app_report_vendors(p_version uuid)
RETURNS TABLE (tender_vendor_id uuid, name text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT tv.id, v.name
    FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id
   WHERE app_can_read_report(p_version, NULL)
     AND (EXISTS (SELECT 1 FROM report_vendor_results r
                   WHERE r.report_version_id = p_version AND r.tender_vendor_id = tv.id)
          OR EXISTS (SELECT 1 FROM report_grades g
                      WHERE g.report_version_id = p_version AND g.tender_vendor_id = tv.id))
$$;

-- The AI models behind the grades of the version (UX-RPT-09).
CREATE FUNCTION app_report_models(p_version uuid)
RETURNS TABLE (model text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT DISTINCT a.model_name || coalesce(' ' || a.model_version, '')
    FROM report_grades g JOIN ai_grades ag ON ag.id = g.ai_grade_id
    JOIN ai_runs a ON a.id = ag.ai_run_id
   WHERE g.report_version_id = p_version AND app_can_read_report(p_version, NULL)
$$;

REVOKE ALL ON FUNCTION app_report_versions(uuid), app_report_requirements(uuid),
  app_report_scoring(uuid), app_report_vendors(uuid), app_report_models(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_report_versions(uuid), app_report_requirements(uuid),
  app_report_scoring(uuid), app_report_vendors(uuid), app_report_models(uuid) TO tender_app;
