-- P3-4: the result handler adds the low-confidence flag from the administrator's threshold.
-- The AI-ingest connection may read the settings of the "ai." group and nothing else.
GRANT SELECT ON settings TO tender_ai_ingest;
CREATE POLICY ingest_read_ai_settings ON settings FOR SELECT TO tender_ai_ingest
    USING (key LIKE 'ai.%');
