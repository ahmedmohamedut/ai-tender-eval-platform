-- P1-5: "last activity" of a tender (UX-TND-01) is the latest audit event on it. Members cannot
-- read audit_events (DM Independence and access), so the lookup is a narrow SECURITY DEFINER
-- function that returns one timestamp and nothing else.
CREATE FUNCTION app_tender_last_activity(t uuid) RETURNS timestamptz
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT max(occurred_at) FROM audit_events WHERE tender_id = t $$;
REVOKE ALL ON FUNCTION app_tender_last_activity(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_tender_last_activity(uuid) TO tender_app;

-- UX-ROL-04: a person without access to a tender is told who to ask, not shown an error code.
-- Existence and the administrators' names are the only things these two functions reveal.
CREATE FUNCTION app_tender_exists(t uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM tenders WHERE id = t AND deleted_at IS NULL) $$;

CREATE FUNCTION app_access_contacts() RETURNS TABLE (display_name text, email text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT u.display_name, u.email FROM users u
      WHERE u.role = 'admin' AND u.status = 'active' ORDER BY lower(u.display_name) $$;

REVOKE ALL ON FUNCTION app_tender_exists(uuid), app_access_contacts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_tender_exists(uuid), app_access_contacts() TO tender_app;
