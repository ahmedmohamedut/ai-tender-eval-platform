-- P3-1: requirements, their sources and flags, AI runs, baselines, the locked snapshot and the
-- scoring model (DM Entity catalogue "Requirements and baseline", "Jobs and AI runs").
--
-- Reads follow the committee (the administrator reads everything, the Observer reads none: PI Q7
-- default). A member edits requirements only while no baseline of the tender is confirmed.
-- AI runs and extracted requirements are written by the AI-ingest connection only.

-- ---- ai_runs (immutable once finished) ----------------------------------------------------------
CREATE TABLE ai_runs (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    job_id        uuid NOT NULL REFERENCES jobs (id) ON DELETE RESTRICT,
    run_type      text NOT NULL CHECK (run_type IN ('extraction', 'evaluation', 'precheck')),
    model_name    text NOT NULL,
    model_version text,
    prompt_version text NOT NULL,
    input_scope   jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    outcome       text CHECK (outcome IN ('succeeded', 'partial', 'failed')),
    warning_count integer NOT NULL DEFAULT 0 CHECK (warning_count >= 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id),
    CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
CREATE FUNCTION ai_runs_freeze() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.finished_at IS NOT NULL THEN
    RAISE EXCEPTION 'a finished ai run is immutable' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ai_runs_frozen BEFORE UPDATE ON ai_runs FOR EACH ROW EXECUTE FUNCTION ai_runs_freeze();

-- ---- baselines (locked on insert; only status and the reopen columns change) --------------------
CREATE TABLE baselines (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id         uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    version_no        integer NOT NULL CHECK (version_no >= 1),
    status            text NOT NULL DEFAULT 'confirmed'
                      CHECK (status IN ('confirmed', 'reopened', 'superseded')),
    requirement_count integer NOT NULL CHECK (requirement_count >= 0),
    confirmed_by      uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    confirmed_at      timestamptz NOT NULL DEFAULT now(),
    reopened_by       uuid REFERENCES users (id) ON DELETE RESTRICT,
    reopened_at       timestamptz,
    reopen_reason     text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    row_version       integer NOT NULL DEFAULT 1,
    UNIQUE (tender_id, version_no),
    CHECK ((status = 'reopened') = (reopened_at IS NOT NULL) OR status = 'superseded')
);
-- At most one baseline per tender is confirmed or reopened; older ones are superseded.
CREATE UNIQUE INDEX baselines_one_live ON baselines (tender_id) WHERE status IN ('confirmed', 'reopened');
CREATE TRIGGER baselines_touch BEFORE INSERT OR UPDATE ON baselines FOR EACH ROW EXECUTE FUNCTION touch_row();

-- Only the tender's active Chair may confirm or reopen (rule 2: read from tender_members). The
-- check uses the signed-in person, so the AI-ingest connection (no person) can never confirm.
CREATE FUNCTION baselines_chair_only() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NOT EXISTS (SELECT 1 FROM tender_members m WHERE m.tender_id = NEW.tender_id
                   AND m.user_id = NEW.confirmed_by AND m.is_chair AND m.status = 'active') THEN
      RAISE EXCEPTION 'only the Chair of the tender confirms a baseline' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
  END IF;
  -- Update: the snapshot columns never change; reopening is the Chair's.
  IF NEW.tender_id <> OLD.tender_id OR NEW.version_no <> OLD.version_no
     OR NEW.requirement_count <> OLD.requirement_count OR NEW.confirmed_by <> OLD.confirmed_by
     OR NEW.confirmed_at <> OLD.confirmed_at THEN
    RAISE EXCEPTION 'a confirmed baseline is locked' USING ERRCODE = '23000';
  END IF;
  IF NEW.status = 'reopened' AND OLD.status = 'confirmed' THEN
    IF NEW.reopened_by IS NULL OR NOT EXISTS (
         SELECT 1 FROM tender_members m WHERE m.tender_id = NEW.tender_id
         AND m.user_id = NEW.reopened_by AND m.is_chair AND m.status = 'active') THEN
      RAISE EXCEPTION 'only the Chair of the tender reopens a baseline' USING ERRCODE = '23000';
    END IF;
    IF NEW.reopen_reason IS NULL OR char_length(btrim(NEW.reopen_reason)) < 10 THEN
      RAISE EXCEPTION 'reopening needs a reason of at least 10 characters' USING ERRCODE = '23000';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER baselines_chair BEFORE INSERT OR UPDATE ON baselines
    FOR EACH ROW EXECUTE FUNCTION baselines_chair_only();

-- Is a baseline of the tender confirmed (requirements locked)?
CREATE FUNCTION app_baseline_locked(t uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM baselines b WHERE b.tender_id = t AND b.status = 'confirmed') $$;

-- ---- requirements ---------------------------------------------------------------------------------
CREATE TABLE requirements (
    id                          uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id                   uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    display_no                  text NOT NULL,
    text                        text NOT NULL CHECK (btrim(text) <> ''),
    category_id                 uuid REFERENCES requirement_categories (id) ON DELETE RESTRICT,
    importance                  text NOT NULL CHECK (importance IN ('mandatory', 'optional')),
    acceptance_criteria         text NOT NULL DEFAULT '',
    origin                      text NOT NULL CHECK (origin IN ('ai', 'manual')),
    review_state                text NOT NULL DEFAULT 'unreviewed'
                                CHECK (review_state IN ('unreviewed', 'reviewed', 'confirmed')),
    -- The producer's own id of an AI candidate: a redelivered batch inserts nothing twice.
    source_item_id              uuid,
    extraction_ai_run_id        uuid REFERENCES ai_runs (id) ON DELETE RESTRICT,
    ai_original                 jsonb,
    delete_reason               text CHECK (delete_reason IN ('duplicate', 'out_of_scope',
                                'not_a_requirement', 'other')),
    duplicate_of_requirement_id uuid REFERENCES requirements (id) ON DELETE RESTRICT,
    merged_into_requirement_id  uuid REFERENCES requirements (id) ON DELETE RESTRICT,
    split_from_requirement_id   uuid REFERENCES requirements (id) ON DELETE RESTRICT,
    last_changed_by             uuid REFERENCES users (id) ON DELETE RESTRICT,
    last_changed_at             timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    row_version                 integer NOT NULL DEFAULT 1,
    deleted_at                  timestamptz,
    deleted_by                  uuid REFERENCES users (id) ON DELETE RESTRICT,
    UNIQUE (tender_id, display_no),
    CHECK (delete_reason IS NULL OR deleted_at IS NOT NULL),
    CHECK (delete_reason <> 'duplicate' OR duplicate_of_requirement_id IS NOT NULL
           OR deleted_at IS NULL),
    CHECK (origin <> 'ai' OR source_item_id IS NOT NULL)
);
CREATE UNIQUE INDEX requirements_item ON requirements (tender_id, source_item_id)
    WHERE source_item_id IS NOT NULL;
CREATE INDEX requirements_state ON requirements (tender_id, review_state) WHERE deleted_at IS NULL;
CREATE INDEX requirements_category ON requirements (tender_id, category_id) WHERE deleted_at IS NULL;
CREATE INDEX requirements_text_search ON requirements USING gin (to_tsvector('english', text));
CREATE TRIGGER requirements_touch BEFORE INSERT OR UPDATE ON requirements
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- REQ-014: the number is allocated inside the insert under a lock on the tender, from every row
-- that ever existed (deleted ones included), so a number is never reused.
CREATE FUNCTION requirements_assign_no() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  n integer;
BEGIN
  IF NEW.display_no IS NULL OR NEW.display_no = '' THEN
    PERFORM pg_advisory_xact_lock(hashtextextended('requirements:' || NEW.tender_id::text, 0));
    SELECT coalesce(max(substring(display_no FROM 5)::integer), 0) + 1 INTO n
      FROM requirements WHERE tender_id = NEW.tender_id;
    NEW.display_no := 'REQ-' || lpad(n::text, 3, '0');
  END IF;
  RETURN NEW;
END $$;
ALTER TABLE requirements ALTER COLUMN display_no DROP NOT NULL;
CREATE TRIGGER requirements_number BEFORE INSERT ON requirements
    FOR EACH ROW EXECUTE FUNCTION requirements_assign_no();
-- The column stays NOT NULL in effect: the trigger fills it before the row is stored.
ALTER TABLE requirements ADD CONSTRAINT requirements_display_no_set CHECK (display_no IS NOT NULL);

CREATE TABLE requirement_sources (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    requirement_id      uuid NOT NULL REFERENCES requirements (id) ON DELETE RESTRICT,
    document_version_id uuid NOT NULL REFERENCES document_versions (id) ON DELETE RESTRICT,
    passage_id          uuid REFERENCES passages (id) ON DELETE RESTRICT,
    page_no             integer CHECK (page_no >= 1),
    quote               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (requirement_id, document_version_id, passage_id)
);
CREATE INDEX requirement_sources_requirement ON requirement_sources (requirement_id);

CREATE TABLE requirement_flags (
    id                     uuid PRIMARY KEY DEFAULT uuidv7(),
    requirement_id         uuid NOT NULL REFERENCES requirements (id) ON DELETE RESTRICT,
    flag_type              text NOT NULL CHECK (flag_type IN ('possible_duplicate', 'compound',
                           'low_confidence', 'conflict')),
    detail                 text,
    related_requirement_id uuid REFERENCES requirements (id) ON DELETE RESTRICT,
    raised_by_ai_run_id    uuid REFERENCES ai_runs (id) ON DELETE RESTRICT,
    resolved_at            timestamptz,
    resolved_by            uuid REFERENCES users (id) ON DELETE RESTRICT,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))
);
CREATE INDEX requirement_flags_open ON requirement_flags (requirement_id) WHERE resolved_at IS NULL;

-- ---- snapshot ----------------------------------------------------------------------------------------
CREATE TABLE baseline_requirements (
    baseline_id         uuid NOT NULL REFERENCES baselines (id) ON DELETE RESTRICT,
    requirement_id      uuid NOT NULL REFERENCES requirements (id) ON DELETE RESTRICT,
    display_no          text NOT NULL,
    text                text NOT NULL,
    category_name       text,
    importance          text NOT NULL,
    acceptance_criteria text NOT NULL DEFAULT '',
    sources             jsonb NOT NULL DEFAULT '[]'::jsonb,
    content_hash        text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (baseline_id, requirement_id)
);
CREATE TRIGGER baseline_requirements_immutable BEFORE UPDATE OR DELETE ON baseline_requirements
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- ---- scoring --------------------------------------------------------------------------------------------
CREATE TABLE scoring_models (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tender_id      uuid NOT NULL REFERENCES tenders (id) ON DELETE RESTRICT,
    baseline_id    uuid REFERENCES baselines (id) ON DELETE RESTRICT,
    points_fully   numeric(6, 2) NOT NULL DEFAULT 1.0 CHECK (points_fully >= 0),
    points_partially numeric(6, 2) NOT NULL DEFAULT 0.5 CHECK (points_partially >= 0),
    points_non     numeric(6, 2) NOT NULL DEFAULT 0 CHECK (points_non >= 0),
    mandatory_rule text NOT NULL DEFAULT 'flag_only' CHECK (mandatory_rule IN ('flag_only')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    row_version    integer NOT NULL DEFAULT 1,
    CHECK (points_fully >= points_partially AND points_partially >= points_non)
);
-- One editable model per tender; confirmed copies carry their baseline.
CREATE UNIQUE INDEX scoring_models_editable ON scoring_models (tender_id) WHERE baseline_id IS NULL;
CREATE UNIQUE INDEX scoring_models_locked ON scoring_models (baseline_id) WHERE baseline_id IS NOT NULL;
CREATE TRIGGER scoring_models_touch BEFORE INSERT OR UPDATE ON scoring_models
    FOR EACH ROW EXECUTE FUNCTION touch_row();
CREATE FUNCTION scoring_models_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.baseline_id IS NOT NULL THEN
    RAISE EXCEPTION 'a confirmed scoring model is locked' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER scoring_models_locked_rows BEFORE UPDATE OR DELETE ON scoring_models
    FOR EACH ROW EXECUTE FUNCTION scoring_models_lock();

CREATE TABLE scoring_category_weights (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    scoring_model_id uuid NOT NULL REFERENCES scoring_models (id) ON DELETE RESTRICT,
    category_id      uuid NOT NULL REFERENCES requirement_categories (id) ON DELETE RESTRICT,
    weight           numeric(8, 4) NOT NULL CHECK (weight > 0),
    UNIQUE (scoring_model_id, category_id)
);
CREATE FUNCTION scoring_weights_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  locked boolean;
BEGIN
  SELECT baseline_id IS NOT NULL INTO locked FROM scoring_models
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.scoring_model_id ELSE NEW.scoring_model_id END;
  IF locked THEN
    RAISE EXCEPTION 'the weights of a confirmed scoring model are locked' USING ERRCODE = '23000';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER scoring_weights_locked_rows BEFORE UPDATE OR DELETE ON scoring_category_weights
    FOR EACH ROW EXECUTE FUNCTION scoring_weights_lock();

-- ---- allowed status changes ---------------------------------------------------------------------------
INSERT INTO status_transitions (table_name, column_name, from_value, to_value, caused_by) VALUES
 ('requirements', 'review_state', NULL, 'unreviewed', 'A requirement is extracted or added'),
 ('requirements', 'review_state', NULL, 'reviewed', 'A member adds a requirement by hand'),
 ('requirements', 'review_state', 'unreviewed', 'reviewed', 'A member edits or marks it reviewed'),
 ('requirements', 'review_state', 'reviewed', 'confirmed', 'The Chair confirms the baseline'),
 ('requirements', 'review_state', 'confirmed', 'reviewed', 'A member edits after the Chair reopened'),
 ('baselines', 'status', NULL, 'confirmed', 'The Chair confirms the requirements'),
 ('baselines', 'status', 'confirmed', 'reopened', 'The Chair reopens with a reason'),
 ('baselines', 'status', 'reopened', 'superseded', 'The Chair confirms the next version');
CREATE TRIGGER requirements_review_state BEFORE INSERT OR UPDATE OF review_state ON requirements
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('review_state');
CREATE TRIGGER baselines_status BEFORE INSERT OR UPDATE OF status ON baselines
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');

-- ---- row-level security -----------------------------------------------------------------------------------
ALTER TABLE requirements             ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement_sources      ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement_flags        ENABLE ROW LEVEL SECURITY;
ALTER TABLE baselines                ENABLE ROW LEVEL SECURITY;
ALTER TABLE baseline_requirements    ENABLE ROW LEVEL SECURITY;
ALTER TABLE scoring_models           ENABLE ROW LEVEL SECURITY;
ALTER TABLE scoring_category_weights ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_runs                  ENABLE ROW LEVEL SECURITY;

-- Who may edit: an active member of the tender while no baseline is confirmed (admin and
-- Observer read only).
CREATE FUNCTION app_can_edit_requirements(t uuid) RETURNS boolean LANGUAGE sql STABLE
SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT app_role() = 'member' AND app_is_member_of(t) AND NOT app_baseline_locked(t) $$;

CREATE POLICY requirements_read ON requirements FOR SELECT TO tender_app
    USING (app_can_use_tender(tender_id));
CREATE POLICY requirements_insert ON requirements FOR INSERT TO tender_app
    WITH CHECK (app_can_edit_requirements(tender_id) AND origin = 'manual');
-- Edits, deletes and review marks; the Chair's baseline confirmation also moves review_state,
-- which is why a confirming Chair updates through the same policy before the baseline row exists.
CREATE POLICY requirements_update ON requirements FOR UPDATE TO tender_app
    USING (app_can_edit_requirements(tender_id)) WITH CHECK (app_can_edit_requirements(tender_id));

CREATE POLICY requirement_sources_read ON requirement_sources FOR SELECT TO tender_app
    USING (EXISTS (SELECT 1 FROM requirements r WHERE r.id = requirement_id AND app_can_use_tender(r.tender_id)));
CREATE POLICY requirement_sources_insert ON requirement_sources FOR INSERT TO tender_app
    WITH CHECK (EXISTS (SELECT 1 FROM requirements r WHERE r.id = requirement_id
                        AND app_can_edit_requirements(r.tender_id)));
CREATE POLICY requirement_flags_read ON requirement_flags FOR SELECT TO tender_app
    USING (EXISTS (SELECT 1 FROM requirements r WHERE r.id = requirement_id AND app_can_use_tender(r.tender_id)));
CREATE POLICY requirement_flags_insert ON requirement_flags FOR INSERT TO tender_app
    WITH CHECK (EXISTS (SELECT 1 FROM requirements r WHERE r.id = requirement_id
                        AND app_can_edit_requirements(r.tender_id)));
CREATE POLICY requirement_flags_update ON requirement_flags FOR UPDATE TO tender_app
    USING (EXISTS (SELECT 1 FROM requirements r WHERE r.id = requirement_id
                   AND app_can_edit_requirements(r.tender_id)))
    WITH CHECK (EXISTS (SELECT 1 FROM requirements r WHERE r.id = requirement_id
                        AND app_can_edit_requirements(r.tender_id)));

CREATE POLICY baselines_read ON baselines FOR SELECT TO tender_app USING (app_can_use_tender(tender_id));
CREATE POLICY baselines_insert ON baselines FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id) AND confirmed_by = app_user_id());
CREATE POLICY baselines_update ON baselines FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND app_is_chair_of(tender_id))
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id));

CREATE POLICY baseline_requirements_read ON baseline_requirements FOR SELECT TO tender_app
    USING (EXISTS (SELECT 1 FROM baselines b WHERE b.id = baseline_id AND app_can_use_tender(b.tender_id)));
CREATE POLICY baseline_requirements_insert ON baseline_requirements FOR INSERT TO tender_app
    WITH CHECK (EXISTS (SELECT 1 FROM baselines b WHERE b.id = baseline_id
                        AND app_role() = 'member' AND app_is_chair_of(b.tender_id)));

CREATE POLICY scoring_models_read ON scoring_models FOR SELECT TO tender_app
    USING (app_can_use_tender(tender_id));
CREATE POLICY scoring_models_write ON scoring_models FOR INSERT TO tender_app
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id));
CREATE POLICY scoring_models_update ON scoring_models FOR UPDATE TO tender_app
    USING (app_role() = 'member' AND app_is_chair_of(tender_id) AND baseline_id IS NULL)
    WITH CHECK (app_role() = 'member' AND app_is_chair_of(tender_id));
CREATE POLICY scoring_weights_read ON scoring_category_weights FOR SELECT TO tender_app
    USING (EXISTS (SELECT 1 FROM scoring_models s WHERE s.id = scoring_model_id
                   AND app_can_use_tender(s.tender_id)));
CREATE POLICY scoring_weights_insert ON scoring_category_weights FOR INSERT TO tender_app
    WITH CHECK (EXISTS (SELECT 1 FROM scoring_models s WHERE s.id = scoring_model_id
                        AND app_role() = 'member' AND app_is_chair_of(s.tender_id)));
CREATE POLICY scoring_weights_update ON scoring_category_weights FOR UPDATE TO tender_app
    USING (EXISTS (SELECT 1 FROM scoring_models s WHERE s.id = scoring_model_id
                   AND app_role() = 'member' AND app_is_chair_of(s.tender_id)))
    WITH CHECK (EXISTS (SELECT 1 FROM scoring_models s WHERE s.id = scoring_model_id
                        AND app_role() = 'member' AND app_is_chair_of(s.tender_id)));

-- AI runs: readable by the committee through the job; written by the AI-ingest connection only.
CREATE POLICY ai_runs_read ON ai_runs FOR SELECT TO tender_app USING (app_can_use_job(job_id));

-- ---- the AI-ingest connection ----------------------------------------------------------------------------
-- Inserts extracted requirements with their sources and flags and their run record. It cannot
-- edit a requirement afterwards and reads nothing a member decides (no evaluation tables).
GRANT SELECT ON requirements, requirement_sources, requirement_flags, ai_runs, requirement_categories,
                baselines TO tender_ai_ingest;
GRANT INSERT ON requirements, requirement_sources, requirement_flags, ai_runs TO tender_ai_ingest;
GRANT UPDATE (finished_at, outcome, warning_count) ON ai_runs TO tender_ai_ingest;
CREATE POLICY ingest_read_requirements ON requirements FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_requirements ON requirements FOR INSERT TO tender_ai_ingest
    WITH CHECK (origin = 'ai' AND review_state = 'unreviewed');
CREATE POLICY ingest_read_sources ON requirement_sources FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_sources ON requirement_sources FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_read_flags ON requirement_flags FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_flags ON requirement_flags FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_read_runs ON ai_runs FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_insert_runs ON ai_runs FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_update_runs ON ai_runs FOR UPDATE TO tender_ai_ingest USING (true) WITH CHECK (true);
CREATE POLICY ingest_read_baselines ON baselines FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_read_categories ON requirement_categories FOR SELECT TO tender_ai_ingest USING (true);
