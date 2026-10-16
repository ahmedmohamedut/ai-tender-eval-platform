import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { MemoryRouter } from "react-router";
import type { ReactElement } from "react";
import { vi } from "vitest";
import { AppearanceProvider } from "../app/Appearance";
import { SessionTestProvider } from "../app/Session";
import { AnnounceProvider } from "../components/ui";
import { ApiProvider, type ApiClient } from "../lib/api";
import type { Me, UserRole } from "../lib/types";

export function makeMe(role: UserRole = "admin", name = "Alex Admin"): Me {
  return {
    user: {
      id: "11111111-1111-4111-8111-111111111111",
      email: "alex@example.test",
      display_name: name,
      role,
      status: "active",
      time_zone: "UTC",
      last_sign_in_at: null,
      created_at: "2026-01-01T00:00:00Z",
      row_version: 1,
    },
    assigned_tender_count: 0,
    client_config: {
      min_note_length: 10,
      upload_max_bytes: 104857600,
      accepted_formats: ["pdf", "docx"],
      low_confidence_threshold: 0.6,
      session_idle_timeout_seconds: 1800,
      session_warning_seconds: 120,
    },
  };
}

export type Handler = (path: string, init?: Record<string, unknown>) => unknown;

/** A fake typed client: each verb returns what `handler(path, init)` says, as an openapi-fetch result. */
export function fakeApi(handler: Handler) {
  const verb = (method: string) =>
    vi.fn(async (path: string, init?: Record<string, unknown>) => {
      const out = handler(`${method} ${path}`, init) as
        { status?: number; data?: unknown; error?: unknown } | undefined;
      const status = out?.status ?? (out?.error ? 400 : 200);
      return {
        data: out?.error ? undefined : out && "data" in out ? out.data : out,
        error: out?.error,
        response: { status },
      };
    });
  return {
    GET: verb("GET"),
    POST: verb("POST"),
    PUT: verb("PUT"),
    PATCH: verb("PATCH"),
    DELETE: verb("DELETE"),
    use: vi.fn(),
  };
}

export function renderPage(
  ui: ReactElement,
  opts: { api: ReturnType<typeof fakeApi>; me?: Me; route?: string },
) {
  const me = opts.me ?? makeMe();
  return render(
    <AppearanceProvider initialMode="light">
      <ApiProvider client={opts.api as unknown as ApiClient}>
        <AnnounceProvider>
          <SessionTestProvider me={me}>
            <MemoryRouter initialEntries={[opts.route ?? "/"]}>{ui}</MemoryRouter>
          </SessionTestProvider>
        </AnnounceProvider>
      </ApiProvider>
    </AppearanceProvider>,
  );
}

/** WCAG checks axe can run without layout. Contrast is covered by tokens.test.ts. */
export async function accessibilityViolations(container: HTMLElement) {
  const result = await axe.run(container, {
    rules: { "color-contrast": { enabled: false }, region: { enabled: false } },
  });
  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`);
}

/** Picks an option of an antd Select by its label (the control is a combobox, not a <select>). */
export async function choose(
  label: string | RegExp,
  option: string,
  scope: HTMLElement = document.body,
) {
  await userEvent.click(within(scope).getByRole("combobox", { name: label }));
  const hits = await screen.findAllByText(option, { selector: ".ant-select-item-option-content" });
  await userEvent.click(hits[hits.length - 1]!);
}

/** The labels an antd Select offers, read by opening it (and closing it again). */
export async function optionsOf(label: string | RegExp): Promise<string[]> {
  const box = screen.getByRole("combobox", { name: label });
  await userEvent.click(box);
  const items = Array.from(
    document.querySelectorAll(".ant-select-item-option-content"),
    (n) => n.textContent ?? "",
  );
  await userEvent.keyboard("{Escape}");
  return items;
}

/**
 * The open dialog with this title. In the test build antd gives every modal the same
 * `aria-labelledby`, so a dialog cannot be found by its accessible name there.
 */
export async function dialogTitled(title: string): Promise<HTMLElement> {
  const heading = await screen.findByText(title, { selector: ".ant-modal-title" });
  return heading.closest('[role="dialog"]') as HTMLElement;
}
