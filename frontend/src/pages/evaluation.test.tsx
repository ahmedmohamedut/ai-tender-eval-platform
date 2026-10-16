import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, makeMe, renderPage } from "../test/helpers";
import { CrossVendor } from "./CrossVendor";
import { EvaluationOverview } from "./EvaluationOverview";
import { EvaluationSheet } from "./EvaluationSheet";

const TENDER_ID = "22222222-2222-4222-8222-222222222222";
const TV = "33333333-3333-4333-8333-333333333333";
const TENDER = {
  id: TENDER_ID,
  name: "Cloud platform",
  status: "evaluation",
  archived: false,
  committee_size: 3,
  vendor_count: 1,
  chair: { id: "11111111-1111-4111-8111-111111111111", display_name: "Casey Chair" },
  members: [],
  stage_dates: [],
  actions: {},
  next_step: null,
  row_version: 1,
};

function row(over: Record<string, unknown> = {}) {
  return {
    requirement: {
      id: "r1",
      display_no: "REQ-001",
      text: "The supplier shall encrypt all data at rest.",
      category: "Security",
      importance: "mandatory",
      acceptance_criteria: "AES-256",
    },
    ai: {
      id: "g1",
      outcome: "graded",
      grade: "fully_compliant",
      rationale_summary: "Stated",
      rationale: "The proposal states that data is encrypted at rest.",
      missing_elements: [],
      confidence: 0.9,
      citations: [
        {
          citation_no: 1,
          document_version_id: "dv1",
          passage_id: "p1",
          page_no: 7,
          excerpt: "We encrypt all customer data at rest.",
        },
      ],
      is_current: true,
    },
    member: { review_state: "not_reviewed", needs_clarification: false },
    flags: [],
    row_version: 3,
    ...over,
  };
}

function evaluation(over: Record<string, unknown> = {}) {
  return {
    id: "e1",
    tender_vendor_id: TV,
    vendor_name: "Acme Cloud",
    status: "in_progress",
    excluded: false,
    summary: {
      counts: {
        fully_compliant: 0,
        partially_compliant: 0,
        non_compliant: 0,
        not_reviewed: 2,
        not_evaluated: 0,
      },
      mandatory_failures: 0,
      reviewed: 0,
      total: 2,
      by_category: [],
      compliance_percent: 0,
      explanation: {
        basis: "your own decisions only.",
        points_fully: 1,
        points_partially: 0.5,
        points_non: 0,
      },
    },
    job: null,
    actions: {
      decide_rows: { allowed: true },
      submit: { allowed: false, reason: "2 rows still need a decision" },
    },
    ...over,
  };
}

function handler(
  extra?: (call: string, init?: unknown) => unknown,
  rows = [row()],
  ev = evaluation(),
) {
  return (call: string, init?: unknown) => {
    if (extra) {
      const out = extra(call, init);
      if (out !== undefined) return out;
    }
    if (call === "GET /tenders/{tenderId}") return TENDER;
    if (call === "GET /tender-vendors/{tenderVendorId}/evaluation") return ev;
    if (call === "GET /tender-vendors/{tenderVendorId}/evaluation/rows") {
      return { items: rows, next_cursor: null };
    }
    if (call === "GET /tender-vendors/{tenderVendorId}/evaluation/rows/{requirementId}/history") {
      return { events: [] };
    }
    return undefined;
  };
}

const ROUTE = `/tenders/${TENDER_ID}/vendors/${TV}/evaluation`;
function sheet() {
  return (
    <Routes>
      <Route
        path="/tenders/:tenderId/vendors/:tenderVendorId/evaluation"
        element={<EvaluationSheet />}
      />
    </Routes>
  );
}

describe("PG-15 Evaluation sheet", () => {
  it("shows the independence note, the counter, the AI evidence and why submit is disabled", async () => {
    const api = fakeApi(handler());
    const { container } = renderPage(sheet(), { api, me: makeMe("member"), route: ROUTE });
    expect(await screen.findByRole("heading", { name: /Acme Cloud/ })).toBeInTheDocument();
    expect(screen.getByText(/Your grades and notes are private/)).toBeInTheDocument();
    expect(screen.getByText("0 of 2 reviewed")).toBeInTheDocument();
    const detail = await screen.findByLabelText("Detail of REQ-001");
    expect(within(detail).getByText(/data is encrypted at rest/)).toBeInTheDocument();
    expect(within(detail).getByText(/\(page 7\)/)).toBeInTheDocument();
    expect(within(detail).getByRole("button", { name: "Open page 7" })).toBeInTheDocument();
    const submit = screen.getByRole("button", { name: "Submit this vendor" });
    expect(submit).toBeDisabled();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("agrees with the row version of the loaded row and announces it", async () => {
    const patches: { header: unknown; body: unknown }[] = [];
    const api = fakeApi(
      handler((call, init) => {
        if (call === "PATCH /tender-vendors/{tenderVendorId}/evaluation/rows/{requirementId}") {
          const i = init as { params: { header: unknown }; body: unknown };
          patches.push({ header: i.params.header, body: i.body });
          return row({
            member: {
              review_state: "agreed",
              grade: "fully_compliant",
              needs_clarification: false,
            },
            row_version: 4,
          });
        }
        return undefined;
      }),
    );
    renderPage(sheet(), { api, me: makeMe("member"), route: ROUTE });
    await userEvent.click(await screen.findByRole("button", { name: "Agree with AI" }));
    await waitFor(() => expect(patches).toHaveLength(1));
    expect(patches[0]!.header).toEqual({ "If-Match": '"3"' });
    expect(patches[0]!.body).toEqual({ action: "agree" });
  });

  it("asks for a grade and a note of the minimum length to override", async () => {
    const patches: { body: unknown }[] = [];
    const api = fakeApi(
      handler((call, init) => {
        if (call.startsWith("PATCH ")) {
          patches.push({ body: (init as { body: unknown }).body });
          return row();
        }
        return undefined;
      }),
    );
    renderPage(sheet(), { api, me: makeMe("member"), route: ROUTE });
    await userEvent.click(await screen.findByRole("button", { name: "Override" }));
    const dialog = await screen.findByRole("dialog", { name: /Override the AI grade/ });
    const save = within(dialog).getByRole("button", { name: "Save override" });
    expect(save).toBeDisabled();
    await userEvent.type(
      within(dialog).getByLabelText("Note (required)"),
      "Encryption is in transit only",
    );
    await userEvent.click(save);
    await waitFor(() => expect(patches).toHaveLength(1));
    expect(patches[0]!.body).toEqual({
      action: "override",
      grade: "non_compliant",
      note: "Encryption is in transit only",
    });
  });

  it("lists what blocks the submit when the server refuses it", async () => {
    const ready = evaluation({
      actions: { decide_rows: { allowed: true }, submit: { allowed: true } },
    });
    const api = fakeApi(
      handler(
        (call) => {
          if (call === "POST /tender-vendors/{tenderVendorId}/evaluation/submit") {
            return {
              status: 422,
              error: {
                title: "The evaluation is not ready to submit",
                status: 422,
                code: "precheck_failed",
                failures: [{ code: "rows_not_reviewed", message: "2 rows are not reviewed" }],
              },
            };
          }
          return undefined;
        },
        [row()],
        ready,
      ),
    );
    renderPage(sheet(), { api, me: makeMe("member"), route: ROUTE });
    await userEvent.click(await screen.findByRole("button", { name: "Submit this vendor" }));
    const dialog = await screen.findByRole("dialog", { name: /Submit Acme Cloud/ });
    await userEvent.click(within(dialog).getByRole("button", { name: "Submit" }));
    expect(await screen.findByText("2 rows are not reviewed")).toBeInTheDocument();
  });
});

describe("UX-EVA-17 and PG-16", () => {
  it("lists the vendors with progress and marks the next one", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /tenders/{tenderId}") return TENDER;
      if (call === "GET /tenders/{tenderId}/evaluations") {
        return {
          items: [
            {
              tender_vendor_id: TV,
              vendor_name: "Acme Cloud",
              status: "in_progress",
              reviewed: 3,
              total: 10,
              outdated: 1,
              flagged: 2,
              is_next: true,
              job_status: "completed",
            },
          ],
        };
      }
      return undefined;
    });
    renderPage(
      <Routes>
        <Route path="/tenders/:tenderId/evaluation" element={<EvaluationOverview />} />
      </Routes>,
      { api, me: makeMe("member"), route: `/tenders/${TENDER_ID}/evaluation` },
    );
    expect(await screen.findByText("3 of 10")).toBeInTheDocument();
    expect(screen.getByText("Next")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Acme Cloud" })).toHaveAttribute(
      "href",
      `/tenders/${TENDER_ID}/vendors/${TV}/evaluation`,
    );
  });

  it("compares one requirement across vendors with the member's own decision", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /tenders/{tenderId}") return TENDER;
      if (call === "GET /requirements/{requirementId}/cross-vendor") {
        return {
          requirement: row().requirement,
          vendors: [
            {
              tender_vendor_id: TV,
              vendor_name: "Acme Cloud",
              ai: {
                id: "g1",
                outcome: "graded",
                grade: "partially_compliant",
                rationale_summary: "Part of it",
                citations: [],
              },
              my_decision: {
                review_state: "overridden",
                grade: "non_compliant",
                needs_clarification: false,
              },
            },
          ],
          previous_requirement_id: null,
          next_requirement_id: "r2",
        };
      }
      return undefined;
    });
    const { container } = renderPage(
      <Routes>
        <Route
          path="/tenders/:tenderId/requirements/:requirementId/compare"
          element={<CrossVendor />}
        />
      </Routes>,
      { api, me: makeMe("member"), route: `/tenders/${TENDER_ID}/requirements/r1/compare` },
    );
    expect(await screen.findByText(/Partially compliant/)).toBeInTheDocument();
    expect(screen.getByText("Non-compliant")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Next requirement" })).toHaveAttribute(
      "href",
      `/tenders/${TENDER_ID}/requirements/r2/compare`,
    );
    expect(await accessibilityViolations(container)).toEqual([]);
  });
});
