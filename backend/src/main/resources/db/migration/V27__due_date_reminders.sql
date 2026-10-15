-- P5-5 (P85): due-date reminders and the audit action of a manual reminder.

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
  def := replace(def, '''export_created''', '''export_created'', ''reminders_sent''');
  EXECUTE format('ALTER TABLE audit_events DROP CONSTRAINT %I', cname);
  EXECUTE format('ALTER TABLE audit_events ADD CONSTRAINT %I %s', cname, def);
END $$;

-- The reminder timer runs without a signed-in person, as the mailer does (V24). For every stage
-- of a tender that is its current stage, has a due date and a lead time, and whose reminder window
-- has opened, each active committee member gets one due_date_reminder; last_reminded_at keeps it
-- to one per due date (a later due date reopens the window). Returns what it created so the
-- caller can push it. Values are the stage and the date, never names, grades or text (rule 10).
CREATE FUNCTION app_run_due_reminders()
RETURNS TABLE (notification_id uuid, user_id uuid, tender_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  d record;
  m record;
  new_id uuid;
  want_email boolean;
BEGIN
  IF app_user_id() IS NOT NULL THEN
    RAISE EXCEPTION 'due-date reminders run as the system' USING ERRCODE = '42501';
  END IF;
  FOR d IN
    SELECT s.id, s.tender_id, s.stage, s.due_date
      FROM tender_stage_dates s JOIN tenders t ON t.id = s.tender_id
     WHERE s.removed_at IS NULL AND t.deleted_at IS NULL AND t.status = s.stage
       AND s.reminder_lead_days IS NOT NULL
       AND s.due_date - s.reminder_lead_days <= current_date
       AND (s.last_reminded_at IS NULL
            OR s.last_reminded_at < (s.due_date - s.reminder_lead_days)::timestamptz)
     FOR UPDATE OF s SKIP LOCKED
  LOOP
    FOR m IN
      SELECT tm.user_id FROM tender_members tm JOIN users u ON u.id = tm.user_id
       WHERE tm.tender_id = d.tender_id AND tm.status = 'active' AND u.status = 'active'
    LOOP
      SELECT p.email_enabled INTO want_email FROM notification_preferences p
       WHERE p.user_id = m.user_id AND p.notification_type = 'due_date_reminder';
      INSERT INTO notifications (user_id, tender_id, notification_type, object_type, object_id,
                                 params, email_status)
      VALUES (m.user_id, d.tender_id, 'due_date_reminder', 'tender', d.tender_id,
              jsonb_build_object('stage', d.stage, 'due_date', d.due_date),
              CASE WHEN coalesce(want_email, false) THEN 'pending' ELSE 'not_requested' END)
      RETURNING id INTO new_id;
      notification_id := new_id;
      user_id := m.user_id;
      tender_id := d.tender_id;
      RETURN NEXT;
    END LOOP;
    UPDATE tender_stage_dates SET last_reminded_at = now() WHERE id = d.id;
  END LOOP;
END $$;
REVOKE ALL ON FUNCTION app_run_due_reminders() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_run_due_reminders() TO tender_app;

-- The mailer needs the address of the recipient of a pending email, and nothing else of the user.
-- System context only (no signed-in person), and only for a notification waiting for delivery.
CREATE FUNCTION app_notification_recipient(p_id uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
  SELECT u.email FROM notifications n JOIN users u ON u.id = n.user_id
   WHERE n.id = p_id AND n.email_status = 'pending' AND u.status = 'active'
     AND app_user_id() IS NULL
$$;
REVOKE ALL ON FUNCTION app_notification_recipient(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_notification_recipient(uuid) TO tender_app;
