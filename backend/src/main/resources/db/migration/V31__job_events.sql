-- Job center details (PG-17, UX-JOB-03, UX-JOB-05): a short activity log per job, written by the
-- backend from the status messages it receives and from its own timers. Workers have no database
-- access and the log never carries document text or names (rule 10): messages come from a fixed
-- vocabulary. Additive: a new table; no existing policy or status-change trigger is touched.
CREATE TABLE job_events (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    job_id     uuid NOT NULL REFERENCES jobs (id) ON DELETE RESTRICT,
    at         timestamptz NOT NULL DEFAULT now(),
    level      text NOT NULL DEFAULT 'info' CHECK (level IN ('info', 'warning', 'error')),
    step       text,
    message    text NOT NULL,
    -- Delivery is at least once: the same fact is stored once per job.
    dedup_key  text NOT NULL,
    UNIQUE (job_id, dedup_key)
);
CREATE INDEX job_events_job_at ON job_events (job_id, at, id);

ALTER TABLE job_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_events_read ON job_events FOR SELECT TO tender_app USING (app_can_use_job(job_id));
CREATE POLICY ingest_read_events ON job_events FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_write_events ON job_events FOR INSERT TO tender_ai_ingest WITH CHECK (true);

GRANT SELECT ON job_events TO tender_ai_ingest;
GRANT INSERT ON job_events TO tender_ai_ingest;
