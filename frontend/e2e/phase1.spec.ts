import { expect, test } from "@playwright/test";
import {
  axeViolations,
  E2E_ADMIN,
  ensureUser,
  env,
  mailLink,
  newContext,
  NEW_PASSWORD,
  signIn,
  sql,
  chooseOption,
} from "./support";

/**
 * The Phase 1 exit criterion (roadmap 3): an administrator invites a user, the user signs in and
 * sees only their tenders, and a tender can be opened to the committee. Against the real stack.
 */
test("invite, sign in, assign, open to committee, and refuse an outsider", async ({ browser }) => {
  const stamp = Date.now();
  const inviteeEmail = `e2e-${stamp}@example.test`;
  const inviteeName = `E2e Person ${stamp}`;
  const tenderName = `E2E tender ${stamp}`;
  ensureUser("e2e-admin", "E2e Admin", "admin");
  const adminEmail = E2E_ADMIN;
  const seedPassword = env("DEV_SEED_USER_PASSWORD");

  // ---- the administrator signs in and invites a user ------------------------------------------
  const admin = await newContext(browser);
  await signIn(admin.page, adminEmail, [seedPassword]);
  await expect(admin.page.getByRole("heading", { name: "All tenders" })).toBeVisible();
  expect(await axeViolations(admin.page), "All tenders page").toEqual([]);

  await admin.page.getByRole("link", { name: "Users" }).click();
  await expect(admin.page.getByRole("heading", { name: "Users" })).toBeVisible();
  await admin.page.getByRole("button", { name: "Invite user" }).click();
  const dialog = admin.page.getByRole("dialog");
  await dialog.getByLabel("Email (required)").fill(inviteeEmail);
  await dialog.getByLabel("Name (required)").fill(inviteeName);
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await admin.page.getByLabel("Search users").fill(inviteeEmail);
  await expect(admin.page.getByRole("rowheader", { name: inviteeName })).toBeVisible();
  await expect(
    admin.page.getByRole("row", { name: new RegExp(inviteeName) }).getByText("Invited"),
  ).toBeVisible();
  expect(await axeViolations(admin.page), "Users page").toEqual([]);

  // ---- the invitee follows the mailed link, sets a password and signs in -----------------------
  const link = await mailLink(inviteeEmail, "action-token");
  const invitee = await newContext(browser);
  await invitee.page.goto(link);
  // The link may land on a confirmation first.
  const proceed = invitee.page.getByRole("link", { name: /click here to proceed/i });
  if (await proceed.isVisible({ timeout: 3000 }).catch(() => false)) {
    await proceed.click();
  }
  await invitee.page.locator("#password-new").waitFor();
  await invitee.page.locator("#password-new").fill(NEW_PASSWORD);
  await invitee.page.locator("#password-confirm").fill(NEW_PASSWORD);
  await invitee.page.locator("input[type=submit], button[type=submit]").first().click();
  await signIn(invitee.page, inviteeEmail, [NEW_PASSWORD]).catch(async () => {
    // Already signed in by the action flow.
    await invitee.page.goto("/");
  });
  await expect(invitee.page.getByRole("heading", { name: "My tenders" })).toBeVisible();
  await expect(invitee.page.getByText(/not assigned to any tender yet/)).toBeVisible();
  expect(sql(`select status from users where lower(email) = '${inviteeEmail}'`)).toEqual([
    "active",
  ]);

  // ---- the administrator creates a tender with a committee and a Chair -----------------------------
  await admin.page.getByRole("link", { name: "All tenders", exact: true }).click();
  await admin.page.getByRole("link", { name: "Create tender" }).first().click();
  await admin.page.getByLabel("Tender name").fill(tenderName);
  const members = admin.page.getByRole("combobox", { name: "Committee members" });
  for (const person of [inviteeName, "Member 01"]) {
    await members.fill(person);
    await admin.page.getByRole("option", { name: person, exact: true }).first().click();
  }
  await admin.page.keyboard.press("Escape");
  await chooseOption(admin.page, "Committee Chair", "Member 01");
  await admin.page.getByRole("button", { name: "Create tender" }).click();
  await expect(admin.page.getByRole("heading", { name: "Edit tender" })).toBeVisible();
  await expect(admin.page.getByText("RFP uploaded")).toBeVisible();
  await expect(admin.page.getByRole("button", { name: "Open to committee" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  expect(await axeViolations(admin.page), "Edit tender page").toEqual([]);

  // The invitee is not on the committee of an open tender yet and sees nothing to open.
  const tenderId = sql(`select id from tenders where name = '${tenderName}'`)[0];
  expect(tenderId).toBeTruthy();

  // Documents arrive with Phase 2; the RFP is arranged directly, as the document upload will.
  sql(`
    with d as (insert into documents (tender_id, doc_type, title) values ('${tenderId}', 'rfp', 'RFP') returning id),
         v as (insert into document_versions (id, document_id, version_no, file_name, file_format, size_bytes, sha256, storage_key, status, uploaded_by)
               select gen_random_uuid(), d.id, 1, 'rfp.pdf', 'pdf', 10, repeat('a', 64), null, 'uploading',
                      (select id from users where lower(email) = lower('${adminEmail}')) from d returning id, document_id)
    select 1`);
  // storage_key is required once stored; complete the arrangement through the allowed moves.
  sql(
    `update document_versions set storage_key = 'originals/${tenderId}/' || document_id || '/' || id || '.pdf' where document_id in (select id from documents where tender_id = '${tenderId}')`,
  );
  sql(
    `update document_versions set status = 'processing' where document_id in (select id from documents where tender_id = '${tenderId}')`,
  );
  sql(
    `update document_versions set status = 'ready', page_count = 1 where document_id in (select id from documents where tender_id = '${tenderId}')`,
  );

  // ---- open to the committee ---------------------------------------------------------------------
  await admin.page.reload();
  await expect(admin.page.getByRole("button", { name: "Open to committee" })).not.toHaveAttribute(
    "aria-disabled",
    "true",
  );
  await admin.page.getByRole("button", { name: "Open to committee" }).click();
  const opened = admin.page.waitForResponse(
    (r) => r.url().includes(`/tenders/${tenderId}/open`) && r.request().method() === "POST",
  );
  await admin.page.getByRole("dialog").getByRole("button", { name: "Open to committee" }).click();
  const response = await opened;
  expect(response.status(), await response.text()).toBe(200);
  await admin.page.goto(`/tenders/${tenderId}`);
  await expect(
    admin.page.getByRole("heading", { level: 1, name: new RegExp(tenderName) }),
  ).toBeVisible();
  expect(sql(`select status from tenders where id = '${tenderId}'`)).toEqual(["requirements"]);

  // ---- the invitee now sees exactly that tender --------------------------------------------------
  await invitee.page.goto("/my-tenders");
  await expect(invitee.page.getByRole("link", { name: tenderName })).toBeVisible();
  await invitee.page.getByRole("link", { name: tenderName }).click();
  await expect(
    invitee.page.getByRole("heading", { level: 1, name: new RegExp(tenderName) }),
  ).toBeVisible();
  expect(await axeViolations(invitee.page), "Tender home").toEqual([]);

  // ---- a member who is not on the committee is refused, with whom to ask ------------------------------
  const outsider = await newContext(browser);
  await signIn(outsider.page, "member02@example.test", [seedPassword]);
  await outsider.page.goto(`/tenders/${tenderId}`);
  await expect(outsider.page.getByText("You do not have access to this tender")).toBeVisible();
  await expect(outsider.page.getByText(/Ask the administrator/)).toBeVisible();
  await outsider.page.goto("/my-tenders");
  await expect(outsider.page.getByRole("link", { name: tenderName })).toHaveCount(0);

  // ---- an observer sees the list but cannot create ------------------------------------------------------
  const observer = await newContext(browser);
  await signIn(observer.page, "observer01@example.test", [seedPassword]);
  await expect(observer.page.getByRole("heading", { name: "All tenders" })).toBeVisible();
  await expect(observer.page.getByRole("link", { name: "Create tender" })).toHaveCount(0);

  for (const c of [admin, invitee, outsider, observer]) {
    await c.context.close();
  }
});
