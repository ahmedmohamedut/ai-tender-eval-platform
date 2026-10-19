import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import {
  accessibilityViolations,
  dialogTitled,
  fakeApi,
  makeMe,
  renderPage,
} from "../test/helpers";
import { Baseline } from "./Baseline";
import { Requirements } from "./Requirements";

const TENDER_ID = "22222222-2222-4222-8222-222222222222";
const CAT = { id: "c1", name: "Security", sort_order: 1, is_active: true };
const TENDER = {
  id: TENDER_ID,
  name: "Cloud platform",
  status: "requirements",
  archived: false,
  committee_size: 2,
  vendor_count: 0,
  chair: { id: "11111111-1111-4111-8111-111111111111", display_name: "Casey Chair" },
  members: [],
  stage_dates: [],
  actions: {},
  next_step: null,
  row_version: 1,
};

function req(over: Record<string, unknown> = {}) {
  return {
    id: "r1",
    tender_id: TENDER_ID,
    display_no: "REQ-001",
    text: "The supplier shall encrypt all data at rest.",
    category: CAT,
    importance: "mandatory",
    acceptance_criteria: "AES-256",
    origin: "ai",
    review_state: "unreviewed",
    sources: [
      {
        document_version_id: "v1",
        passage_id: "p1",
        page_no: 4,
        quote: "shall encrypt all data",
      },
    ],
    flags: [],
    deleted: false,
    row_version: 3,
    actions: {
      edit: { allowed: true },
      delete: { allowed: true },
      review: { allowed: true },
    },
    ...over,
  };
}

function at(ui: React.ReactElement, path: string) {
  return (
    <Routes>
      <Route path={path} element={ui} />
    </Routes>
  );
}

const SUMMARY = { total: 2, mandatory: 1, optional: 1, reviewed: 0, flagged: 1 };

function listHandler(items: unknown[], extra?: (call: string) => unknown) {
  return (call: string) => {
    if (extra) {
      const out = extra(call);
      if (out !== undefined) return out;
    }
    if (call === "GET /tenders/{tenderId}") return TENDER;
    if (call === "GET /tenders/{tenderId}/requirements") return { items, total: items.length };
    if (call === "GET /tenders/{tenderId}/requirements/summary") return SUMMARY;
    if (call === "GET /categories") return [CAT];
    return undefined;
  };
}

describe("PG-11 Requirements", () => {
  it("lists requirements with origin, flags and state as text, and a summary", async () => {
    const items = [
      req(),
      req({
        id: "r2",
        display_no: "REQ-002",
        text: "Backups should be tested.",
        importance: "optional",
        origin: "manual",
        review_state: "reviewed",
        flags: [{ id: "f1", flag_type: "possible_duplicate", detail: "Looks like REQ-001" }],
      }),
    ];
    const api = fakeApi(listHandler(items));
    const { container } = renderPage(at(<Requirements />, "/tenders/:tenderId/requirements"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/requirements`,
    });
    const table = await screen.findByRole("table");
    expect(within(table).getByText("Not reviewed")).toBeInTheDocument();
    expect(within(table).getByText("Reviewed")).toBeInTheDocument();
    expect(within(table).getByText("Possible duplicate")).toBeInTheDocument();
    expect(within(table).getByText("AI")).toBeInTheDocument();
    expect(screen.getByText("0 of 2 reviewed")).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("saves with the loaded version and a note, and shows the conflict when someone else saved", async () => {
    const mine = req();
    const theirs = req({
      text: "The supplier shall encrypt all customer data at rest.",
      row_version: 4,
      last_changed_by: { id: "u2", display_name: "Morgan Member" },
    });
    let patches = 0;
    const api = fakeApi(
      listHandler([mine], (call) => {
        if (call === "PATCH /requirements/{requirementId}") {
          patches += 1;
          return patches === 1
            ? {
                status: 412,
                error: {
                  title: "Someone else changed this item",
                  status: 412,
                  code: "precondition_failed",
                  current: theirs,
                  changed_by: { id: "u2", display_name: "Morgan Member" },
                  changed_at: "2026-10-01T10:00:00Z",
                  changed_fields: ["text"],
                },
              }
            : mine;
        }
        return undefined;
      }),
    );
    renderPage(at(<Requirements />, "/tenders/:tenderId/requirements"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/requirements`,
    });
    await userEvent.click(await screen.findByRole("button", { name: "REQ-001" }));
    const text = await screen.findByLabelText("Requirement text");
    await userEvent.clear(text);
    await userEvent.type(text, "The supplier shall encrypt everything at rest.");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
    const dialog = await dialogTitled("Save changes to REQ-001");
    // the note is required before the save is possible
    expect(within(dialog).getByRole("button", { name: "Save" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    await userEvent.type(within(dialog).getByLabelText("Note (required)"), "Clearer wording");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(api.PATCH).toHaveBeenCalledTimes(1));
    expect(api.PATCH).toHaveBeenCalledWith(
      "/requirements/{requirementId}",
      expect.objectContaining({
        params: { path: { requirementId: "r1" }, header: { "If-Match": '"3"' } },
        body: expect.objectContaining({ note: "Clearer wording" }),
      }),
    );
    // the conflict names who changed it and offers both versions
    const conflict = await dialogTitled("This requirement was changed");
    expect(
      within(conflict).getByText(/Morgan Member changed this requirement/),
    ).toBeInTheDocument();
    expect(within(conflict).getByText(/customer data at rest/)).toBeInTheDocument();
    await userEvent.click(within(conflict).getByRole("button", { name: "Keep mine" }));
    await waitFor(() => expect(api.PATCH).toHaveBeenCalledTimes(2));
    expect(api.PATCH).toHaveBeenLastCalledWith(
      "/requirements/{requirementId}",
      expect.objectContaining({
        params: { path: { requirementId: "r1" }, header: { "If-Match": '"4"' } },
      }),
    );
  });

  it("is read only with a reason when the baseline is confirmed", async () => {
    const locked = req({
      review_state: "confirmed",
      actions: { edit: { allowed: false, reason: "The baseline is confirmed. Reopen it to edit" } },
    });
    const api = fakeApi(listHandler([locked]));
    renderPage(at(<Requirements />, "/tenders/:tenderId/requirements"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/requirements`,
    });
    expect(
      await screen.findByText(/The baseline is confirmed, so the list is read only/),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "REQ-001" }));
    expect(await screen.findByLabelText("Requirement text")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save changes" })).not.toBeInTheDocument();
  });

  it("applies a bulk change with one note and reports what was skipped", async () => {
    const api = fakeApi(
      listHandler([req(), req({ id: "r2", display_no: "REQ-002" })], (call) =>
        call === "POST /tenders/{tenderId}/requirements/bulk"
          ? { updated: 1, skipped: [{ id: "r2", code: "locked" }] }
          : undefined,
      ),
    );
    renderPage(at(<Requirements />, "/tenders/:tenderId/requirements"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/requirements`,
    });
    await userEvent.click(await screen.findByLabelText("Select REQ-001"));
    await userEvent.click(screen.getByLabelText("Select REQ-002"));
    await userEvent.click(screen.getByRole("button", { name: "Mark reviewed" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Note (required)"), "Reviewed together");
    await userEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    await waitFor(() =>
      expect(api.POST).toHaveBeenCalledWith(
        "/tenders/{tenderId}/requirements/bulk",
        expect.objectContaining({
          body: expect.objectContaining({
            ids: ["r1", "r2"],
            action: "mark_reviewed",
            note: "Reviewed together",
          }),
        }),
      ),
    );
  });
});

describe("PG-12 Baseline and scoring", () => {
  const model = {
    points_fully: 1,
    points_partially: 0.5,
    points_non: 0,
    mandatory_rule: "flag_only",
    weights: [],
    locked: false,
    row_version: 0,
  };
  const handler = (ready: boolean, extra?: (c: string) => unknown) => (call: string) => {
    if (extra) {
      const out = extra(call);
      if (out !== undefined) return out;
    }
    if (call === "GET /tenders/{tenderId}") return TENDER;
    if (call === "GET /tenders/{tenderId}/baselines/precheck")
      return {
        ready,
        failures: ready
          ? []
          : [
              {
                code: "unreviewed",
                message: "3 requirements are not reviewed",
                link: `/tenders/${TENDER_ID}/requirements`,
              },
            ],
        counts: { total: 10, mandatory: 7, optional: 3 },
        scoring: model,
        next_version_no: 1,
      };
    if (call === "GET /tenders/{tenderId}/baselines") return [];
    if (call === "GET /tenders/{tenderId}/scoring-model") return model;
    if (call === "GET /categories") return [CAT];
    return undefined;
  };
  // the Chair of TENDER is the person makeMe signs in as
  it("shows the checks, disables confirm with its reason, and names what is missing", async () => {
    const api = fakeApi(handler(false));
    const { container } = renderPage(at(<Baseline />, "/tenders/:tenderId/baseline"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/baseline`,
    });
    expect(await screen.findByText("3 requirements are not reviewed")).toBeInTheDocument();
    const confirm = screen.getByRole("button", { name: "Confirm requirements" });
    expect(confirm).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("Resolve the checks above first")).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("confirms after a dialog that says what becomes locked", async () => {
    const api = fakeApi(
      handler(true, (call) =>
        call === "POST /tenders/{tenderId}/baselines"
          ? {
              id: "b1",
              tender_id: TENDER_ID,
              version_no: 1,
              status: "confirmed",
              requirement_count: 10,
              confirmed_at: "2026-10-01T10:00:00Z",
            }
          : undefined,
      ),
    );
    renderPage(at(<Baseline />, "/tenders/:tenderId/baseline"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/baseline`,
    });
    await userEvent.click(await screen.findByRole("button", { name: "Confirm requirements" }));
    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByText(/Confirm 10 requirements\? The list becomes Baseline v1/),
    ).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Confirm requirements" }));
    await waitFor(() =>
      expect(api.POST).toHaveBeenCalledWith(
        "/tenders/{tenderId}/baselines",
        expect.objectContaining({
          params: expect.objectContaining({ header: { "Idempotency-Key": expect.any(String) } }),
        }),
      ),
    );
  });
});
