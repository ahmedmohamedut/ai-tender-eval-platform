import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import type { Browser, BrowserContext, Page } from "@playwright/test";

const require = createRequire(import.meta.url);

export const env = (name: string, fallback?: string): string => {
  const v = process.env[name] ?? fallback;
  if (v === undefined) {
    throw new Error(`${name} is not set; run through "make e2e" so .env is loaded`);
  }
  return v;
};

export const NEW_PASSWORD = "E2e-Sign-In-Password-1";

/** The administrator the browser tests sign in as; created beside the bootstrap one, never changed. */
export const E2E_ADMIN = "e2e-admin@example.test";

/** Creates (or repairs) one development user through the seed script. */
export function ensureUser(
  shortName: string,
  displayName: string,
  role: "admin" | "member" | "observer",
): void {
  execFileSync("../infra/scripts/seed-dev-users.sh", ["--one", shortName, displayName, role], {
    encoding: "utf8",
    env: process.env,
  });
}

/** One SQL statement against the development database; returns the first column of each row. */
export function sql(statement: string): string[] {
  const out = execFileSync(
    "docker",
    [
      "exec",
      "-e",
      `PGPASSWORD=${env("POSTGRES_SUPERUSER_PASSWORD")}`,
      "tender-eval-postgres-1",
      "psql",
      "-U",
      env("POSTGRES_SUPERUSER"),
      "-d",
      env("POSTGRES_DB"),
      "-v",
      "ON_ERROR_STOP=1",
      "-At",
      "-c",
      statement,
    ],
    { encoding: "utf8" },
  );
  return out.split("\n").filter(Boolean);
}

/** Signs in through the Keycloak form, trying each password in turn; handles a forced change. */
export async function signIn(page: Page, email: string, passwords: string[]): Promise<string> {
  await page.goto("/");
  for (const password of passwords) {
    await page.locator("#username").waitFor();
    await page.locator("#username").fill(email);
    await page.locator("#password").fill(password);
    await page.locator("#kc-login").click();
    // Either the form comes back with an error, a password change is demanded, or we are in.
    const next = await Promise.race([
      page
        .locator("#password-new")
        .waitFor()
        .then(() => "change" as const),
      page
        .locator("#input-error, #input-error-username, .kc-feedback-text")
        .first()
        .waitFor()
        .then(() => "error" as const),
      page
        .locator("main#main")
        .waitFor()
        .then(() => "in" as const),
    ]);
    if (next === "in") {
      return password;
    }
    if (next === "change") {
      await changePassword(page);
      await page.locator("main#main").waitFor();
      return NEW_PASSWORD;
    }
  }
  throw new Error(`could not sign in as ${email}`);
}

export async function changePassword(page: Page): Promise<void> {
  await page.locator("#password-new").fill(NEW_PASSWORD);
  await page.locator("#password-confirm").fill(NEW_PASSWORD);
  await page.locator("input[type=submit], button[type=submit]").first().click();
}

/** The newest mail to `to` from the mail catcher, polled until it arrives. */
export async function mailLink(to: string, contains: string): Promise<string> {
  const base = `http://localhost:${env("MAILPIT_HTTP_PORT", "18025")}`;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const found = (await (
      await fetch(`${base}/api/v1/search?query=${encodeURIComponent("to:" + to)}`)
    ).json()) as {
      messages?: { ID: string }[];
    };
    for (const m of found.messages ?? []) {
      const msg = (await (await fetch(`${base}/api/v1/message/${m.ID}`)).json()) as {
        HTML?: string;
        Text?: string;
      };
      const body = `${msg.HTML ?? ""}\n${msg.Text ?? ""}`;
      const link = /https?:\/\/[^\s"'<>]+/g;
      for (const url of body.match(link) ?? []) {
        const clean = url.replace(/&amp;/g, "&");
        if (clean.includes(contains)) {
          return clean;
        }
      }
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`no mail with a "${contains}" link for ${to}`);
}

export async function newContext(
  browser: Browser,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext();
  return { context, page: await context.newPage() };
}

/** axe-core in the real browser, contrast included. Returns readable violations. */
export async function axeViolations(page: Page): Promise<string[]> {
  // antd fades dialogs and drawers in and out; contrast measured mid-fade is not the real contrast.
  await page.waitForFunction(() => document.getAnimations().length === 0, undefined, {
    timeout: 5_000,
  });
  await page.addScriptTag({ path: require.resolve("axe-core/axe.min.js") });
  const result = await page.evaluate(async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const axe = (window as any).axe;
    const r = await axe.run(document, { rules: { region: { enabled: false } } });
    return r.violations.map(
      (v: { id: string; help: string; nodes: { html: string; any: { data?: unknown }[] }[] }) =>
        `${v.id}: ${v.help} (${v.nodes.length}) ` +
        v.nodes
          .slice(0, 4)
          .map(
            (n) => `${n.html.slice(0, 90)} ${JSON.stringify(n.any[0]?.data ?? "").slice(0, 160)}`,
          )
          .join(" | "),
    );
  });
  return result as string[];
}

/** Picks an option of an antd Select by its visible label (the control is a combobox). */
export async function chooseOption(page: Page, label: string | RegExp, option: string) {
  await page.getByRole("combobox", { name: label }).click();
  await page.getByRole("option", { name: option, exact: true }).click();
}
