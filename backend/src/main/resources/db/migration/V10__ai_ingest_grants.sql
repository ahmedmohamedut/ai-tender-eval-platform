-- P1-2: what the AI-ingest connection may do (implementation guide 5.4, PI 5.6, PI 5.7).
--
-- The backend's status and result handlers use this role because they run without a signed-in
-- user. It inserts pages, sections and passages and moves the named status columns of jobs,
-- job steps and document versions. It reads nothing of a member's own decisions (those tables
-- arrive later and are never granted to it), and it cannot see users, tenders or committees.
--
-- Privileges are column-level where the role updates, so it cannot rewrite what a job is
-- about or who started it.

GRANT SELECT ON jobs, job_steps, job_scope_items, document_versions, documents,
                document_pages, document_sections, passages TO tender_ai_ingest;
GRANT INSERT ON document_pages, document_sections, passages, job_steps TO tender_ai_ingest;
GRANT INSERT ON audit_events TO tender_ai_ingest;

GRANT UPDATE (status, current_step, progress_percent, started_at, finished_at, failure_code,
              failure_message, last_status_seq, last_status_at, result_parts, counts, warnings)
    ON jobs TO tender_ai_ingest;
GRANT UPDATE (status, started_at, finished_at) ON job_steps TO tender_ai_ingest;
GRANT UPDATE (status, failure_reason, page_count) ON document_versions TO tender_ai_ingest;

CREATE POLICY ingest_read_jobs ON jobs FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_update_jobs ON jobs FOR UPDATE TO tender_ai_ingest USING (true) WITH CHECK (true);
CREATE POLICY ingest_read_steps ON job_steps FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_write_steps ON job_steps FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_update_steps ON job_steps FOR UPDATE TO tender_ai_ingest USING (true) WITH CHECK (true);
CREATE POLICY ingest_read_scope ON job_scope_items FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_read_documents ON documents FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_read_versions ON document_versions FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_update_versions ON document_versions FOR UPDATE TO tender_ai_ingest
    USING (true) WITH CHECK (true);
CREATE POLICY ingest_read_pages ON document_pages FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_write_pages ON document_pages FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_read_sections ON document_sections FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_write_sections ON document_sections FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_read_passages ON passages FOR SELECT TO tender_ai_ingest USING (true);
CREATE POLICY ingest_write_passages ON passages FOR INSERT TO tender_ai_ingest WITH CHECK (true);
CREATE POLICY ingest_audit_insert ON audit_events FOR INSERT TO tender_ai_ingest WITH CHECK (source <> 'person');
