import { execFileSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { expect, test } from "@playwright/test";
import { axeViolations, E2E_ADMIN, ensureUser, env, newContext, signIn, sql } from "./support";

/**
 * The Phase 2 exit criterion (roadmap 3): a long PDF and a DOCX reach Ready with page, section and
 * boxes per passage, and a passage link opens the right page with the passage highlighted. Real
 * stack, synthetic documents only (AGENTS rule 15).
 */
test("upload an RFP and a proposal, read them, and open a passage highlighted", async ({
  browser,
}) => {
  const dir = mkdtempSync(join(tmpdir(), "tender-e2e-"));
  execFileSync("uv", ["run", "python", "tests/fixtures/generate.py", dir, "40"], {
    cwd: resolve(process.cwd(), "../worker"),
    stdio: "pipe",
  });
  ensureUser("e2e-admin", "E2e Admin", "admin");
  const stamp = Date.now();
  const tenderName = `E2E documents ${stamp}`;
  const adminId = sql(`select id from users where lower(email) = lower('${E2E_ADMIN}')`)[0];
  const tenderId = sql(
    `insert into tenders (name, created_by) values ('${tenderName}', '${adminId}') returning id`,
  )[0].split("\n")[0];

  const admin = await newContext(browser);
  await signIn(admin.page, E2E_ADMIN, [env("DEV_SEED_USER_PASSWORD")]);
  await admin.page.goto(`/tenders/${tenderId}/documents`);
  await expect(admin.page.getByText("No documents yet")).toBeVisible();
  expect(await axeViolations(admin.page), "Documents (empty)").toEqual([]);

  // ---- upload the RFP: it is read in the background and becomes Ready ------------------------------
  await admin.page.locator("#document-file").setInputFiles(join(dir, "rfp.pdf"));
  const row = admin.page.getByRole("row", { name: /rfp/i });
  await expect(row).toBeVisible();
  await expect(row.getByText("Ready")).toBeVisible({ timeout: 90_000 });
  expect(await axeViolations(admin.page), "Documents (ready)").toEqual([]);

  const versionId = sql(
    `select v.id from document_versions v join documents d on d.id = v.document_id where d.tender_id = '${tenderId}' and d.doc_type = 'rfp'`,
  )[0];
  expect(sql(`select page_count from document_versions where id = '${versionId}'`)).toEqual(["40"]);
  // every passage has a page, a section and boxes
  expect(
    sql(
      `select count(*) from passages where document_version_id = '${versionId}' and section_id is not null and jsonb_array_length(bbox) > 0`,
    )[0],
  ).toBe(sql(`select count(*) from passages where document_version_id = '${versionId}'`)[0]);

  // ---- a passage on page 17 opens that page with its boxes highlighted -----------------------------
  const passage = sql(
    `select id from passages where document_version_id = '${versionId}' and page_no = 17 order by passage_no limit 1`,
  )[0];
  expect(passage).toBeTruthy();
  await admin.page.goto(`/documents/${versionId}/view?passage=${passage}`);
  await expect(admin.page.getByText("Page 17 of 40")).toBeVisible({ timeout: 30_000 });
  await expect(admin.page.getByTestId("passage-highlight").first()).toBeVisible({
    timeout: 30_000,
  });
  expect(await axeViolations(admin.page), "Viewer").toEqual([]);

  // ---- the page itself leads back to the tender's documents ------------------------------------------
  await admin.page.getByRole("link", { name: "Back to tender documents" }).click();
  await expect(admin.page.getByRole("heading", { name: "Documents", exact: true })).toBeVisible();
  await expect(admin.page).toHaveURL(new RegExp(`/tenders/${tenderId}/documents$`));
  await admin.page.goBack();

  // ---- the file cannot be fetched as a download or by a storage address ---------------------------
  const token = await admin.page.evaluate(() =>
    Object.keys(sessionStorage).map((k) => sessionStorage.getItem(k)),
  );
  expect(JSON.stringify(token)).not.toContain("X-Amz-Signature");

  // ---- a DOCX and a failing file --------------------------------------------------------------------
  await admin.page.goto(`/tenders/${tenderId}/documents`);
  await admin.page.getByLabel("Type").selectOption("other");
  await admin.page.locator("#document-file").setInputFiles(join(dir, "sow.docx"));
  const docx = admin.page.getByRole("row", { name: /sow/i });
  await expect(docx.getByText("Ready")).toBeVisible({ timeout: 120_000 });

  await admin.page.locator("#document-file").setInputFiles(join(dir, "protected.pdf"));
  const prot = admin.page.getByRole("row", { name: /protected/i });
  await expect(prot.getByText("Failed")).toBeVisible({ timeout: 60_000 });
  await expect(prot.getByText(/password protected/i)).toBeVisible();

  // ---- the job center shows what ran -----------------------------------------------------------------
  await admin.page.getByRole("link", { name: "Jobs" }).click();
  await expect(admin.page.getByRole("heading", { name: "Jobs" })).toBeVisible();
  await expect(admin.page.getByText("Reading a document").first()).toBeVisible();
  expect(await axeViolations(admin.page), "Jobs").toEqual([]);

  // ---- a vendor with a two-file proposal -------------------------------------------------------------
  await admin.page.goto(`/tenders/${tenderId}/vendors`);
  await admin.page.getByLabel("Vendor name").fill(`Lumen ${stamp}`);
  await admin.page.getByRole("button", { name: "Add vendor" }).click();
  await expect(
    admin.page.getByRole("heading", { name: new RegExp(`Lumen ${stamp}`) }),
  ).toBeVisible();
  await admin.page
    .getByLabel("Upload the proposal")
    .setInputFiles([join(dir, "sow.docx"), join(dir, "rfp.pdf")]);
  await admin.page.getByRole("button", { name: "Upload", exact: true }).click();
  await expect(admin.page.getByText(/in version 1/)).toBeVisible({ timeout: 30_000 });
  await expect(admin.page.getByText("sow.docx (main file)")).toBeVisible();
  expect(await axeViolations(admin.page), "Vendors").toEqual([]);
});
