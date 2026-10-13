-- P3-2: the category list is administrator-managed reference data; its writes are audited like
-- every other write, with two actions the data model did not name (DM updated in the same commit).
DO $$
DECLARE
  def text;
  cname text;
BEGIN
  SELECT c.conname, pg_get_constraintdef(c.oid) INTO cname, def
    FROM pg_constraint c
   WHERE c.conrelid = 'audit_events'::regclass AND c.contype = 'c'
     AND pg_get_constraintdef(c.oid) LIKE '%tender_created%';
  IF cname IS NULL THEN
    RAISE EXCEPTION 'the audit action constraint was not found';
  END IF;
  def := replace(def, '''job_retried''', '''job_retried'', ''category_created'', ''category_updated''');
  EXECUTE format('ALTER TABLE audit_events DROP CONSTRAINT %I', cname);
  EXECUTE format('ALTER TABLE audit_events ADD CONSTRAINT %I %s', cname, def);
END $$;
