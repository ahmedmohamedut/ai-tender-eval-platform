-- P4-6 (approved by the project owner): a member's decisions are audited like every write, so the
-- administrator and the Observer, who read audit_events, would see grades and override reasons
-- before the release rules apply. The read policy now holds back the events about a member's
-- evaluation rows: the administrator reads them once every member of that vendor has submitted or
-- been excluded (the same rule as the rows themselves); the Observer never. Every other event is
-- read exactly as before.
DROP POLICY audit_read ON audit_events;
CREATE POLICY audit_read ON audit_events FOR SELECT TO tender_app USING (
    CASE
      WHEN object_type = 'member_evaluation_row' THEN
        app_role() = 'admin' AND tender_vendor_id IS NOT NULL
        AND app_all_submitted_or_excluded(tender_vendor_id)
      ELSE app_role() IN ('admin', 'observer')
    END);

-- The history of the caller's own row for one requirement: their own decisions only (UX-EVA-07).
-- SECURITY DEFINER because members cannot read audit_events; it returns events of the caller's
-- own row and nothing else.
CREATE FUNCTION app_evaluation_row_history(p_tender_vendor uuid, p_requirement uuid)
RETURNS TABLE (id uuid, occurred_at timestamptz, source text, action text, actor_user_id uuid,
               display_name text, before_json text, after_json text, note text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT a.id, a.occurred_at, a.source, a.action, a.actor_user_id, u.display_name,
         a.before::text, a.after::text, a.note
    FROM audit_events a LEFT JOIN users u ON u.id = a.actor_user_id
   WHERE a.object_type = 'member_evaluation_row'
     AND a.object_id = (SELECT r.id FROM member_evaluation_rows r
                          JOIN member_evaluations e ON e.id = r.member_evaluation_id
                         WHERE e.tender_vendor_id = p_tender_vendor AND e.user_id = app_user_id()
                           AND r.requirement_id = p_requirement)
     AND app_role() = 'member'
   ORDER BY a.occurred_at DESC, a.id DESC
$$;
REVOKE ALL ON FUNCTION app_evaluation_row_history(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_evaluation_row_history(uuid, uuid) TO tender_app;
