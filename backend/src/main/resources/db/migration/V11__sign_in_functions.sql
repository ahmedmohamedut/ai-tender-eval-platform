-- P1-3: what the backend needs before it knows who is calling.
--
-- The first query of a request looks a person up by their Keycloak subject, but the policies on
-- users need an identity first. These two functions are the narrow doors: one returns the row
-- of the subject asked about, the other creates the first administrator (PI 3.7).

CREATE FUNCTION app_resolve_user(p_sub text)
RETURNS TABLE (id uuid, email text, display_name text, role text, status text, row_version integer,
               last_sign_in_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT u.id, u.email, u.display_name, u.role, u.status, u.row_version, u.last_sign_in_at
  FROM users u WHERE u.sso_subject = p_sub
$$;

-- Creates the bootstrap administrator when, and only when, no administrator exists yet.
-- The backend calls it after checking the token's email against bootstrap.admin_email.
CREATE FUNCTION app_bootstrap_admin(p_sub text, p_email text, p_name text) RETURNS uuid
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  INSERT INTO users (email, display_name, role, status, sso_subject, last_sign_in_at)
  SELECT p_email, p_name, 'admin', 'active', p_sub, now()
  WHERE NOT EXISTS (SELECT 1 FROM users WHERE role = 'admin')
  ON CONFLICT DO NOTHING
  RETURNING id
$$;

REVOKE ALL ON FUNCTION app_resolve_user(text), app_bootstrap_admin(text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_resolve_user(text), app_bootstrap_admin(text, text, text) TO tender_app;
