import { execFileSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { expect, test } from "@playwright/test";
import { axeViolations, E2E_ADMIN, ensureUser, env, newContext, signIn, sql } from "./support";

/**
 * The Phase 3 exit criteria (roadmap 3), against the real stack with synthetic documents:
 * extraction proposes requirements whose every quote is verbatim in a cited passage; two members
 * editing one requirement get a clear conflict; confirming locks the list; reopening needs a
 * reason and the next confirmation is version 2.
 */
test("extract, review, conflict, confirm, reopen and confirm again", async ({ browser }) => {
  test.setTimeout(420_000);
  const dir = mkdtempSync(join(tmpdir(), "tender-e2e-"));
  execFileSync("uv", ["run", "python", "tests/fixtures/generate.py", dir, "12"], {
    cwd: resolve(process.cwd(), "../worker"),
    stdio: "pipe",
  });
  ensureUser("e2e-admin", "E2e Admin", "admin");
  const seed = env("DEV_SEED_USER_PASSWORD");
  const stamp = Date.now();
  const adminId = sql(`select id from users where lower(email) = lower('${E2E_ADMIN}')`)[0];
  const m1 = sql(`select id from users where email = 'member01@example.test'`)[0];
  const m2 = sql(`select id from users where email = 'member02@example.test'`)[0];
  const tender = sql(
    `insert into tenders (name, created_by, status) values ('E2E requirements ${stamp}', '${adminId}', 'draft') returning id`,
  )[0].split("\n")[0];
  sql(`update tenders set status = 'requirements' where id = '${tender}'`);
  sql(
    `insert into tender_members (tender_id, user_id, is_chair) values ('${tender}', '${m1}', true)`,
  );
  sql(`insert into tender_members (tender_id, user_id) values ('${tender}', '${m2}')`);
  if (sql(`select count(*) from requirement_categories`)[0] === "0") {
    sql(`insert into requirement_categories (name) values ('Security')`);
  }

  // ---- the administrator uploads the RFP -----------------------------------------------------------
  const admin = await newContext(browser);
  await signIn(admin.page, E2E_ADMIN, [env("DEV_SEED_USER_PASSWORD")]);
  await admin.page.goto(`/tenders/${tender}/documents`);
  await admin.page.getByRole("button", { name: "Upload document" }).click();
  await admin.page.locator("#document-file").setInputFiles(join(dir, "rfp.pdf"));
  await expect(admin.page.getByRole("row", { name: /rfp/i }).getByText("Ready")).toBeVisible({
    timeout: 90_000,
  });
  const version = sql(
    `select v.id from document_versions v join documents d on d.id = v.document_id where d.tender_id = '${tender}'`,
  )[0];

  // ---- member 1 selects a section and extracts -------------------------------------------------------
  const one = await newContext(browser);
  await signIn(one.page, "member01@example.test", [seed]);
  await one.page.goto(`/documents/${version}/view`);
  await expect(one.page.getByText("Extract requirements")).toBeVisible({ timeout: 30_000 });
  await one.page.getByLabel(/Extract from 1\. Chapter 1/).check();
  expect(await axeViolations(one.page), "Extraction selection").toEqual([]);
  await one.page.getByRole("button", { name: "Start extraction" }).click();
  await one.page
    .getByRole("dialog", { name: "Start extraction" })
    .getByRole("button", { name: "Start extraction" })
    .click();
  await expect(one.page.getByText(/Extracted/).first()).toBeVisible({
    timeout: 120_000,
  });

  // ---- the requirements arrive unreviewed, each source verbatim ---------------------------------------
  await one.page.goto(`/tenders/${tender}/requirements`);
  await expect(one.page.getByRole("table")).toBeVisible();
  const total = Number(sql(`select count(*) from requirements where tender_id = '${tender}'`)[0]);
  expect(total).toBeGreaterThan(3);
  expect(
    sql(
      `select count(*) from requirements r where r.tender_id = '${tender}' and not exists (
         select 1 from requirement_sources s join passages p on p.id = s.passage_id
         where s.requirement_id = r.id and position(s.quote in p.text) > 0)`,
    )[0],
  ).toBe("0");
  expect(await axeViolations(one.page), "Requirements").toEqual([]);

  // ---- two members edit one requirement: the second gets a clear conflict ------------------------------
  const first = one.page.getByRole("button", { name: "REQ-001" });
  await first.click();
  const two = await newContext(browser);
  await signIn(two.page, "member02@example.test", [seed]);
  await two.page.goto(`/tenders/${tender}/requirements`);
  await two.page.getByRole("button", { name: "REQ-001" }).click();

  await one.page
    .getByLabel("Requirement text")
    .fill("The supplier shall provide the service as one.");
  await one.page.getByRole("button", { name: "Save changes" }).click();
  await one.page.getByRole("dialog").getByLabel("Note (required)").fill("Member one clarified");
  await one.page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(one.page.getByText("REQ-001 saved")).toBeVisible();

  await two.page
    .getByLabel("Requirement text")
    .fill("The supplier shall provide the service as two.");
  await two.page.getByRole("button", { name: "Save changes" }).click();
  await two.page.getByRole("dialog").getByLabel("Note (required)").fill("Member two clarified");
  await two.page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  const conflict = two.page.getByRole("dialog", { name: "This requirement was changed" });
  await expect(conflict).toBeVisible();
  await expect(
    conflict.getByText(/changed this requirement while you were editing it/),
  ).toBeVisible();
  await conflict.getByRole("button", { name: "Keep theirs" }).click();

  // ---- review everything, resolve flags, confirm as the Chair ---------------------------------------------
  await one.page.goto(`/tenders/${tender}/requirements`);
  await one.page.getByLabel("Select REQ-001").waitFor();
  sql(
    `update requirement_flags set resolved_at = now(), resolved_by = '${m1}' where resolved_at is null and requirement_id in (select id from requirements where tender_id = '${tender}')`,
  );
  sql(
    `update requirements set category_id = (select id from requirement_categories order by created_at limit 1) where tender_id = '${tender}' and category_id is null`,
  );
  await one.page.reload();
  const boxes = one.page.getByRole("checkbox", { name: /^Select REQ-/ });
  await boxes.first().waitFor();
  const n = await boxes.count();
  for (let i = 0; i < n; i++) await boxes.nth(i).check();
  await one.page.getByRole("button", { name: "Mark reviewed" }).click();
  await one.page.getByRole("dialog").getByLabel("Note (required)").fill("Reviewed in the journey");
  await one.page.getByRole("dialog").getByRole("button", { name: "Apply" }).click();
  await expect(one.page.getByRole("dialog")).toBeHidden();
  await expect
    .poll(
      () =>
        sql(
          `select count(*) from requirements where tender_id = '${tender}' and review_state <> 'reviewed' and deleted_at is null`,
        )[0],
    )
    .toBe("0");

  await one.page.goto(`/tenders/${tender}/baseline`);
  await expect(one.page.getByText(/Every requirement is reviewed/)).toBeVisible({
    timeout: 30_000,
  });
  expect(await axeViolations(one.page), "Baseline").toEqual([]);
  await one.page.getByRole("button", { name: "Confirm requirements" }).click();
  await one.page.getByRole("dialog").getByRole("button", { name: "Confirm requirements" }).click();
  await expect(one.page.getByText(/Baseline v1/).first()).toBeVisible({ timeout: 30_000 });
  expect(
    sql(`select status from baselines where tender_id = '${tender}' and version_no = 1`),
  ).toEqual(["confirmed"]);

  // locked: member 2 cannot edit any more
  await two.page.reload();
  await expect(
    two.page.getByText(/The baseline is confirmed, so the list is read only/),
  ).toBeVisible();

  // ---- reopen with a reason, confirm again as version 2 ---------------------------------------------------
  await one.page.getByRole("button", { name: "Reopen requirements" }).click();
  const reopen = one.page.getByRole("dialog");
  await reopen.getByLabel("Note (required)").fill("Scope changed after the sponsor review");
  await reopen.getByRole("button", { name: "Reopen requirements" }).click();
  await expect(one.page.getByRole("button", { name: "Confirm requirements" })).toBeVisible({
    timeout: 30_000,
  });
  expect(
    sql(`select status from baselines where tender_id = '${tender}' and version_no = 1`),
  ).toEqual(["reopened"]);
  await one.page.getByRole("button", { name: "Confirm requirements" }).click();
  await one.page.getByRole("dialog").getByRole("button", { name: "Confirm requirements" }).click();
  await expect(one.page.getByText(/Baseline v2/).first()).toBeVisible({ timeout: 30_000 });
  expect(
    sql(
      `select version_no || ':' || status from baselines where tender_id = '${tender}' order by version_no`,
    ),
  ).toEqual(["1:superseded", "2:confirmed"]);
});
