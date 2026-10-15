import { expect, test } from "@playwright/test";
import { axeViolations, ensureUser, env, newContext, signIn, sql } from "./support";

/**
 * The Phase 4 journey against the real stack: an evaluated proposal (rows written to the database
 * the way the result handler writes them, synthetic text only) is reviewed by two members who each
 * see only their own decisions until they submit. Screening and the evaluation job themselves are
 * covered by `infra/scripts/screening-smoke.sh` and `evaluation-smoke.sh` on the real services.
 */
test("two members review one vendor independently and see each other after submitting", async ({
  browser,
}) => {
  test.setTimeout(240_000);
  ensureUser("e2e-admin", "E2e Admin", "admin");
  const seed = env("DEV_SEED_USER_PASSWORD");
  const stamp = Date.now();
  const adminId = sql(`select id from users where email = 'e2e-admin@example.test'`)[0];
  const m1 = sql(`select id from users where email = 'member01@example.test'`)[0];
  const m2 = sql(`select id from users where email = 'member02@example.test'`)[0];

  // the world: a tender in evaluation with a confirmed baseline, one accepted and evaluated vendor
  const tender = sql(
    `insert into tenders (name, created_by, status) values ('E2E evaluation ${stamp}', '${adminId}', 'draft') returning id`,
  )[0].split("\n")[0];
  sql(`update tenders set status = 'requirements' where id = '${tender}'`);
  sql(`update tenders set status = 'evaluation' where id = '${tender}'`);
  sql(
    `insert into tender_members (tender_id, user_id, is_chair) values ('${tender}', '${m1}', true)`,
  );
  sql(`insert into tender_members (tender_id, user_id) values ('${tender}', '${m2}')`);
  const r1 = sql(
    `insert into requirements (tender_id, text, importance, origin) values ('${tender}', 'The supplier shall encrypt all customer data at rest.', 'mandatory', 'manual') returning id`,
  )[0].split("\n")[0];
  const r2 = sql(
    `insert into requirements (tender_id, text, importance, origin) values ('${tender}', 'The supplier must provide support during working hours.', 'mandatory', 'manual') returning id`,
  )[0].split("\n")[0];
  const baseline = sql(
    `insert into baselines (tender_id, version_no, requirement_count, confirmed_by) values ('${tender}', 1, 2, '${m1}') returning id`,
  )[0].split("\n")[0];
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
  const vendor = sql(
    `insert into vendors (name, normalized_name) values ('E2E Vendor ${stamp}', 'e2e vendor ${stamp}') returning id`,
  )[0].split("\n")[0];
  const tv = sql(
    `insert into tender_vendors (tender_id, vendor_id, added_by) values ('${tender}', '${vendor}', '${adminId}') returning id`,
  )[0].split("\n")[0];
  const pv = sql(
    `insert into proposal_versions (tender_vendor_id, version_no, uploaded_by) values ('${tv}', 1, '${adminId}') returning id`,
  )[0].split("\n")[0];
  const doc = sql(
    `insert into documents (tender_id, doc_type, title) values ('${tender}', 'proposal', 'Proposal') returning id`,
  )[0].split("\n")[0];
  const dv = sql(`select gen_random_uuid()`)[0];
  sql(
    `insert into document_versions (id, document_id, version_no, file_name, file_format, size_bytes, sha256, storage_key, status, uploaded_by) values ('${dv}', '${doc}', 1, 'p.pdf', 'pdf', 10, repeat('a', 64), 'originals/${tender}/${doc}/${dv}.pdf', 'uploading', '${adminId}')`,
  );
  sql(
    `insert into proposal_version_files (proposal_version_id, document_version_id, file_role) values ('${pv}', '${dv}', 'main')`,
  );
  const passage = sql(
    `insert into passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end, content_hash) values (gen_random_uuid(), '${dv}', 7, 1, 'We encrypt all customer data at rest using AES-256.', '[]'::jsonb, 0, 50, repeat('b', 64)) returning id`,
  )[0].split("\n")[0];
  for (const u of [m1, m2]) {
    sql(
      `insert into conflict_declarations (tender_vendor_id, user_id, has_conflict) values ('${tv}', '${u}', false)`,
    );
  }
  sql(`update proposal_versions set status = 'in_screening' where id = '${pv}'`);
  sql(`update proposal_versions set status = 'accepted' where id = '${pv}'`);
  const job = sql(
    `insert into jobs (tender_id, job_type, target_type, target_id) values ('${tender}', 'evaluation', 'proposal_version', '${pv}') returning id`,
  )[0].split("\n")[0];
  const run = sql(
    `insert into ai_runs (job_id, run_type, model_name, prompt_version) values ('${job}', 'evaluation', 'stub', 'e-1') returning id`,
  )[0].split("\n")[0];
  sql(`update jobs set status = 'running', started_at = now() where id = '${job}'`);
  sql(
    `with g as (insert into ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id, outcome, grade, rationale_summary, rationale, confidence, requirement_hash) values (gen_random_uuid(), '${run}', '${pv}', '${baseline}', '${r1}', 'graded', 'fully_compliant', 'Stated', 'The proposal states that all customer data is encrypted at rest.', 0.9, repeat('c', 64)) returning id) insert into grade_citations (ai_grade_id, citation_no, document_version_id, passage_id, page_no, excerpt) select id, 1, '${dv}', '${passage}', 7, 'We encrypt all customer data at rest' from g`,
  );
  sql(
    `insert into ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id, outcome, failure_code, requirement_hash) values (gen_random_uuid(), '${run}', '${pv}', '${baseline}', '${r2}', 'failed', 'ungrounded', repeat('c', 64))`,
  );
  sql(`update jobs set status = 'completed', finished_at = now() where id = '${job}'`);
  sql(`select count(*) from ingest_sync_member_evaluations('${pv}')`);

  // ---- member 1 reviews: agrees with the AI, grades the failed row, submits --------------------------
  const one = await newContext(browser);
  await signIn(one.page, "member01@example.test", [seed]);
  await one.page.goto(`/tenders/${tender}/evaluation`);
  await expect(one.page.getByText("0 of 2")).toBeVisible({ timeout: 30_000 });
  expect(await axeViolations(one.page), "Evaluation overview").toEqual([]);
  await one.page.getByRole("link", { name: /Open E2E Vendor/ }).click();
  await expect(one.page.getByText(/Your grades and notes are private/)).toBeVisible();
  await expect(one.page.getByText("0 of 2 reviewed")).toBeVisible();
  await one.page.getByRole("button", { name: "REQ-001" }).click();
  await expect(one.page.getByText(/encrypted at rest/).first()).toBeVisible();
  expect(await axeViolations(one.page), "Evaluation sheet").toEqual([]);
  await one.page.getByRole("button", { name: "Show in context" }).click();
  await expect(one.page.getByLabel("The passage in context")).toContainText("AES-256");
  await one.page.getByRole("button", { name: "Agree with AI" }).click();
  await expect(one.page.getByText("1 of 2 reviewed")).toBeVisible();

  await one.page.getByRole("button", { name: "REQ-002" }).click();
  await expect(one.page.getByText(/the AI could not give a grade/)).toBeVisible();
  await one.page.getByRole("button", { name: "Override" }).click();
  const dialog = one.page.getByRole("dialog");
  const save = dialog.getByRole("button", { name: "Save override" });
  await expect(save).toBeDisabled();
  await dialog.getByLabel("Note (required)").fill("Support hours are listed in the annex");
  await save.click();
  await expect(one.page.getByText("2 of 2 reviewed")).toBeVisible();
  await one.page.getByRole("button", { name: "Submit this vendor" }).click();
  await one.page.getByRole("dialog").getByRole("button", { name: "Submit" }).click();
  await expect(one.page.getByText("Submitted").first()).toBeVisible();

  // ---- the database holds exactly what the member decided -------------------------------------------
  expect(
    sql(
      `select r.review_state || ':' || coalesce(r.member_grade, '-') from member_evaluation_rows r join member_evaluations e on e.id = r.member_evaluation_id join requirements q on q.id = r.requirement_id where e.user_id = '${m1}' and e.tender_vendor_id = '${tv}' order by q.created_at`,
    ),
  ).toEqual(["agreed:fully_compliant", "overridden:non_compliant"]);
  expect(
    sql(
      `select count(*) from member_evaluations where tender_vendor_id = '${tv}' and user_id = '${m1}' and status = 'submitted' and first_submitted_at is not null`,
    )[0],
  ).toBe("1");

  // ---- member 2 sees only their own state; the other member's decisions stay closed -------------------
  const two = await newContext(browser);
  await signIn(two.page, "member02@example.test", [seed]);
  await two.page.goto(`/tenders/${tender}/vendors/${tv}/evaluation`);
  await expect(two.page.getByText("0 of 2 reviewed")).toBeVisible({ timeout: 30_000 });
  await expect(two.page.getByRole("button", { name: /Show other members/ })).toHaveCount(0);
  await two.page.getByRole("button", { name: "REQ-001" }).click();
  await two.page.getByRole("button", { name: "Agree with AI" }).click();
  await two.page.getByRole("button", { name: "REQ-002" }).click();
  await two.page.getByRole("button", { name: "Override" }).click();
  await two.page
    .getByRole("dialog")
    .getByLabel("Note (required)")
    .fill("The vendor says nothing about support hours");
  await two.page.getByRole("dialog").getByRole("button", { name: "Save override" }).click();
  await expect(two.page.getByText("2 of 2 reviewed")).toBeVisible();
  await two.page.getByRole("button", { name: "Submit this vendor" }).click();
  await two.page.getByRole("dialog").getByRole("button", { name: "Submit" }).click();
  await expect(two.page.getByText("Submitted").first()).toBeVisible();

  // after submitting, member 2 may read member 1's decisions on a requirement
  await two.page.getByRole("button", { name: "REQ-002" }).click();
  await two.page.getByRole("button", { name: /Show other members/ }).click();
  await expect(two.page.getByLabel("Other members' decisions")).toContainText("Support hours");
  await admin_check(tender, tv);
  await one.context.close();
  await two.context.close();
});

async function admin_check(tender: string, tv: string) {
  // the administrator reads nothing of the rows through the audit trail before release; both
  // members have submitted, so the tender moves to consolidation (Phase 5)
  expect(
    sql(
      `select count(*) from member_evaluations where tender_vendor_id = '${tv}' and status = 'submitted'`,
    )[0],
  ).toBe("2");
  expect(sql(`select status from tenders where id = '${tender}'`)[0]).toBe("consolidation");
}
