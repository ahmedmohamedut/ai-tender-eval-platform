-- P3-3: the Chair moves a tender's stage when confirming or reopening a baseline (DM Allowed
-- status changes: requirements to evaluation, evaluation or consolidation to requirements). The
-- tenders table is administrator-write, so the two moves go through this definer function, which
-- checks that the caller is the active Chair of the tender (rule 2) and refuses every other move.
CREATE FUNCTION app_chair_move_stage(p_tender uuid, p_to text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
  current_status text;
BEGIN
  IF NOT app_is_chair_of(p_tender) THEN
    RAISE EXCEPTION 'only the Chair moves this stage' USING ERRCODE = '42501';
  END IF;
  SELECT status INTO current_status FROM tenders WHERE id = p_tender FOR UPDATE;
  IF NOT ((current_status = 'requirements' AND p_to = 'evaluation')
          OR (current_status IN ('evaluation', 'consolidation') AND p_to = 'requirements')) THEN
    RAISE EXCEPTION 'the Chair cannot move % to %', current_status, p_to USING ERRCODE = '23514';
  END IF;
  UPDATE tenders SET status = p_to WHERE id = p_tender;
END $$;
REVOKE ALL ON FUNCTION app_chair_move_stage(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_chair_move_stage(uuid, text) TO tender_app;
