-- P5-2 review: app_report_versions (V25) returned in-progress versions, with the Chair's reopen
-- reason, to every committee member. Same rule as app_can_read_report: in progress is for the
-- administrator and the Chair only.
CREATE OR REPLACE FUNCTION app_report_versions(p_tender uuid)
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
          OR (app_role() = 'member' AND app_is_member_of(p_tender)
              AND (rv.status <> 'in_progress' OR app_is_chair_of(p_tender)))
          OR (app_role() = 'observer' AND rv.status IN ('finalized', 'superseded')))
$$;
