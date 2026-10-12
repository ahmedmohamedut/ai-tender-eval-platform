-- P1-1: allowed status changes (DM Allowed status changes). One table holds the allowed moves
-- and one generic trigger function enforces them on every status column, so the data model
-- table and the database cannot drift. Later phases add their rows in their migrations.

CREATE TABLE status_transitions (
    table_name  text NOT NULL,
    column_name text NOT NULL,
    from_value  text,            -- NULL means "on insert"
    to_value    text NOT NULL,
    caused_by   text NOT NULL,
    UNIQUE NULLS NOT DISTINCT (table_name, column_name, from_value, to_value)
);

CREATE FUNCTION enforce_status_transition() RETURNS trigger LANGUAGE plpgsql
SET search_path = public, pg_temp AS $$
DECLARE
  col   text := TG_ARGV[0];
  new_v text := to_jsonb(NEW) ->> col;
  old_v text;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    old_v := to_jsonb(OLD) ->> col;
    IF old_v IS NOT DISTINCT FROM new_v THEN
      RETURN NEW;
    END IF;
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM status_transitions t
      WHERE t.table_name = TG_TABLE_NAME AND t.column_name = col
        AND t.from_value IS NOT DISTINCT FROM old_v AND t.to_value = new_v) THEN
    RAISE EXCEPTION 'status change % -> % is not allowed on %.%',
      coalesce(old_v, '(new row)'), new_v, TG_TABLE_NAME, col USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

INSERT INTO status_transitions (table_name, column_name, from_value, to_value, caused_by) VALUES
 ('tenders', 'status', NULL, 'draft', 'The administrator creates the tender'),
 ('tenders', 'status', 'draft', 'requirements', 'The admin opens the tender to the committee'),
 ('tenders', 'status', 'requirements', 'evaluation', 'The Chair confirms the baseline'),
 ('tenders', 'status', 'evaluation', 'consolidation', 'Every accepted vendor has all evaluations submitted or excluded'),
 ('tenders', 'status', 'consolidation', 'finalized', 'The Chair finalizes the report'),
 ('tenders', 'status', 'evaluation', 'requirements', 'The Chair reopens the baseline'),
 ('tenders', 'status', 'consolidation', 'requirements', 'The Chair reopens the baseline'),
 ('tenders', 'status', 'finalized', 'consolidation', 'The Chair reopens the report'),

 ('document_versions', 'status', NULL, 'uploading', 'An upload starts'),
 ('document_versions', 'status', 'uploading', 'processing', 'The upload completes'),
 ('document_versions', 'status', 'processing', 'ready', 'The processing job ends'),
 ('document_versions', 'status', 'processing', 'failed', 'The processing job ends'),
 ('document_versions', 'status', 'failed', 'processing', 'Retry from the job center'),

 ('proposal_versions', 'status', NULL, 'uploaded', 'The administrator uploads the proposal'),
 ('proposal_versions', 'status', 'uploaded', 'in_screening', 'The first checklist item or recommendation is saved'),
 ('proposal_versions', 'status', 'in_screening', 'accepted', 'The Chair confirms the decision'),
 ('proposal_versions', 'status', 'in_screening', 'rejected', 'The Chair confirms the decision'),
 ('proposal_versions', 'status', 'in_screening', 'resubmission_requested', 'The Chair confirms the decision'),
 ('proposal_versions', 'status', 'rejected', 'accepted', 'The Chair reinstates the vendor with a note'),
 ('proposal_versions', 'status', 'accepted', 'evaluating', 'The evaluation job starts'),
 ('proposal_versions', 'status', 'evaluating', 'evaluated', 'The evaluation job completes'),
 ('proposal_versions', 'status', 'uploaded', 'superseded', 'A newer proposal version is added'),
 ('proposal_versions', 'status', 'in_screening', 'superseded', 'A newer proposal version is added'),
 ('proposal_versions', 'status', 'accepted', 'superseded', 'A newer proposal version is added'),
 ('proposal_versions', 'status', 'rejected', 'superseded', 'A newer proposal version is added'),
 ('proposal_versions', 'status', 'resubmission_requested', 'superseded', 'A newer proposal version is added'),
 ('proposal_versions', 'status', 'evaluating', 'superseded', 'A newer proposal version is added'),
 ('proposal_versions', 'status', 'evaluated', 'superseded', 'A newer proposal version is added'),

 ('jobs', 'status', NULL, 'queued', 'The backend creates the job'),
 ('jobs', 'status', 'queued', 'running', 'A worker picks the job up'),
 ('jobs', 'status', 'running', 'completed', 'The job ends'),
 ('jobs', 'status', 'running', 'completed_with_warnings', 'The job ends'),
 ('jobs', 'status', 'running', 'failed', 'The job ends'),
 ('jobs', 'status', 'queued', 'cancelled', 'The starter or the Chair cancels'),
 ('jobs', 'status', 'running', 'cancelled', 'The starter or the Chair cancels'),
 -- Not in the data model table: PI 6.2 step 2 marks a job failed at once when the broker
 -- refuses the publish, and PI 5.8 fails a job that never started (docs/decisions.md).
 ('jobs', 'status', 'queued', 'failed', 'The command could not be published, or the job never started');

CREATE TRIGGER tenders_status BEFORE INSERT OR UPDATE OF status ON tenders
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');
CREATE TRIGGER document_versions_status BEFORE INSERT OR UPDATE OF status ON document_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');
CREATE TRIGGER proposal_versions_status BEFORE INSERT OR UPDATE OF status ON proposal_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');
CREATE TRIGGER jobs_status BEFORE INSERT OR UPDATE OF status ON jobs
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition('status');

-- The transition table is reference data: the application roles read it, never write it.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON status_transitions FROM tender_app;
