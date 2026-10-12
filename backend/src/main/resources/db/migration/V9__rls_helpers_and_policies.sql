-- P1-2: row-level security (DM Independence and access; implementation guide 5.3).
--
-- Each request transaction sets app.user_id (users.id) and app.role (admin, member or
-- observer) with set_config(..., true). The application role owns no table and cannot switch
-- these policies off. Helper functions are SECURITY DEFINER so that a policy on tender_members
-- is not evaluated by itself; the owner is not subject to RLS (no FORCE), the caller is.
--
-- The Chair is never read from the token: app_is_chair_of() reads tender_members (rule 2).
-- Every policy is scoped TO the role that uses it, so a policy for the request path can never
-- apply to the AI-ingest connection and the reverse.

CREATE FUNCTION app_user_id() RETURNS uuid LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION app_role() RETURNS text LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.role', true), '') $$;

CREATE FUNCTION app_is_member_of(t uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM tender_members m
        WHERE m.tender_id = t AND m.user_id = app_user_id() AND m.status = 'active') $$;

CREATE FUNCTION app_is_chair_of(t uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM tender_members m
        WHERE m.tender_id = t AND m.user_id = app_user_id() AND m.status = 'active' AND m.is_chair) $$;

-- Tender dependents (documents, vendors, jobs): the admin always, a member when assigned.
-- The Observer reads the tender list only (PI Q7 default), so is not part of this test.
CREATE FUNCTION app_can_use_tender(t uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT app_role() = 'admin' OR (app_role() = 'member' AND app_is_member_of(t)) $$;

-- A document version: files of a vendor's proposal need a conflict declaration of "no
-- conflict" by the reader (DM Read rules). Replaced in Phase 4 to add exclusions.
CREATE FUNCTION app_can_read_document_version(dv uuid) RETURNS boolean LANGUAGE sql STABLE
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
              JOIN conflict_declarations c ON c.tender_vendor_id = pv.tender_vendor_id
              WHERE f.document_version_id = v.id
                AND c.user_id = app_user_id()
                AND NOT c.has_conflict)))
  END
$$;

CREATE FUNCTION app_can_use_job(j uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM jobs x WHERE x.id = j AND app_can_use_tender(x.tender_id)) $$;

-- Whether the signed-in user may see another user's profile: the admin, themselves,
-- people on a shared committee, and for the Observer anyone who sits on a committee (the
-- tender list shows the Chair and the committee size).
CREATE FUNCTION app_can_see_user(u uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT app_role() = 'admin'
      OR u = app_user_id()
      OR (app_role() = 'observer' AND EXISTS (
            SELECT 1 FROM tender_members m WHERE m.user_id = u AND m.status = 'active'))
      OR EXISTS (
            SELECT 1 FROM tender_members mine
            JOIN tender_members theirs ON theirs.tender_id = mine.tender_id
            WHERE mine.user_id = app_user_id() AND mine.status = 'active'
              AND theirs.user_id = u AND theirs.status = 'active')
$$;

REVOKE ALL ON FUNCTION app_user_id(), app_role(), app_is_member_of(uuid), app_is_chair_of(uuid),
    app_can_use_tender(uuid), app_can_read_document_version(uuid), app_can_use_job(uuid),
    app_can_see_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_user_id(), app_role(), app_is_member_of(uuid), app_is_chair_of(uuid),
    app_can_use_tender(uuid), app_can_read_document_version(uuid), app_can_use_job(uuid),
    app_can_see_user(uuid) TO tender_app;

-- ---------------------------------------------------------------------------------------
-- Enable row-level security on every table that holds data. status_transitions is reference
-- data without RLS and without write privileges.
ALTER TABLE users                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE drafts                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE settings               ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenders                ENABLE ROW LEVEL SECURITY;
ALTER TABLE tender_members         ENABLE ROW LEVEL SECURITY;
ALTER TABLE tender_stage_dates     ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents              ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_versions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_pages         ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_sections      ENABLE ROW LEVEL SECURITY;
ALTER TABLE passages               ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendors                ENABLE ROW LEVEL SECURITY;
ALTER TABLE tender_vendors         ENABLE ROW LEVEL SECURITY;
ALTER TABLE proposal_versions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE proposal_version_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE conflict_declarations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE jobs                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_steps              ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_scope_items        ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events           ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_access_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys       ENABLE ROW LEVEL SECURITY;

-- ---- users: self, admin, committee mates ------------------------------------------------
CREATE POLICY users_read ON users FOR SELECT TO tender_app USING (app_can_see_user(id));
CREATE POLICY users_admin_insert ON users FOR INSERT TO tender_app WITH CHECK (app_role() = 'admin');
CREATE POLICY users_admin_update ON users FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');
CREATE POLICY users_self_update ON users FOR UPDATE TO tender_app
    USING (id = app_user_id()) WITH CHECK (id = app_user_id());

-- A person may change their own time zone, and become active on first sign-in (invited to
-- active). Everything else on a user row is the administrator's.
CREATE FUNCTION guard_user_self_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF app_role() = 'admin' THEN
    RETURN NEW;
  END IF;
  IF NEW.email IS DISTINCT FROM OLD.email
     OR NEW.role IS DISTINCT FROM OLD.role
     OR NEW.sso_subject IS DISTINCT FROM OLD.sso_subject
     OR NEW.display_name IS DISTINCT FROM OLD.display_name
     OR (NEW.status IS DISTINCT FROM OLD.status
         AND NOT (OLD.status = 'invited' AND NEW.status = 'active')) THEN
    RAISE EXCEPTION 'a user may not change their own role, status or identity' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER users_self_guard BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION guard_user_self_update();

-- ---- drafts: the author and nobody else ------------------------------------------------------
CREATE POLICY drafts_author ON drafts FOR ALL TO tender_app
    USING (user_id = app_user_id()) WITH CHECK (user_id = app_user_id());

-- ---- reference and settings -------------------------------------------------------------------
CREATE POLICY categories_read ON requirement_categories FOR SELECT TO tender_app
    USING (app_user_id() IS NOT NULL);
CREATE POLICY categories_admin_write ON requirement_categories FOR ALL TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');
CREATE POLICY settings_read ON settings FOR SELECT TO tender_app USING (app_user_id() IS NOT NULL);
CREATE POLICY settings_admin_update ON settings FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

-- ---- tenders and the committee ------------------------------------------------------------------
CREATE POLICY tenders_read ON tenders FOR SELECT TO tender_app USING (
    app_role() IN ('admin', 'observer') OR (app_role() = 'member' AND app_is_member_of(id)));
CREATE POLICY tenders_admin_write ON tenders FOR INSERT TO tender_app WITH CHECK (app_role() = 'admin');
CREATE POLICY tenders_admin_update ON tenders FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY tender_members_read ON tender_members FOR SELECT TO tender_app USING (
    app_role() IN ('admin', 'observer') OR user_id = app_user_id()
    OR (app_role() = 'member' AND app_is_member_of(tender_id)));
CREATE POLICY tender_members_admin_insert ON tender_members FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'admin');
CREATE POLICY tender_members_admin_update ON tender_members FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY stage_dates_read ON tender_stage_dates FOR SELECT TO tender_app
    USING (app_can_use_tender(tender_id));
CREATE POLICY stage_dates_admin_insert ON tender_stage_dates FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'admin');
CREATE POLICY stage_dates_admin_update ON tender_stage_dates FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

-- ---- documents -------------------------------------------------------------------------------------
CREATE POLICY documents_read ON documents FOR SELECT TO tender_app USING (app_can_use_tender(tender_id));
CREATE POLICY documents_admin_insert ON documents FOR INSERT TO tender_app WITH CHECK (app_role() = 'admin');
CREATE POLICY documents_admin_update ON documents FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY document_versions_read ON document_versions FOR SELECT TO tender_app
    USING (app_can_read_document_version(id));
CREATE POLICY document_versions_admin_insert ON document_versions FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'admin');
CREATE POLICY document_versions_admin_update ON document_versions FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY document_pages_read ON document_pages FOR SELECT TO tender_app
    USING (app_can_read_document_version(document_version_id));
CREATE POLICY document_sections_read ON document_sections FOR SELECT TO tender_app
    USING (app_can_read_document_version(document_version_id));
CREATE POLICY passages_read ON passages FOR SELECT TO tender_app
    USING (app_can_read_document_version(document_version_id));

-- ---- vendors and proposals ---------------------------------------------------------------------
CREATE POLICY vendors_read ON vendors FOR SELECT TO tender_app USING (
    app_role() = 'admin' OR EXISTS (
        SELECT 1 FROM tender_vendors tv WHERE tv.vendor_id = vendors.id AND app_can_use_tender(tv.tender_id)));
CREATE POLICY vendors_admin_insert ON vendors FOR INSERT TO tender_app WITH CHECK (app_role() = 'admin');
CREATE POLICY vendors_admin_update ON vendors FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY tender_vendors_read ON tender_vendors FOR SELECT TO tender_app USING (
    app_role() IN ('admin', 'observer') OR (app_role() = 'member' AND app_is_member_of(tender_id)));
CREATE POLICY tender_vendors_admin_insert ON tender_vendors FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'admin');
CREATE POLICY tender_vendors_admin_update ON tender_vendors FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY proposal_versions_read ON proposal_versions FOR SELECT TO tender_app USING (
    EXISTS (SELECT 1 FROM tender_vendors tv
            WHERE tv.id = proposal_versions.tender_vendor_id AND app_can_use_tender(tv.tender_id)));
CREATE POLICY proposal_versions_admin_insert ON proposal_versions FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'admin');
CREATE POLICY proposal_versions_admin_update ON proposal_versions FOR UPDATE TO tender_app
    USING (app_role() = 'admin') WITH CHECK (app_role() = 'admin');

CREATE POLICY proposal_version_files_read ON proposal_version_files FOR SELECT TO tender_app USING (
    EXISTS (SELECT 1 FROM proposal_versions pv JOIN tender_vendors tv ON tv.id = pv.tender_vendor_id
            WHERE pv.id = proposal_version_files.proposal_version_id AND app_can_use_tender(tv.tender_id)));
CREATE POLICY proposal_version_files_admin_insert ON proposal_version_files FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'admin');

-- A declaration is written by its owner for a vendor of a tender they sit on, and is
-- immutable afterwards (trigger). The admin and the Chair of the tender read them.
CREATE POLICY declarations_read ON conflict_declarations FOR SELECT TO tender_app USING (
    user_id = app_user_id() OR app_role() = 'admin' OR EXISTS (
        SELECT 1 FROM tender_vendors tv
        WHERE tv.id = conflict_declarations.tender_vendor_id AND app_is_chair_of(tv.tender_id)));
CREATE POLICY declarations_own_insert ON conflict_declarations FOR INSERT TO tender_app WITH CHECK (
    user_id = app_user_id() AND app_role() = 'member' AND EXISTS (
        SELECT 1 FROM tender_vendors tv
        WHERE tv.id = conflict_declarations.tender_vendor_id AND app_is_member_of(tv.tender_id)));

-- ---- jobs ------------------------------------------------------------------------------------------
CREATE POLICY jobs_read ON jobs FOR SELECT TO tender_app USING (app_can_use_tender(tender_id));
CREATE POLICY jobs_insert ON jobs FOR INSERT TO tender_app WITH CHECK (app_can_use_tender(tender_id));
CREATE POLICY jobs_update ON jobs FOR UPDATE TO tender_app
    USING (app_can_use_tender(tender_id)) WITH CHECK (app_can_use_tender(tender_id));
CREATE POLICY job_steps_read ON job_steps FOR SELECT TO tender_app USING (app_can_use_job(job_id));
CREATE POLICY job_scope_items_read ON job_scope_items FOR SELECT TO tender_app USING (app_can_use_job(job_id));
CREATE POLICY job_scope_items_insert ON job_scope_items FOR INSERT TO tender_app
    WITH CHECK (app_can_use_job(job_id));

-- ---- logs ---------------------------------------------------------------------------------------------
-- History panels (Phase 3) read a view that returns only events on objects the reader may
-- already read; the table itself is for the admin and the Observer.
CREATE POLICY audit_read ON audit_events FOR SELECT TO tender_app
    USING (app_role() IN ('admin', 'observer'));
CREATE POLICY audit_insert ON audit_events FOR INSERT TO tender_app WITH CHECK (
    (source = 'person' AND actor_user_id = app_user_id()) OR source = 'system');
CREATE POLICY access_read ON document_access_events FOR SELECT TO tender_app
    USING (app_role() IN ('admin', 'observer'));
CREATE POLICY access_insert ON document_access_events FOR INSERT TO tender_app
    WITH CHECK (user_id = app_user_id());

CREATE POLICY idempotency_own ON idempotency_keys FOR ALL TO tender_app
    USING (user_id = app_user_id()) WITH CHECK (user_id = app_user_id());
