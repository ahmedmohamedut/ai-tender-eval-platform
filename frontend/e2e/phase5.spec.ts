import { expect, test } from "@playwright/test";
import { axeViolations, chooseOption, ensureUser, env, newContext, signIn, sql } from "./support";

/**
 * The Phase 5 journey against the real stack: two members have submitted an evaluated proposal
 * (rows written the way the result handler writes them, synthetic text only) and disagree on one
 * requirement. The Chair records the final grade and finalizes the report; a member and the
 * Observer read it only after that; reopening starts version 2 and keeps version 1 readable.
 */
test("the Chair settles a disagreement, finalizes, and reopening creates version 2", async ({
  browser,
}) => {
  test.setTimeout(240_000);
  ensureUser("e2e-admin", "E2E Admin", "admin");
  const seed = env("DEV_SEED_USER_PASSWORD");
  const stamp = Date.now();
  const adminId = sql(`select id from users where email = 'e2e-admin@example.test'`)[0];
  const m1 = sql(`select id from users where email = 'member01@example.test'`)[0];
  const m2 = sql(`select id from users where email = 'member02@example.test'`)[0];
  const one = (q: string) => sql(q)[0].split("\n")[0];

  const tender = one(
    `insert into tenders (name, created_by, status) values ('E2E report ${stamp}', '${adminId}', 'draft') returning id`,
  );
  sql(`update tenders set status = 'requirements' where id = '${tender}'`);
  sql(`update tenders set status = 'evaluation' where id = '${tender}'`);
  sql(
    `insert into tender_members (tender_id, user_id, is_chair) values ('${tender}', '${m1}', true)`,
  );
  sql(`insert into tender_members (tender_id, user_id) values ('${tender}', '${m2}')`);
  const r1 = one(
    `insert into requirements (tender_id, text, importance, origin) values ('${tender}', 'The supplier shall encrypt all customer data at rest.', 'mandatory', 'manual') returning id`,
  );
  const r2 = one(
    `insert into requirements (tender_id, text, importance, origin) values ('${tender}', 'The supplier must provide support during working hours.', 'mandatory', 'manual') returning id`,
  );
  const baseline = one(
    `insert into baselines (tender_id, version_no, requirement_count, confirmed_by) values ('${tender}', 1, 2, '${m1}') returning id`,
  );
  let n = 0;
  for (const r of [r1, r2]) {
    n += 1;
    sql(
      `insert into baseline_requirements (baseline_id, requirement_id, display_no, text, importance, content_hash) select '${baseline}', id, 'REQ-00${n}', text, importance, repeat('c', 64) from requirements where id = '${r}'`,
    );
  }
  sql(
    `insert into scoring_models (tender_id, baseline_id, points_fully, points_partially, points_non, mandatory_rule) values ('${tender}', '${baseline}', 1, 0.5, 0, 'flag_only')`,
  );
  const vendor = one(
    `insert into vendors (name, normalized_name) values ('E2E Vendor ${stamp}', 'e2e vendor ${stamp}') returning id`,
  );
  const tv = one(
    `insert into tender_vendors (tender_id, vendor_id, added_by) values ('${tender}', '${vendor}', '${adminId}') returning id`,
  );
  const pv = one(
    `insert into proposal_versions (tender_vendor_id, version_no, uploaded_by) values ('${tv}', 1, '${adminId}') returning id`,
  );
  const doc = one(
    `insert into documents (tender_id, doc_type, title) values ('${tender}', 'proposal', 'Proposal') returning id`,
  );
  const dv = sql(`select gen_random_uuid()`)[0];
  sql(
    `insert into document_versions (id, document_id, version_no, file_name, file_format, size_bytes, sha256, storage_key, status, uploaded_by) values ('${dv}', '${doc}', 1, 'p.pdf', 'pdf', 10, repeat('a', 64), 'originals/${tender}/${doc}/${dv}.pdf', 'uploading', '${adminId}')`,
  );
  sql(
    `insert into proposal_version_files (proposal_version_id, document_version_id, file_role) values ('${pv}', '${dv}', 'main')`,
  );
  const passage = one(
    `insert into passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end, content_hash) values (gen_random_uuid(), '${dv}', 7, 1, 'We encrypt all customer data at rest using AES-256.', '[]'::jsonb, 0, 50, repeat('b', 64)) returning id`,
  );
  for (const u of [m1, m2]) {
    sql(
      `insert into conflict_declarations (tender_vendor_id, user_id, has_conflict) values ('${tv}', '${u}', false)`,
    );
  }
  sql(`update proposal_versions set status = 'in_screening' where id = '${pv}'`);
  sql(`update proposal_versions set status = 'accepted' where id = '${pv}'`);
  const job = one(
    `insert into jobs (tender_id, job_type, target_type, target_id) values ('${tender}', 'evaluation', 'proposal_version', '${pv}') returning id`,
  );
  const run = one(
    `insert into ai_runs (job_id, run_type, model_name, prompt_version) values ('${job}', 'evaluation', 'stub', 'e-1') returning id`,
  );
  sql(`update jobs set status = 'running', started_at = now() where id = '${job}'`);
  sql(
    `with g as (insert into ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id, outcome, grade, rationale_summary, rationale, confidence, requirement_hash) values (gen_random_uuid(), '${run}', '${pv}', '${baseline}', '${r1}', 'graded', 'fully_compliant', 'Stated', 'The proposal states that all customer data is encrypted at rest.', 0.9, repeat('c', 64)) returning id) insert into grade_citations (ai_grade_id, citation_no, document_version_id, passage_id, page_no, excerpt) select id, 1, '${dv}', '${passage}', 7, 'We encrypt all customer data at rest' from g`,
  );
  sql(
    `insert into ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id, outcome, grade, non_compliance_reason, requirement_hash) values (gen_random_uuid(), '${run}', '${pv}', '${baseline}', '${r2}', 'graded', 'non_compliant', 'not_addressed', repeat('c', 64))`,
  );
  sql(`update jobs set status = 'completed', finished_at = now() where id = '${job}'`);
  sql(`select count(*) from ingest_sync_member_evaluations('${pv}')`);
  sql(`update proposal_versions set status = 'evaluated' where id = '${pv}'`);
  // both members agree with the AI, except member 2 on REQ-001 (a disagreement)
  for (const u of [m1, m2]) {
    sql(
      `update member_evaluation_rows set review_state = 'agreed', member_grade = g.grade from member_evaluations e, ai_grades g where member_evaluation_rows.member_evaluation_id = e.id and e.user_id = '${u}' and e.tender_vendor_id = '${tv}' and g.id = member_evaluation_rows.ai_grade_id`,
    );
  }
  sql(
    `update member_evaluation_rows set review_state = 'overridden', member_grade = 'partially_compliant', note = 'Encryption covers only the backups.' where requirement_id = '${r1}' and member_evaluation_id = (select id from member_evaluations where tender_vendor_id = '${tv}' and user_id = '${m2}')`,
  );
  sql(
    `set role tender_owner; update member_evaluations set status = 'submitted', first_submitted_at = now(), submitted_at = now() where tender_vendor_id = '${tv}'`,
  );
  sql(`set role tender_owner; update tenders set status = 'consolidation' where id = '${tender}'`);

  // ---- before finalization a member and the Observer read nothing of the report ----------------------
  const member = await newContext(browser);
  await signIn(member.page, "member02@example.test", [seed]);
  await member.page.goto(`/tenders/${tender}/report`);
  await expect(member.page.getByText("The report is not available yet")).toBeVisible({
    timeout: 30_000,
  });
  const observer = await newContext(browser);
  await signIn(observer.page, "observer01@example.test", [seed]);
  await observer.page.goto(`/tenders/${tender}/report`);
  await expect(observer.page.getByText("The report is not available yet")).toBeVisible();

  // ---- the Chair settles the disagreement and finalizes ---------------------------------------------
  const chair = await newContext(browser);
  await signIn(chair.page, "member01@example.test", [seed]);
  await chair.page.goto(`/tenders/${tender}/report`);
  await expect(chair.page.getByText("Report version 1")).toBeVisible({ timeout: 30_000 });
  expect(await axeViolations(chair.page), "Report").toEqual([]);
  await expect(chair.page.getByRole("button", { name: "Finalize report" })).toBeDisabled();
  await expect(chair.page.getByText(/1 disagreements still need a final grade/)).toBeVisible();
  await chair.page.getByRole("button", { name: /Open REQ-001 for E2E Vendor/ }).click();
  await expect(chair.page.getByText("Encryption covers only the backups.")).toBeVisible();
  await chair.page.getByRole("radio", { name: "Partially compliant" }).check();
  await chair.page
    .getByLabel("Justification (required)")
    .fill("The vendor confirms encryption of backups only");
  await chair.page.getByRole("button", { name: "Record final grade" }).click();
  await expect(chair.page.getByText(/No disagreements are waiting/)).toBeVisible();
  await chair.page.keyboard.press("Escape");
  await chair.page.getByRole("button", { name: "Finalize report" }).click();
  await chair.page.getByRole("dialog").getByRole("button", { name: "Finalize report" }).click();
  await expect(chair.page.getByText("Finalized", { exact: false }).first()).toBeVisible({
    timeout: 30_000,
  });
  expect(sql(`select status from report_versions where tender_id = '${tender}'`)).toEqual([
    "finalized",
  ]);
  expect(sql(`select status from tenders where id = '${tender}'`)[0]).toBe("finalized");

  // ---- after finalization the member and the Observer read it -----------------------------------------
  await member.page.reload();
  await expect(member.page.getByText("Report version 1")).toBeVisible({ timeout: 30_000 });
  await observer.page.reload();
  await expect(observer.page.getByText("Report version 1")).toBeVisible({ timeout: 30_000 });

  // ---- the audit trail holds the facts, and the administrator reads it ---------------------------------
  const admin = await newContext(browser);
  await signIn(admin.page, "e2e-admin@example.test", [seed]);
  await admin.page.goto("/audit");
  await chooseOption(
    admin.page,
    "Tender",
    sql(`select name from tenders where id = '${tender}'`)[0]!,
  );
  await admin.page.getByRole("button", { name: "Search" }).click();
  await expect(
    admin.page
      .getByRole("cell", { name: "Report finalized" })
      .or(admin.page.getByRole("rowheader", { name: "Report finalized" })),
  ).toBeVisible({ timeout: 30_000 });
  expect(await axeViolations(admin.page), "Audit trail").toEqual([]);

  // ---- reopening creates version 2; version 1 stays finalized and readable -----------------------------
  await chair.page.getByRole("button", { name: "Reopen report" }).click();
  const dialog = chair.page.getByRole("dialog");
  await dialog.getByLabel("Note (required)").fill("A late clarification changes the support grade");
  await dialog.getByRole("button", { name: "Reopen report" }).click();
  await expect(chair.page.getByText("Report version 2")).toBeVisible({ timeout: 30_000 });
  expect(
    sql(
      `select version_no || ':' || status from report_versions where tender_id = '${tender}' order by version_no`,
    ),
  ).toEqual(["1:finalized", "2:in_progress"]);

  for (const c of [member, observer, chair, admin]) await c.context.close();
});
