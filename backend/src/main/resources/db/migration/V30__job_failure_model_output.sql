-- A model that answered but whose answers were cut off or unreadable is not an outage
-- (AI 7.2): its own job failure code. Additive: no policy or status-change trigger is touched.
ALTER TABLE jobs DROP CONSTRAINT jobs_failure_code_check;
ALTER TABLE jobs
    ADD CONSTRAINT jobs_failure_code_check
    CHECK (failure_code IN ('password_protected', 'no_selectable_text', 'unsupported_format',
                            'source_unavailable', 'index_unavailable', 'llm_unavailable',
                            'model_output_unusable', 'no_content_in_scope', 'timeout', 'other'));
