-- P4-4: how many requirements of the tender's confirmed baseline still need a grade for a proposal
-- version: no current graded row with the baseline's hash (failed, changed or new requirements).
-- SECURITY DEFINER because ai_grades is hidden from members who have not declared; this returns a
-- count only and requires the caller to be on the tender.
CREATE FUNCTION app_grades_needed(pv uuid) RETURNS integer LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT CASE WHEN NOT app_can_use_tender(app_tender_of_proposal(pv)) THEN 0 ELSE (
    SELECT count(*)::integer
      FROM baselines b
      JOIN baseline_requirements br ON br.baseline_id = b.id
     WHERE b.tender_id = app_tender_of_proposal(pv) AND b.status = 'confirmed'
       AND NOT EXISTS (SELECT 1 FROM ai_grades g
                        WHERE g.proposal_version_id = pv AND g.is_current
                          AND g.requirement_id = br.requirement_id AND g.outcome = 'graded'
                          AND g.requirement_hash = br.content_hash)) END
$$;
REVOKE ALL ON FUNCTION app_grades_needed(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_grades_needed(uuid) TO tender_app;
