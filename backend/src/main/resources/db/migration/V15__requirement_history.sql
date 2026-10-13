-- P3-2: the history of a requirement is its audit events (DM: no history table). Members cannot
-- read audit_events (the audit trail is an administrator and Observer screen), so the events of
-- one requirement are served by a definer function that first checks that the caller may read
-- that requirement's tender.
CREATE FUNCTION app_requirement_history(p_requirement uuid)
RETURNS TABLE (id uuid, occurred_at timestamptz, source text, action text, actor_user_id uuid,
               display_name text, before_json text, after_json text, note text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT e.id, e.occurred_at, e.source, e.action, e.actor_user_id, u.display_name,
         e.before::text, e.after::text, e.note
  FROM audit_events e
  LEFT JOIN users u ON u.id = e.actor_user_id
  WHERE e.object_type = 'requirement' AND e.object_id = p_requirement
    AND EXISTS (SELECT 1 FROM requirements r
                WHERE r.id = p_requirement AND app_can_use_tender(r.tender_id))
  ORDER BY e.occurred_at DESC, e.id DESC
  LIMIT 200
$$;
REVOKE ALL ON FUNCTION app_requirement_history(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_requirement_history(uuid) TO tender_app;
